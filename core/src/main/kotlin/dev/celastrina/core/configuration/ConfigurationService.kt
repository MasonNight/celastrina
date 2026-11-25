package dev.celastrina.core.configuration

import com.lagradost.cloudstream3.syncproviders.SyncIdName
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.extension.AvailableExtension
import dev.celastrina.core.extension.ExtensionFailure
import dev.celastrina.core.extension.ExtensionHost
import dev.celastrina.core.extension.ExtensionRepository
import dev.celastrina.core.extension.ExtensionSource
import dev.celastrina.core.extension.ExtensionWarning
import dev.celastrina.core.extension.ListedExtension
import dev.celastrina.core.extension.RepositoryReader
import dev.celastrina.core.extension.RepositoryUnreadable
import dev.celastrina.core.port.ExtensionLoader
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.Logger
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.ConnectionResult
import dev.celastrina.core.source.jellyfin.MediaServer
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.jellyfin.MediaServerLogin
import dev.celastrina.core.source.jellyfin.QuickConnectStep
import dev.celastrina.core.source.slugOf
import dev.celastrina.core.source.stremio.ManifestCheck
import dev.celastrina.core.source.stremio.StremioAddon
import dev.celastrina.core.source.stremio.StremioAddonKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.UUID

/**
 * The one place that reads, validates and saves the Household's configuration. The on-TV screens and the
 * agent's ContentProvider both go through it, so they get the same rules and messages.
 *
 * Every write that adds, changes or enables a Source tests its connection straight after saving. A Source that fails
 * its test stays saved, marked failing, and the write answers [WriteOutcome.Failed] with the reason.
 */
class ConfigurationService(
    private val storage: Storage,
    private val http: HttpClient,
    private val appVersion: String,
    /** The TMDB key built into the app; blank in a build made without one. */
    private val projectTmdbKey: String,
    extensionLoader: ExtensionLoader = ExtensionLoader.None,
    logger: Logger = Logger.StandardOut,
    /** Dates the marks on Sources added or changed from a computer. */
    private val clock: Clock = Clock.System,
) {
    private val lock = Any()
    private val extensionHost = ExtensionHost(extensionLoader, logger)
    private val repositories = RepositoryReader(http)
    private val announcements = MutableSharedFlow<ConfigurationChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each change as it is saved, for the on-screen notices. A command that changes nothing announces nothing. */
    val changes: SharedFlow<ConfigurationChange> = announcements.asSharedFlow()

    fun status(): Status = synchronized(lock) {
        Status(
            appVersion = appVersion,
            tmdbKey = tmdbKeyInUse(),
            sources = load().size,
            extensions = loadExtensions().installed.size,
            protocolVersion = PROTOCOL_VERSION,
        )
    }

    /** How the Catalogue reaches TMDB: with the Household's own key when it has set one, else the project key. */
    fun tmdbSettings(): TmdbSettings = synchronized(lock) { TmdbSettings(apiKey = tmdbKeyOverride() ?: projectTmdbKey) }

    /** Uses the Household's own TMDB key in place of the project key, from now on. */
    fun setTmdbKey(key: String, from: ChangedFrom): WriteOutcome {
        val newKey = key.trim()
        if (newKey.isEmpty()) {
            return WriteOutcome.Invalid(
                WriteOutcome.Problem.BlankTmdbKey,
                "A TMDB key can't be blank. To go back to the project key, clear the key instead.",
            )
        }
        synchronized(lock) {
            if (tmdbKeyOverride() == newKey) return WriteOutcome.Ok
            storage.write(TMDB_KEY, newKey.encodeToByteArray())
        }
        announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.TmdbKeySet, sourceName = null, from))
        return WriteOutcome.Ok
    }

    /** Goes back to the project key. Clearing when the Household has no key of its own is harmless. */
    fun clearTmdbKey(from: ChangedFrom): WriteOutcome {
        synchronized(lock) {
            if (tmdbKeyOverride() == null) return WriteOutcome.Ok
            storage.delete(TMDB_KEY)
        }
        announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.TmdbKeyCleared, sourceName = null, from))
        return WriteOutcome.Ok
    }

    private fun tmdbKeyOverride(): String? = storage.read(TMDB_KEY)?.decodeToString()

    private fun tmdbKeyInUse() = when {
        tmdbKeyOverride() != null -> TmdbKeyInUse.Override
        projectTmdbKey.isNotBlank() -> TmdbKeyInUse.Project
        else -> TmdbKeyInUse.None
    }

    /** Every Source in the Household's order. Never includes a secret, only whether there is a login. */
    fun list(): List<SourceSummary> = synchronized(lock) {
        val needingNewer = loadExtensions().installed.filter { it.needsNewerCelastrina }.map { it.name }
        load().mapIndexed { index, it ->
            SourceSummary(
                id = it.id,
                name = it.name,
                kind = it.kind,
                enabled = it.enabled,
                position = index + 1,
                testState = it.testState,
                hasLogin = login(it.id) != null,
                problem = NEEDS_NEWER_CELASTRINA.takeIf { _ -> it.extension in needingNewer },
                addedFromComputerAt = it.addedFromComputerAt,
                changedFromComputerAt = it.changedFromComputerAt,
            )
        }
    }

    /** The enabled Sources, in the order a Lookup asks them. */
    fun sources(): List<Source> = synchronized(lock) {
        load().filter { it.enabled }.mapNotNull { stored ->
            if (stored.extension != null) return@mapNotNull extensionSource(stored)
            when (val kind = kindOf(stored.kind)) {
                InternetArchiveSource.Kind -> InternetArchiveSource(http, id = stored.id, name = stored.name)
                in MediaServerKinds.all -> server(stored).source(stored.id, stored.name, kind!!, login(stored.id))
                StremioAddonKind -> addon(stored).source(stored.id, stored.name, storage.read(manifestKey(stored.id))?.decodeToString())
                else -> error("Unknown Source Kind ${stored.kind} for Source ${stored.id}")
            }
        }
    }

    /**
     * Adds a Source at the end of the order and answers its ID in [WriteOutcome.Added]. A Jellyfin or Emby server is
     * logged in to straight away, and only the access token is kept. Adding the same Source again is harmless.
     */
    suspend fun add(new: NewSource, from: ChangedFrom): WriteOutcome {
        val kind = kindOf(new.kind) ?: return invalid(
            WriteOutcome.Problem.UnknownKind,
            "There's no Source Kind ${new.kind}. Kinds: ${KINDS.joinToString { it.id }}.",
        )
        val name = new.name.trim()
        if (name.isEmpty()) return blankName()
        val url = if (kind == InternetArchiveSource.Kind) {
            if (new.url != null || new.username != null || new.password != null) return unneededSetting(kind)
            null
        } else if (kind == StremioAddonKind) {
            if (new.username != null || new.password != null) return unneededSetting(kind)
            StremioAddon.manifestUrlOf(new.url) ?: return badUrl(kind)
        } else {
            val url = serverAddress(new.url) ?: return badUrl(kind)
            if (new.username.isNullOrBlank() || new.password == null) {
                return invalid(WriteOutcome.Problem.MissingLogin, "A ${kind.name} Source needs a username and a password (which may be empty).")
            }
            url
        }
        val id = when (val saved = saveNew(name, kind, url, new.username?.trim(), from)) {
            is Saved.As -> saved.id
            is Saved.Refused -> return saved.outcome
        }
        return when (val tested = test(id, logInWith = new.password)) {
            WriteOutcome.Ok -> WriteOutcome.Added(id)
            else -> tested
        }
    }

    /**
     * Starts adding a Jellyfin server with Quick Connect: the answer's code is for the Household to approve in the
     * server's Quick Connect page, signed in on another device. [new] needs the kind, name and server address. Nothing
     * is saved until the code is approved.
     */
    suspend fun startQuickConnect(new: NewSource): QuickConnect {
        if (kindOf(new.kind) != MediaServerKinds.Jellyfin) {
            return QuickConnect.Finished(invalid(WriteOutcome.Problem.QuickConnectUnsupported, "Quick Connect is only for Jellyfin servers. Log in with a username and password instead."))
        }
        val name = new.name.trim()
        if (name.isEmpty()) return QuickConnect.Finished(blankName())
        val url = serverAddress(new.url) ?: return QuickConnect.Finished(badUrl(MediaServerKinds.Jellyfin))
        synchronized(lock) { nameTaken(name, load())?.let { return QuickConnect.Finished(it) } }
        return when (val step = MediaServer(http, url, deviceId(), appVersion).startQuickConnect()) {
            is QuickConnectStep.Started -> QuickConnect.Waiting(step.code, name, url, step.secret)
            is QuickConnectStep.Failed -> QuickConnect.Failed(step.reason, quickConnectFailure(name, url, step.reason, starting = true))
            else -> QuickConnect.Failed(Reason.SourceError, quickConnectFailure(name, url, Reason.SourceError, starting = true))
        }
    }

    /**
     * Asks whether the Household has approved [waiting]'s code yet, answering [QuickConnect.Waiting] until it has.
     * Once approved, the server is added at the end of the order with the approving user's login, and answers
     * [WriteOutcome.Added].
     */
    suspend fun continueQuickConnect(waiting: QuickConnect.Waiting, from: ChangedFrom): QuickConnect {
        val (name, url) = waiting.name to waiting.url
        return when (val step = MediaServer(http, url, deviceId(), appVersion).continueQuickConnect(waiting.secret)) {
            QuickConnectStep.Waiting -> waiting
            QuickConnectStep.Expired -> QuickConnect.Failed(Reason.AuthRequired, "The Quick Connect code for $name has expired. Start again for a new code.")
            is QuickConnectStep.Failed -> QuickConnect.Failed(step.reason, quickConnectFailure(name, url, step.reason, starting = false))
            is QuickConnectStep.Started -> QuickConnect.Failed(Reason.SourceError, quickConnectFailure(name, url, Reason.SourceError, starting = false))
            is QuickConnectStep.Connected -> when (val saved = saveNew(name, MediaServerKinds.Jellyfin, url, step.username, from)) {
                is Saved.Refused -> QuickConnect.Finished(saved.outcome)
                is Saved.As -> {
                    synchronized(lock) {
                        val sources = load()
                        storage.write(loginKey(saved.id), json.encodeToString(MediaServerLogin.serializer(), step.login).encodeToByteArray())
                        save(sources.map { if (it.id == saved.id) it.copy(testState = TestState.Passing) else it })
                    }
                    QuickConnect.Finished(WriteOutcome.Added(saved.id))
                }
            }
        }
    }

    private fun quickConnectFailure(name: String, url: String, reason: Reason, starting: Boolean) = when (reason) {
        Reason.Unreachable -> "Can't reach $name at $url."
        Reason.AuthRequired -> if (starting) {
            "Quick Connect is turned off on $name. Turn it on in the server's dashboard, or log in with a username and password."
        } else {
            "$name turned down the Quick Connect login. Start again for a new code."
        }
        Reason.RateLimited -> "$name is refusing requests for now. Try again later."
        Reason.SourceError -> "$name answered, but doesn't offer Quick Connect as a Jellyfin server does. Check the address, or log in with a username and password."
    }

    private sealed interface Saved {
        data class As(val id: String) : Saved
        data class Refused(val outcome: WriteOutcome.Invalid) : Saved
    }

    /**
     * Saves a new Source at the end of the order, untested, and announces it. The same Source again is harmless and
     * answers its ID; another Source with the same name is refused.
     */
    private fun saveNew(name: String, kind: SourceKind, url: String?, username: String?, from: ChangedFrom): Saved {
        val saved = synchronized(lock) {
            val sources = load()
            val sameName = sources.find { it.name.equals(name, ignoreCase = true) }
            when {
                sameName == null -> {
                    val id = uniqueId(slugOf(name), sources)
                    val addedAt = clock.nowMillis().takeIf { from == ChangedFrom.Computer }
                    save(sources + StoredSource(id = id, name = name, kind = kind.id, url = url, username = username, addedFromComputerAt = addedAt))
                    Saved.As(id)
                }
                sameName.kind == kind.id && sameName.url == url && sameName.username == username -> return Saved.As(sameName.id)
                else -> return Saved.Refused(nameTaken(name, sources)!!)
            }
        }
        announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.Added, name, from))
        return saved
    }

    private fun nameTaken(name: String, sources: List<StoredSource>): WriteOutcome.Invalid? =
        sources.find { it.name.equals(name, ignoreCase = true) }?.let {
            invalid(WriteOutcome.Problem.NameTaken, "There's already a Source called ${it.name}. Choose another name.")
        }

    /**
     * Changes a Source's name, server address or login; anything left null stays as it is. The ID never changes.
     * A new username needs the password too.
     */
    suspend fun update(id: String, changes: SourceChanges, from: ChangedFrom): WriteOutcome {
        val newName = changes.name?.trim()
        if (newName != null && newName.isEmpty()) return blankName()
        val kind = synchronized(lock) { load().find { it.id == id }?.kind }?.let(::kindOf) ?: return unknownSource(id)
        val takesUrl = kind in MediaServerKinds.all || kind == StremioAddonKind
        val takesLogin = kind in MediaServerKinds.all
        if ((changes.url != null && !takesUrl) || ((changes.username != null || changes.password != null) && !takesLogin)) return unneededSetting(kind)
        val newUrl = changes.url?.let { (if (kind == StremioAddonKind) StremioAddon.manifestUrlOf(it) else serverAddress(it)) ?: return badUrl(kind) }
        val newUsername = changes.username?.trim()
        if (newUsername != null && (newUsername.isEmpty() || changes.password == null)) {
            return invalid(WriteOutcome.Problem.MissingLogin, "A new username needs its password too.")
        }
        val outcome = change(id, from) { source ->
            val changed = source.copy(name = newName ?: source.name, url = newUrl ?: source.url, username = newUsername ?: source.username)
            val announcement = when {
                changed.name != source.name -> ConfigurationChange(ConfigurationChange.Action.Renamed, changed.name, from, previousName = source.name)
                changed != source || changes.password != null -> ConfigurationChange(ConfigurationChange.Action.Changed, changed.name, from)
                else -> null
            }
            Edit(changed, announcement)
        }
        return if (outcome == WriteOutcome.Ok) test(id, logInWith = changes.password) else outcome
    }

    /** Lets Lookups ask the Source again, and tests its connection. */
    suspend fun enable(id: String, from: ChangedFrom): WriteOutcome {
        val outcome = setEnabled(id, true, from)
        return if (outcome == WriteOutcome.Ok) test(id, logInWith = null) else outcome
    }

    /** Keeps the Source, but stops Lookups asking it. */
    fun disable(id: String, from: ChangedFrom): WriteOutcome = setEnabled(id, false, from)

    private fun setEnabled(id: String, enabled: Boolean, from: ChangedFrom) = change(id, from) { source ->
        val action = if (enabled) ConfigurationChange.Action.Enabled else ConfigurationChange.Action.Disabled
        Edit(source.copy(enabled = enabled), ConfigurationChange(action, source.name, from).takeIf { source.enabled != enabled })
    }

    /**
     * Puts the Sources [ids] first, in that order; the others follow in the order they were in. Lookups prefer
     * Streams from Sources earlier in the order.
     */
    fun setOrder(ids: List<String>, from: ChangedFrom): WriteOutcome {
        ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            return invalid(WriteOutcome.Problem.RepeatedSource, "The order names ${it.key} more than once.")
        }
        val announcement = synchronized(lock) {
            val sources = load()
            ids.firstOrNull { id -> sources.none { it.id == id } }?.let { return unknownSource(it) }
            val ordered = ids.map { id -> sources.first { it.id == id } } + sources.filter { it.id !in ids }
            if (ordered == sources) return WriteOutcome.Ok
            save(ordered)
            ConfigurationChange(ConfigurationChange.Action.Reordered, ordered.first().name, from)
        }
        announcements.tryEmit(announcement)
        return WriteOutcome.Ok
    }

    /** Removes the Source and its login for good. Removing one that is already gone is harmless. */
    fun remove(id: String, from: ChangedFrom): WriteOutcome = change(id, from, ifMissing = WriteOutcome.Ok) { source ->
        storage.delete(loginKey(id))
        storage.delete(manifestKey(id))
        Edit(null, ConfigurationChange(ConfigurationChange.Action.Removed, source.name, from))
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
            repositories.read(address)
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
        if (isNew) announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.RepositoryImported, fetched.name, from))
        return ImportOutcome.Imported(checkNotNull(repository(address)))
    }

    /** The Extension Repository imported from [url], with the Extensions it lists, or null when there's none. */
    fun repository(url: String): ExtensionRepository? = synchronized(lock) {
        val extensions = loadExtensions()
        extensions.repositories.find { it.url == url }?.let { repository ->
            ExtensionRepository(
                url = repository.url,
                name = repository.name,
                extensions = repository.extensions.map { listed ->
                    AvailableExtension(
                        name = listed.name,
                        displayName = listed.displayName,
                        version = listed.version,
                        description = listed.description,
                        authors = listed.authors,
                        languages = listOfNotNull(listed.language),
                        installed = extensions.installed.any { it.name == listed.name && it.repositoryUrl == url },
                    )
                },
            )
        }
    }

    /**
     * Installs the Extension [name] from the imported Extension Repository [repositoryUrl]. Its download must match the
     * list's SHA-256, and the Extension stays pinned to that Repository: one with the same name from another is refused.
     * Each of its providers becomes a Source at the end of the order, enabled. Installing it again is harmless.
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
        val sha256 = listed.sha256 ?: return InstallOutcome.Invalid(
            WriteOutcome.Problem.MissingHash,
            "${repository.name} gives no SHA-256 for $name, so it can't be checked and wasn't installed.",
        )
        val code = try {
            repositories.download(listed.url)
        } catch (e: RepositoryUnreadable) {
            return InstallOutcome.Failed(e.reason, e.message)
        }
        if (sha256Of(code) != sha256) {
            return InstallOutcome.Invalid(
                WriteOutcome.Problem.HashMismatch,
                "$name's download doesn't match the SHA-256 ${repository.name} lists for it, so it wasn't installed.",
            )
        }
        val providers = try {
            extensionHost.providers(name, sha256) { code }
        } catch (e: ExtensionFailure) {
            if (!e.needsNewerCelastrina) return InstallOutcome.Failed(e.reason, "${listed.displayName} didn't load.")
            null
        }
        val installed = InstalledExtension(name, listed.displayName, repositoryUrl, listed.version, sha256, needsNewerCelastrina = providers == null)
        val added = synchronized(lock) {
            storage.write(codeKey(sha256), code)
            val extensions = loadExtensions()
            saveExtensions(extensions.copy(installed = extensions.installed.filter { it.name != name } + installed))
            var sources = load()
            providers.orEmpty().map { provider ->
                val source = StoredSource(
                    id = uniqueId(slugOf("$name ${provider.name}"), sources),
                    name = uniqueName(provider.name, sources),
                    kind = EXTENSION_KIND,
                    extension = name,
                    provider = provider.name,
                    titleKinds = ExtensionSource.titleKindsOf(provider),
                    imdb = SyncIdName.Imdb in provider.supportedSyncNames,
                    addedFromComputerAt = clock.nowMillis().takeIf { from == ChangedFrom.Computer },
                )
                sources = sources + source
                source
            }.also { save(sources) }
        }
        added.forEach { announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.Added, it.name, from)) }
        if (providers == null) return InstallOutcome.Failed(Reason.SourceError, "${listed.displayName}: $NEEDS_NEWER_CELASTRINA.")
        return InstallOutcome.Installed(added.map { it.id })
    }

    private fun extensionSource(stored: StoredSource): Source? {
        val installed = loadExtensions().installed.find { it.name == stored.extension }
        if (installed == null || installed.needsNewerCelastrina) return null
        return ExtensionSource(
            id = stored.id,
            name = stored.name,
            kind = ExtensionSource.kind(stored.titleKinds, stored.imdb),
            extension = installed.name,
            providerName = checkNotNull(stored.provider) { "Source ${stored.id} names no provider" },
            host = extensionHost,
            providers = {
                extensionHost.providers(installed.name, installed.sha256) {
                    checkNotNull(storage.read(codeKey(installed.sha256))) { "Extension ${installed.name}'s code is missing" }
                }
            },
            needsNewerCelastrina = { markNeedsNewerCelastrina(installed.name) },
        )
    }

    /** Stops Lookups asking the Extension [name]'s Sources: it links against code this Celastrina lacks. */
    private fun markNeedsNewerCelastrina(name: String) = synchronized(lock) {
        val extensions = loadExtensions()
        saveExtensions(extensions.copy(installed = extensions.installed.map { if (it.name == name) it.copy(needsNewerCelastrina = true) else it }))
    }

    private fun loadExtensions(): StoredExtensions =
        storage.read(EXTENSIONS)?.let { json.decodeFromString<StoredExtensions>(it.decodeToString()) } ?: StoredExtensions()

    private fun saveExtensions(extensions: StoredExtensions) {
        storage.write(EXTENSIONS, json.encodeToString(StoredExtensions.serializer(), extensions).encodeToByteArray())
    }

    /** The first of [name], `name 2`, `name 3`… that no Source is called. */
    private fun uniqueName(name: String, sources: List<StoredSource>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name $it" }.first { n -> sources.none { it.name.equals(n, ignoreCase = true) } }

    /**
     * Tests the Source [id]'s connection and saves how it went: a Jellyfin or Emby server logs in again with
     * [logInWith] when given, and otherwise checks the saved login. The Internet Archive has nothing to test.
     */
    private suspend fun test(id: String, logInWith: String?): WriteOutcome {
        val source = synchronized(lock) { load().find { it.id == id } } ?: return WriteOutcome.Ok
        if (kindOf(source.kind) == StremioAddonKind) return testAddon(source)
        if (kindOf(source.kind) !in MediaServerKinds.all) return WriteOutcome.Ok
        val server = server(source)
        val saved = login(id)
        val result = when {
            logInWith != null -> server.logIn(source.username.orEmpty(), logInWith)
            saved != null -> server.check(saved)
            else -> ConnectionResult.Failed(Reason.AuthRequired)
        }
        synchronized(lock) {
            val sources = load()
            if (sources.none { it.id == id }) return WriteOutcome.Ok
            val passed = result is ConnectionResult.Connected
            if (result is ConnectionResult.Connected) storage.write(loginKey(id), json.encodeToString(MediaServerLogin.serializer(), result.login).encodeToByteArray())
            save(sources.map { if (it.id == id) it.copy(testState = if (passed) TestState.Passing else TestState.Failing) else it })
        }
        return when (result) {
            is ConnectionResult.Connected -> WriteOutcome.Ok
            is ConnectionResult.Failed -> WriteOutcome.Failed(id, result.reason, failureMessage(source, result.reason, loggedIn = logInWith != null, hadLogin = saved != null))
        }
    }

    /** Fetches the add-on's manifest, and keeps it for Lookups when it offers Streams. */
    private suspend fun testAddon(source: StoredSource): WriteOutcome {
        val check = addon(source).check()
        synchronized(lock) {
            val sources = load()
            if (sources.none { it.id == source.id }) return WriteOutcome.Ok
            if (check is ManifestCheck.Fetched) storage.write(manifestKey(source.id), check.manifest.encodeToByteArray()) else storage.delete(manifestKey(source.id))
            val state = if (check is ManifestCheck.Fetched) TestState.Passing else TestState.Failing
            save(sources.map { if (it.id == source.id) it.copy(testState = state) else it })
        }
        // An add-on's URL may hold its settings, even a key, so the messages never repeat it.
        fun failed(reason: Reason, message: String) = WriteOutcome.Failed(source.id, reason, message)
        return when (check) {
            is ManifestCheck.Fetched -> WriteOutcome.Ok
            ManifestCheck.NoStreams -> failed(Reason.SourceError, "${source.name} offers no Streams. Celastrina uses only an add-on's Streams.")
            ManifestCheck.NotAManifest -> failed(Reason.SourceError, "${source.name} didn't answer with an add-on manifest. Check the URL.")
            is ManifestCheck.Failed -> failed(
                check.reason,
                when (check.reason) {
                    Reason.Unreachable -> "Can't reach the add-on ${source.name}."
                    Reason.AuthRequired -> "${source.name} turned the URL down. Its settings may need making again."
                    Reason.RateLimited -> "${source.name} is refusing requests for now. Try again later."
                    Reason.SourceError -> "${source.name} didn't answer with an add-on manifest. Check the URL."
                },
            )
        }
    }

    private fun addon(source: StoredSource) = StremioAddon(http, checkNotNull(source.url) { "Source ${source.id} has no URL" })

    private fun failureMessage(source: StoredSource, reason: Reason, loggedIn: Boolean, hadLogin: Boolean) = when (reason) {
        Reason.Unreachable -> "Can't reach ${source.name} at ${source.url}."
        Reason.AuthRequired -> when {
            loggedIn -> "${source.name} turned down the username or password."
            hadLogin -> "${source.name} turned down the saved login. Log in again."
            else -> "${source.name} has no login yet. Log in again with its password."
        }
        Reason.RateLimited -> "${source.name} is refusing requests for now. Try again later."
        Reason.SourceError -> "${source.name} answered, but not as a Jellyfin or Emby server does. Check the address."
    }

    private fun server(source: StoredSource) = MediaServer(http, checkNotNull(source.url) { "Source ${source.id} has no URL" }, deviceId(), appVersion)

    private fun login(id: String): MediaServerLogin? =
        storage.read(loginKey(id))?.let { json.decodeFromString(MediaServerLogin.serializer(), it.decodeToString()) }

    /** Names this TV to Jellyfin and Emby servers, which keep one session per device: made once, then kept. */
    private fun deviceId(): String = synchronized(lock) {
        storage.read(DEVICE_ID)?.decodeToString() ?: UUID.randomUUID().toString().also { storage.write(DEVICE_ID, it.encodeToByteArray()) }
    }

    /** What an edit of one Source does: the changed Source (null to remove it) and its announcement, if anything changed. */
    private class Edit(val changed: StoredSource?, val announcement: ConfigurationChange?)

    /**
     * Applies [edit] to the Source [id] and saves it, marking it changed from a computer when it was. A missing Source
     * answers [ifMissing].
     */
    private fun change(id: String, from: ChangedFrom, ifMissing: WriteOutcome = unknownSource(id), edit: (StoredSource) -> Edit): WriteOutcome {
        val announcement = synchronized(lock) {
            val sources = load()
            val source = sources.find { it.id == id } ?: return ifMissing
            val result = edit(source)
            val changed = if (result.announcement != null && from == ChangedFrom.Computer) {
                result.changed?.copy(changedFromComputerAt = clock.nowMillis())
            } else {
                result.changed
            }
            if (changed != source) save(sources.mapNotNull { if (it.id == id) changed else it })
            result.announcement
        }
        announcement?.let(announcements::tryEmit)
        return WriteOutcome.Ok
    }

    /** The first of [slug], `slug-2`, `slug-3`… that no Source has. */
    private fun uniqueId(slug: String, sources: List<StoredSource>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) slug else "$slug-$it" }.first { id -> sources.none { it.id == id } }

    private fun save(sources: List<StoredSource>) {
        storage.write(SOURCES, json.encodeToString(StoredSources.serializer(), StoredSources(sources)).encodeToByteArray())
    }

    private fun load(): List<StoredSource> =
        storage.read(SOURCES)?.let { json.decodeFromString<StoredSources>(it.decodeToString()).sources } ?: preinstalled()

    private fun preinstalled() = listOf(
        StoredSource(id = slugOf(INTERNET_ARCHIVE), name = INTERNET_ARCHIVE, kind = InternetArchiveSource.Kind.id),
    )

    @Serializable
    private data class StoredSources(val sources: List<StoredSource>)

    @Serializable
    private data class StoredSource(
        val id: String,
        val name: String,
        val kind: String,
        val enabled: Boolean = true,
        val testState: TestState = TestState.Untested,
        /** A Jellyfin or Emby server's address, without a trailing '/', or an add-on's manifest URL. */
        val url: String? = null,
        val username: String? = null,
        /** For a Source from an Extension: the Extension's name, its provider's name, and what the provider plays. */
        val extension: String? = null,
        val provider: String? = null,
        val titleKinds: Set<Title.Kind> = emptySet(),
        val imdb: Boolean = false,
        val addedFromComputerAt: Long? = null,
        val changedFromComputerAt: Long? = null,
    )

    @Serializable
    private data class StoredExtensions(
        val repositories: List<StoredRepository> = emptyList(),
        val installed: List<InstalledExtension> = emptyList(),
    )

    @Serializable
    private data class StoredRepository(val url: String, val name: String, val extensions: List<ListedExtension>)

    /** An installed Extension, pinned to the Extension Repository it came from. Its code is kept under its SHA-256. */
    @Serializable
    private data class InstalledExtension(
        val name: String,
        val displayName: String,
        val repositoryUrl: String,
        val version: Int,
        val sha256: String,
        val needsNewerCelastrina: Boolean = false,
    )

    companion object {
        /** Goes up only on changes that break older callers of the agent's ContentProvider. */
        const val PROTOCOL_VERSION = 1

        private const val SOURCES = "sources.json"
        private const val TMDB_KEY = "tmdb-key"
        private const val DEVICE_ID = "device-id"
        private const val INTERNET_ARCHIVE = "Internet Archive"
        private const val EXTENSIONS = "extensions.json"
        private const val WARNING_ACKNOWLEDGED = "extension-warning-acknowledged"
        private const val EXTENSION_KIND = "extension"

        /** What an Extension that links against code this Celastrina lacks says, wherever it is listed. */
        const val NEEDS_NEWER_CELASTRINA = "Needs a newer Celastrina"

        private fun codeKey(sha256: String) = "extension-$sha256.cs3"

        private fun sha256Of(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** [text] as an http(s) URL with a host, or null when it isn't one. */
        private fun webAddress(text: String): String? {
            val trimmed = text.trim()
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
            return trimmed.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() }
        }

        private val KINDS = listOf(InternetArchiveSource.Kind) + MediaServerKinds.all + StremioAddonKind
        private val json = Json { ignoreUnknownKeys = true }

        private fun kindOf(id: String): SourceKind? = KINDS.find { it.id == id }

        private fun loginKey(id: String) = "login-$id.json"

        private fun manifestKey(id: String) = "manifest-$id.json"

        /** [text] as a server address, `http(s)://host[:port][/path]` without a trailing '/', or null when it isn't one. */
        private fun serverAddress(text: String?): String? {
            val trimmed = text?.trim()?.trimEnd('/') ?: return null
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
            return trimmed.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawQuery == null }
        }

        private fun invalid(problem: WriteOutcome.Problem, message: String) = WriteOutcome.Invalid(problem, message)
        private fun blankName() = invalid(WriteOutcome.Problem.BlankName, "A Source needs a name.")
        private fun unknownSource(id: String) = invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID $id.")
        private fun unneededSetting(kind: SourceKind) = invalid(
            WriteOutcome.Problem.UnneededSetting,
            if (kind == StremioAddonKind) "A Stremio-compatible add-on needs only its manifest URL, no login." else "The ${kind.name} needs no URL or login.",
        )
        private fun badUrl(kind: SourceKind) = invalid(
            WriteOutcome.Problem.BadUrl,
            if (kind == StremioAddonKind) {
                "A Stremio-compatible add-on Source needs its manifest URL, starting http:// or https:// and ending /manifest.json."
            } else {
                "A Jellyfin or Emby Source needs the server's address, starting http:// or https://, such as http://192.168.1.20:8096."
            },
        )
    }
}

data class Status(val appVersion: String, val tmdbKey: TmdbKeyInUse, val sources: Int, val extensions: Int, val protocolVersion: Int)

/** Which TMDB key the Catalogue uses. Says nothing of the key itself. */
enum class TmdbKeyInUse {
    /** The key built into the app. */
    Project,

    /** The Household's own key. */
    Override,

    /** No key at all: a build without a project key, and the Household hasn't set one. */
    None,
}

/**
 * A Source to add. [kind] is a Source Kind's ID; Jellyfin and Emby need [url], [username] and [password], and a
 * Stremio-compatible add-on needs its manifest URL as [url].
 */
data class NewSource(
    val kind: String,
    val name: String,
    val url: String? = null,
    val username: String? = null,
    /** Used to log in, then forgotten. */
    val password: String? = null,
)

/** Changes to a Source; null leaves that setting as it is. A [password] logs in again. */
data class SourceChanges(
    val name: String? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
)

/** What the Household and the agent see of a Source. */
data class SourceSummary(
    /** Made from the name when the Source is added; unchanged by renames. */
    val id: String,
    val name: String,
    /** The Source Kind's ID, such as `internet-archive`. */
    val kind: String,
    val enabled: Boolean,
    /** 1 for the Source asked about first. */
    val position: Int,
    val testState: TestState,
    val hasLogin: Boolean,
    /** When the agent added it from a computer, in epoch milliseconds; null when it was added on the TV or preinstalled. */
    val addedFromComputerAt: Long?,
    /** When the agent last changed it from a computer, in epoch milliseconds; null when it never has. */
    val changedFromComputerAt: Long?,
    /** Why Lookups don't ask this Source though it is enabled, such as "Needs a newer Celastrina"; null when they do. */
    val problem: String? = null,
)

/** How the Source's latest connection test went. */
enum class TestState { Untested, Passing, Failing }

/** What a write answers: saved, refused with a [Problem], or saved but failing its connection test. */
sealed interface WriteOutcome {
    data object Ok : WriteOutcome

    /** A Source was added (or was already there), passing its connection test. */
    data class Added(val sourceId: String) : WriteOutcome

    data class Invalid(val problem: Problem, val message: String) : WriteOutcome

    /** The write was saved, but the Source [sourceId] failed its connection test for [reason]. */
    data class Failed(val sourceId: String, val reason: Reason, val message: String) : WriteOutcome

    enum class Problem {
        UnknownSource, BlankName, UnknownKind, BadUrl, MissingLogin, NameTaken, RepeatedSource, UnneededSetting, BlankTmdbKey, QuickConnectUnsupported,
        UnknownRepository, UnknownExtension, PinnedToOtherRepository, MissingHash, HashMismatch,
    }
}

/** How adding a Jellyfin server with Quick Connect is going. */
sealed interface QuickConnect {
    /** Show [code] until the Household approves it on the server, asking again every few seconds. */
    class Waiting internal constructor(
        val code: String,
        internal val name: String,
        internal val url: String,
        internal val secret: String,
    ) : QuickConnect

    /** Done: the server was [added][WriteOutcome.Added], or the add was [refused][WriteOutcome.Invalid]. */
    data class Finished(val outcome: WriteOutcome) : QuickConnect

    /** Couldn't log in, for [reason]; nothing was saved. */
    data class Failed(val reason: Reason, val message: String) : QuickConnect
}

/** Where a change was made: on the TV, or by the agent over ADB. */
enum class ChangedFrom { Tv, Computer }

/** A saved change to the configuration, as the on-screen notice tells it. */
data class ConfigurationChange(
    val action: Action,
    /**
     * The Source's name after the change; for a new order, the Source now first; for an imported Extension Repository,
     * its name; null for any other change that isn't to a Source.
     */
    val sourceName: String?,
    val from: ChangedFrom,
    /** For a rename, the name it had before. */
    val previousName: String? = null,
) {
    enum class Action { Added, Renamed, Changed, Enabled, Disabled, Removed, Reordered, TmdbKeySet, TmdbKeyCleared, RepositoryImported }
}

/** What importing an Extension Repository answers. */
sealed interface ImportOutcome {
    /** Imported: the Repository and the Extensions it lists, ready to choose from. */
    data class Imported(val repository: ExtensionRepository) : ImportOutcome

    /** Refused until the Household has seen [warning] and agreed: import again acknowledging [warningVersion]. */
    data class WarningNotAcknowledged(val warning: String, val warningVersion: Int) : ImportOutcome

    data class Invalid(val problem: WriteOutcome.Problem, val message: String) : ImportOutcome

    /** The Repository couldn't be read, for [reason]. */
    data class Failed(val reason: Reason, val message: String) : ImportOutcome
}

/** What installing an Extension answers. */
sealed interface InstallOutcome {
    /** Installed (or already was): the IDs of the Sources its providers became. */
    data class Installed(val sourceIds: List<String>) : InstallOutcome

    data class Invalid(val problem: WriteOutcome.Problem, val message: String) : InstallOutcome

    /** Not usable, for [reason]: it couldn't be downloaded, didn't load, or needs a newer Celastrina. */
    data class Failed(val reason: Reason, val message: String) : InstallOutcome
}
