package dev.celastrina.core.configuration

import dev.celastrina.core.extension.AvailableExtension
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.extension.ExtensionFailure
import dev.celastrina.core.extension.ExtensionHost
import dev.celastrina.core.extension.ExtensionRepository
import dev.celastrina.core.extension.ExtensionSource
import dev.celastrina.core.extension.ExtensionWarning
import dev.celastrina.core.extension.ListedExtension
import dev.celastrina.core.extension.ProvidedSource
import dev.celastrina.core.extension.RepositoryReader
import dev.celastrina.core.extension.RepositoryUnreadable
import dev.celastrina.core.port.ExtensionLoader
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId

/** Extension Repositories and the Extensions installed from them, with the Sources each Extension provides. */
internal class ExtensionSettings(
    store: ConfigurationStore,
    http: HttpClient,
    private val appVersion: String,
    private val clock: Clock,
    private val logger: Logger,
    private val failoverMemory: FailoverMemory?,
    extensionLoader: ExtensionLoader,
    private val announce: (ConfigurationChange) -> Unit,
) : StoreAccess(store) {
    private val extensionHost = ExtensionHost(extensionLoader, logger)
    private val repositoryReader = RepositoryReader(http)

    init {
        // Code of Extensions removed or replaced since the last start goes now, before any Extension is loaded.
        extensionLoader.keepOnly(synchronized(lock) { loadExtensions().installed.map { it.sha256 }.toSet() })
    }

    /**
     * Imports the Extension Repository at [url] and answers the Extensions it lists. The Household must first have seen
     * [ExtensionWarning]: without [warningAcknowledged] set to its current version this refuses, answering the warning
     * to show. Importing again refreshes the list.
     */
    suspend fun importRepository(url: String, warningAcknowledged: Int?, from: ChangedFrom): ImportOutcome {
        if (warningAcknowledged != ExtensionWarning.VERSION) {
            return ImportOutcome.WarningNotAcknowledged(ExtensionWarning.TEXT, ExtensionWarning.VERSION)
        }
        val address = webAddress(url) ?: return ImportOutcome.Invalid(
            WriteOutcome.Problem.BadUrl,
            "An Extension Repository's address starts http:// or https://, such as https://example.org/repo.json.",
        )
        synchronized(lock) { storage.write(WARNING_ACKNOWLEDGED, ExtensionWarning.VERSION.toString().encodeToByteArray()) }
        val fetched = try {
            repositoryReader.read(address)
        } catch (e: RepositoryUnreadable) {
            return ImportOutcome.Failed(e.reason, e.message)
        }
        val isNew = synchronized(lock) {
            val extensions = loadExtensions()
            val isNew = extensions.repositories.none { it.url == address }
            val repository = StoredRepository(address, fetched.name, fetched.extensions)
            saveExtensions(extensions.copy(repositories = extensions.repositories.filter { it.url != address } + repository))
            isNew
        }
        if (isNew) announce(ConfigurationChange(ConfigurationChange.Action.RepositoryImported, fetched.name, from))
        return ImportOutcome.Imported(checkNotNull(repository(address)))
    }

    /** The Extension Repository imported from [url], with the Extensions it lists, or null when there's none. */
    fun repository(url: String): ExtensionRepository? = repositories().find { it.url == url }

    /** Every imported Extension Repository, oldest first, with the Extensions it lists and which are installed. */
    fun repositories(): List<ExtensionRepository> = synchronized(lock) {
        val extensions = loadExtensions()
        extensions.repositories.map { repository ->
            ExtensionRepository(
                url = repository.url,
                name = repository.name,
                extensions = repository.extensions.map { listed ->
                    val installed = extensions.installed.find { it.name == listed.name && it.repositoryUrl == repository.url }
                    AvailableExtension(
                        name = listed.name,
                        displayName = listed.displayName,
                        version = listed.version,
                        description = listed.description,
                        authors = listed.authors,
                        languages = listOfNotNull(listed.language),
                        installed = installed != null,
                        installedVersion = installed?.version,
                    )
                },
            )
        }
    }

    /**
     * Reads every imported Extension Repository again for newer versions of the installed Extensions, and answers how
     * many have one. It reads them at most once a day, unless asked to check [now]; a Repository that can't be read
     * keeps what it listed before. Nothing is updated: the Household updates by hand.
     */
    suspend fun checkForUpdates(now: Boolean = false): Int {
        val urls = synchronized(lock) {
            val last = storage.read(UPDATES_CHECKED)?.decodeToString()?.toLongOrNull()
            if (!now && last != null && clock.nowMillis() - last < UPDATE_CHECK_INTERVAL) return updatesAvailable()
            storage.write(UPDATES_CHECKED, clock.nowMillis().toString().encodeToByteArray())
            loadExtensions().repositories.map { it.url }
        }
        for (url in urls) {
            val fetched = try {
                repositoryReader.read(url)
            } catch (e: RepositoryUnreadable) {
                logger.info("Couldn't check $url for updates: ${e.message}")
                continue
            }
            synchronized(lock) {
                val extensions = loadExtensions()
                val refreshed = extensions.repositories.map { if (it.url == url) StoredRepository(url, fetched.name, fetched.extensions) else it }
                saveExtensions(extensions.copy(repositories = refreshed))
            }
        }
        return updatesAvailable()
    }

    /** How many installed Extensions have a newer version listed, as of the last update check. */
    fun updatesAvailable(): Int = synchronized(lock) { loadExtensions().updatable().size }

    /**
     * Updates the installed Extension [name] to the version its Extension Repository lists, checked against the list's
     * SHA-256 as an install is. Its Sources stay where they are in the order. Updating one with no update is harmless.
     */
    suspend fun updateExtension(name: String, from: ChangedFrom): InstallOutcome {
        val (installed, repository, listed) = synchronized(lock) {
            val extensions = loadExtensions()
            val installed = extensions.installed.find { it.name == name } ?: return InstallOutcome.Invalid(
                WriteOutcome.Problem.UnknownExtension,
                "There's no installed Extension $name. Installed: ${extensions.installed.joinToString { it.name }.ifEmpty { "none" }}.",
            )
            if (installed !in extensions.updatable()) return InstallOutcome.Installed(sourceIdsOf(name))
            val repository = extensions.repositories.first { it.url == installed.repositoryUrl }
            Triple(installed, repository, checkNotNull(extensions.listedFor(installed)))
        }
        val code = when (val fetched = fetchCode(repository, listed)) {
            is FetchedCode.Refused -> return fetched.outcome
            is FetchedCode.Code -> fetched
        }
        val synced = synchronized(lock) {
            storage.write(codeKey(code.sha256), code.bytes)
            val extensions = loadExtensions()
            val updated = installed.copy(
                displayName = listed.displayName,
                version = listed.version,
                sha256 = code.sha256,
                needsNewerThan = appVersion.takeIf { code.provided == null },
            )
            saveExtensions(extensions.copy(installed = extensions.installed.map { if (it.name == name) updated else it }))
            if (code.sha256 != installed.sha256) storage.delete(codeKey(installed.sha256))
            code.provided?.let { syncSources(name, it, from) }
        }
        announce(ConfigurationChange(ConfigurationChange.Action.ExtensionUpdated, listed.displayName, from))
        synced?.let { announce(it) }
        if (code.provided == null) return InstallOutcome.Failed(Reason.SourceError, "${listed.displayName}: ${ConfigurationService.NEEDS_NEWER_CELASTRINA}.")
        return InstallOutcome.Installed(synchronized(lock) { sourceIdsOf(name) })
    }

    /**
     * Tries again each installed Extension that needed a newer Celastrina than an earlier one: after an app update, one
     * that loads now gets its Sources back, or its first ones if it never loaded. Call it when Celastrina starts.
     */
    suspend fun recheckExtensions() {
        val waiting = synchronized(lock) { loadExtensions().installed.filter { it.needsNewerThan != null && !it.needsNewerCelastrina() } }
        for (installed in waiting) {
            val provided = try {
                extensionHost.sourcesOf(installed.name, installed.sha256) {
                    checkNotNull(storage.read(codeKey(installed.sha256))) { "Extension ${installed.name}'s code is missing" }
                }
            } catch (e: ExtensionFailure) {
                if (e.needsNewerCelastrina) markNeedsNewerCelastrina(installed.name)
                continue
            }
            val synced = synchronized(lock) {
                val extensions = loadExtensions()
                saveExtensions(extensions.copy(installed = extensions.installed.map { if (it.name == installed.name) it.copy(needsNewerThan = null) else it }))
                syncSources(installed.name, provided, ChangedFrom.Tv)
            }
            announce(synced)
        }
    }

    /** What making an Extension's Sources match the Sources it provides changed, as the announcements to make once it is saved. */
    private class Synced(val added: List<StoredSource>, val removed: List<StoredSource>, val from: ChangedFrom)

    private fun announce(synced: Synced) {
        if (synced.removed.isNotEmpty()) failoverMemory?.forget(synced.removed.map { it.id })
        synced.removed.forEach { announce(ConfigurationChange(ConfigurationChange.Action.Removed, it.name, synced.from)) }
        synced.added.forEach { announce(ConfigurationChange(ConfigurationChange.Action.Added, it.name, synced.from)) }
    }

    /**
     * Makes the Extension [name]'s Sources match the Sources it [provided]: each new one joins the end of the order,
     * enabled, and one it no longer provides leaves it. The rest stay where they are. Call it holding the lock.
     */
    private fun syncSources(name: String, provided: List<ProvidedSource>, from: ChangedFrom): Synced {
        val before = load()
        val providedNames = provided.map { it.name }.toSet()
        val removed = before.filter { it.extension == name && it.nameInExtension !in providedNames }
        var sources = (before - removed.toSet()).map { stored ->
            val now = provided.find { stored.extension == name && it.name == stored.nameInExtension } ?: return@map stored
            stored.copy(titleKinds = now.titleKinds, imdb = now.imdb)
        }
        val added = provided.filter { p -> sources.none { it.extension == name && it.nameInExtension == p.name } }.map { p ->
            StoredSource(
                id = uniqueId(SourceId.of("$name ${p.name}"), sources),
                name = uniqueName(p.name, sources),
                kind = EXTENSION_KIND,
                extension = name,
                nameInExtension = p.name,
                titleKinds = p.titleKinds,
                imdb = p.imdb,
                addedFromComputerAt = clock.nowMillis().takeIf { from == ChangedFrom.Computer },
            ).also { sources = sources + it }
        }
        if (sources != before) save(sources)
        return Synced(added, removed, from)
    }

    /** Updates every installed Extension that has an update, answering how each went by its name. */
    suspend fun updateAllExtensions(from: ChangedFrom): Map<String, InstallOutcome> =
        synchronized(lock) { loadExtensions().updatable().map { it.name } }.associateWith { updateExtension(it, from) }

    /**
     * Removes the installed Extension [name]: its Sources leave the order at once, with their Failover memory, and its
     * code is gone by the next start. Removing one that isn't installed is harmless.
     */
    fun removeExtension(name: String, from: ChangedFrom): WriteOutcome {
        val (removed, sourceIds) = synchronized(lock) {
            val extensions = loadExtensions()
            val removed = extensions.installed.find { it.name == name } ?: return WriteOutcome.Ok
            saveExtensions(extensions.copy(installed = extensions.installed - removed))
            storage.delete(codeKey(removed.sha256))
            val sources = load()
            val gone = sources.filter { it.extension == name }
            save(sources - gone.toSet())
            removed to gone.map { it.id }
        }
        failoverMemory?.forget(sourceIds)
        announce(ConfigurationChange(ConfigurationChange.Action.ExtensionRemoved, removed.displayName, from))
        return WriteOutcome.Ok
    }

    /** Removes the Extension Repository imported from [url] and every Extension installed from it. Harmless if it's gone. */
    fun removeRepository(url: String, from: ChangedFrom): WriteOutcome {
        val installedFromIt = synchronized(lock) { loadExtensions().installed.filter { it.repositoryUrl == url }.map { it.name } }
        installedFromIt.forEach { removeExtension(it, from) }
        val removed = synchronized(lock) {
            val extensions = loadExtensions()
            val removed = extensions.repositories.find { it.url == url } ?: return WriteOutcome.Ok
            saveExtensions(extensions.copy(repositories = extensions.repositories - removed))
            removed
        }
        announce(ConfigurationChange(ConfigurationChange.Action.RepositoryRemoved, removed.name, from))
        return WriteOutcome.Ok
    }

    private fun sourceIdsOf(extension: String) = load().filter { it.extension == extension }.map { it.id }

    private sealed interface FetchedCode {
        /** [provided] is null when the code needs a newer Celastrina. */
        class Code(val bytes: ByteArray, val sha256: String, val provided: List<ProvidedSource>?) : FetchedCode
        class Refused(val outcome: InstallOutcome) : FetchedCode
    }

    /** Downloads [listed]'s code, checks it against the SHA-256 [repository] lists, and loads it. */
    private suspend fun fetchCode(repository: StoredRepository, listed: ListedExtension): FetchedCode {
        val sha256 = listed.sha256 ?: return FetchedCode.Refused(
            InstallOutcome.Invalid(WriteOutcome.Problem.MissingHash, "${repository.name} gives no SHA-256 for ${listed.name}, so it can't be checked and wasn't installed."),
        )
        val code = try {
            repositoryReader.download(listed.url)
        } catch (e: RepositoryUnreadable) {
            return FetchedCode.Refused(InstallOutcome.Failed(e.reason, e.message))
        }
        if (sha256Of(code) != sha256) {
            return FetchedCode.Refused(
                InstallOutcome.Invalid(
                    WriteOutcome.Problem.HashMismatch,
                    "${listed.name}'s download doesn't match the SHA-256 ${repository.name} lists for it, so it wasn't installed.",
                ),
            )
        }
        val provided = try {
            extensionHost.sourcesOf(listed.name, sha256) { code }
        } catch (e: ExtensionFailure) {
            if (!e.needsNewerCelastrina) return FetchedCode.Refused(InstallOutcome.Failed(e.reason, "${listed.displayName} didn't load."))
            null
        }
        return FetchedCode.Code(code, sha256, provided)
    }

    /**
     * Installs the Extension [name] from the imported Extension Repository [repositoryUrl]. Its download must match the
     * list's SHA-256, and the Extension stays pinned to that Repository: one with the same name from another is refused.
     * Each Source it provides joins the end of the order, enabled. Installing it again is harmless.
     */
    suspend fun installExtension(repositoryUrl: String, name: String, from: ChangedFrom): InstallOutcome {
        val (repository, listed) = synchronized(lock) {
            val extensions = loadExtensions()
            val repository = extensions.repositories.find { it.url == repositoryUrl } ?: return InstallOutcome.Invalid(
                WriteOutcome.Problem.UnknownRepository,
                "There's no Extension Repository $repositoryUrl. Import it first.",
            )
            val listed = repository.extensions.find { it.name == name } ?: return InstallOutcome.Invalid(
                WriteOutcome.Problem.UnknownExtension,
                "${repository.name} lists no Extension $name. It lists: ${repository.extensions.joinToString { it.name }}.",
            )
            extensions.installed.find { it.name == name }?.let { installed ->
                if (installed.repositoryUrl != repositoryUrl) {
                    return InstallOutcome.Invalid(
                        WriteOutcome.Problem.PinnedToOtherRepository,
                        "An Extension called $name is already installed from ${installed.repositoryUrl}, and stays pinned to it. " +
                            "Remove that one before installing $name from $repositoryUrl.",
                    )
                }
                return InstallOutcome.Installed(load().filter { it.extension == name }.map { it.id })
            }
            repository to listed
        }
        val code = when (val fetched = fetchCode(repository, listed)) {
            is FetchedCode.Refused -> return fetched.outcome
            is FetchedCode.Code -> fetched
        }
        val installed = InstalledExtension(
            name, listed.displayName, repositoryUrl, listed.version, code.sha256,
            needsNewerThan = appVersion.takeIf { code.provided == null },
        )
        val synced = synchronized(lock) {
            storage.write(codeKey(code.sha256), code.bytes)
            val extensions = loadExtensions()
            saveExtensions(extensions.copy(installed = extensions.installed.filter { it.name != name } + installed))
            syncSources(name, code.provided.orEmpty(), from)
        }
        announce(synced)
        if (code.provided == null) return InstallOutcome.Failed(Reason.SourceError, "${listed.displayName}: ${ConfigurationService.NEEDS_NEWER_CELASTRINA}.")
        return InstallOutcome.Installed(synced.added.map { it.id })
    }

    /** The Source [stored], from an installed Extension; null when its Extension is gone. */
    fun source(stored: StoredSource): Source? {
        val extensions = loadExtensions()
        val installed = extensions.installed.find { it.name == stored.extension } ?: return null
        return ExtensionSource(
            id = stored.id,
            name = stored.name,
            kind = ExtensionSource.kind(stored.titleKinds, stored.imdb),
            extension = installed.name,
            nameInExtension = checkNotNull(stored.nameInExtension) { "Source ${stored.id} has no name in its Extension" },
            host = extensionHost,
            providers = {
                extensionHost.providers(installed.name, installed.sha256) {
                    checkNotNull(storage.read(codeKey(installed.sha256))) { "Extension ${installed.name}'s code is missing" }
                }
            },
            onNeedsNewerCelastrina = { markNeedsNewerCelastrina(installed.name) },
            updateAvailable = installed in extensions.updatable(),
            needsNewerCelastrina = installed.needsNewerCelastrina(),
        )
    }

    /** Stops Lookups running the Extension [name]'s code, until Celastrina is updated: it links against code this one lacks. */
    private fun markNeedsNewerCelastrina(name: String) = synchronized(lock) {
        val extensions = loadExtensions()
        saveExtensions(extensions.copy(installed = extensions.installed.map { if (it.name == name) it.copy(needsNewerThan = appVersion) else it }))
    }

    /** Whether this Extension needs a newer Celastrina than this one. Once Celastrina is updated, it gets another try. */
    private fun InstalledExtension.needsNewerCelastrina() = needsNewerThan == appVersion

    /** Whether [extension] needs a newer Celastrina than this one. */
    fun needsNewer(extension: InstalledExtension) = extension.needsNewerCelastrina()
}
