package dev.celastrina.core.configuration

import dev.celastrina.core.catalogue.NotPlayableMemory
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.extension.ExtensionRepository
import dev.celastrina.core.extension.ExtensionWarning
import dev.celastrina.core.port.ExtensionLoader
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.Logger
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
/**
 * The one place that reads, validates and saves the Household's configuration. The on-TV screens and the
 * agent's ContentProvider both go through it, so they get the same rules and messages. It keeps the TMDB key itself, and
 * hands the Sources to [SourceSettings], the Extensions to [ExtensionSettings], and connection tests to [ConnectionTests].
 *
 * Every write that adds, changes or enables a Source tests its connection straight after saving. A Source that fails
 * its test stays saved, marked failing, and the write answers [WriteOutcome.Failed] with the reason.
 */
class ConfigurationService(
    storage: Storage,
    http: HttpClient,
    private val appVersion: String,
    /** The TMDB key built into the app; blank in a build made without one. */
    private val projectTmdbKey: String,
    extensionLoader: ExtensionLoader = ExtensionLoader.None,
    logger: Logger = Logger.StandardOut,
    /** Dates the marks on Sources added or changed from a computer. */
    clock: Clock = Clock.System,
    /** Forgets the Streams of Sources that are removed. */
    failoverMemory: FailoverMemory? = null,
    /** Forgets every Not playable Title whenever a Source is added, changed or removed. */
    private val notPlayable: NotPlayableMemory? = null,
) {
    private val store = ConfigurationStore(storage)
    private val announcements = MutableSharedFlow<ConfigurationChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val extensions = ExtensionSettings(store, http, appVersion, clock, logger, failoverMemory, extensionLoader, ::announce)
    private val connection = ConnectionTests(store, http, appVersion)
    private val sourceSettings = SourceSettings(store, http, appVersion, clock, failoverMemory, connection, extensions, ::announce)

    /** Each change as it is saved, for the on-screen notices. A command that changes nothing announces nothing. */
    val changes: SharedFlow<ConfigurationChange> = announcements.asSharedFlow()

    /**
     * Announces [change] once it is saved. A change to what the Sources are, or what they can find, also forgets every
     * Not playable Title, so each Source gets a fair chance; a new order or TMDB key changes neither.
     */
    private fun announce(change: ConfigurationChange) {
        if (change.action !in LEAVES_NOT_PLAYABLE) notPlayable?.forgetAll()
        announcements.tryEmit(change)
    }

    fun status(): Status = synchronized(store.lock) {
        Status(
            appVersion = appVersion,
            tmdbKey = tmdbKeyInUse(),
            sources = store.load().size,
            extensions = store.loadExtensions().installed.size,
            protocolVersion = PROTOCOL_VERSION,
        )
    }

    /** How the Catalogue reaches TMDB: with the Household's own key when it has set one, else the project key. */
    fun tmdbSettings(): TmdbSettings = synchronized(store.lock) { TmdbSettings(apiKey = tmdbKeyOverride() ?: projectTmdbKey) }

    /** Uses the Household's own TMDB key in place of the project key, from now on. */
    fun setTmdbKey(key: String, from: ChangedFrom): WriteOutcome {
        val newKey = key.trim()
        if (newKey.isEmpty()) {
            return WriteOutcome.Invalid(
                WriteOutcome.Problem.BlankTmdbKey,
                "A TMDB key can't be blank. To go back to the project key, clear the key instead.",
            )
        }
        synchronized(store.lock) {
            if (tmdbKeyOverride() == newKey) return WriteOutcome.Ok
            store.storage.write(TMDB_KEY, newKey.encodeToByteArray())
        }
        announce(ConfigurationChange(ConfigurationChange.Action.TmdbKeySet, sourceName = null, from))
        return WriteOutcome.Ok
    }

    /** Goes back to the project key. Clearing when the Household has no key of its own is harmless. */
    fun clearTmdbKey(from: ChangedFrom): WriteOutcome {
        synchronized(store.lock) {
            if (tmdbKeyOverride() == null) return WriteOutcome.Ok
            store.storage.delete(TMDB_KEY)
        }
        announce(ConfigurationChange(ConfigurationChange.Action.TmdbKeyCleared, sourceName = null, from))
        return WriteOutcome.Ok
    }

    private fun tmdbKeyOverride(): String? = store.storage.read(TMDB_KEY)?.decodeToString()

    private fun tmdbKeyInUse() = when {
        tmdbKeyOverride() != null -> TmdbKeyInUse.Override
        projectTmdbKey.isNotBlank() -> TmdbKeyInUse.Project
        else -> TmdbKeyInUse.None
    }

    /** Every Source in the Household's order. Never includes a secret, only whether there is a login. */
    fun list(): List<SourceSummary> = sourceSettings.list()

    /** The enabled Sources, in the order a Lookup asks them. */
    fun sources(): List<Source> = sourceSettings.sources()

    /**
     * Adds a Source at the end of the order and answers its ID in [WriteOutcome.Added]. A Jellyfin or Emby server is
     * logged in to straight away, and only the access token is kept. Adding the same Source again is harmless.
     */
    suspend fun add(new: NewSource, from: ChangedFrom): WriteOutcome = sourceSettings.add(new, from)

    /**
     * Starts adding a Jellyfin server with Quick Connect: the answer's code is for the Household to approve in the
     * server's Quick Connect page, signed in on another device. [new] needs the kind, name and server address. Nothing
     * is saved until the code is approved.
     */
    suspend fun startQuickConnect(new: NewSource): QuickConnect = sourceSettings.startQuickConnect(new)

    /**
     * Asks whether the Household has approved [waiting]'s code yet, answering [QuickConnect.Waiting] until it has.
     * Once approved, the server is added at the end of the order with the approving user's login, and answers
     * [WriteOutcome.Added].
     */
    suspend fun continueQuickConnect(waiting: QuickConnect.Waiting, from: ChangedFrom): QuickConnect = sourceSettings.continueQuickConnect(waiting, from)

    /**
     * Changes a Source's name, server address or login; anything left null stays as it is. The ID never changes.
     * A new username needs the password too.
     */
    suspend fun update(id: SourceId, changes: SourceChanges, from: ChangedFrom): WriteOutcome = sourceSettings.update(id, changes, from)

    /** Lets Lookups ask the Source again, and tests its connection. */
    suspend fun enable(id: SourceId, from: ChangedFrom): WriteOutcome = sourceSettings.enable(id, from)

    /** Keeps the Source, but stops Lookups asking it. */
    fun disable(id: SourceId, from: ChangedFrom): WriteOutcome = sourceSettings.disable(id, from)

    /**
     * Puts the Sources [ids] first, in that order; the others follow in the order they were in. Lookups prefer
     * Streams from Sources earlier in the order.
     */
    fun setOrder(ids: List<SourceId>, from: ChangedFrom): WriteOutcome = sourceSettings.setOrder(ids, from)

    /** Removes the Source, its login and its Failover memory for good. Removing one that is already gone is harmless. */
    fun remove(id: SourceId, from: ChangedFrom): WriteOutcome = sourceSettings.remove(id, from)

    /**
     * Imports the Extension Repository at [url] and answers the Extensions it lists. The Household must first have seen
     * [ExtensionWarning]: without [warningAcknowledged] set to its current version this refuses, answering the warning
     * to show. Importing again refreshes the list.
     */
    suspend fun importRepository(url: String, warningAcknowledged: Int?, from: ChangedFrom): ImportOutcome =
        extensions.importRepository(url, warningAcknowledged, from)

    /** The Extension Repository imported from [url], with the Extensions it lists, or null when there's none. */
    fun repository(url: String): ExtensionRepository? = extensions.repository(url)

    /** Every imported Extension Repository, oldest first, with the Extensions it lists and which are installed. */
    fun repositories(): List<ExtensionRepository> = extensions.repositories()

    /**
     * Reads every imported Extension Repository again for newer versions of the installed Extensions, and answers how
     * many have one. It reads them at most once a day, unless asked to check [now]; a Repository that can't be read
     * keeps what it listed before. Nothing is updated: the Household updates by hand.
     */
    suspend fun checkForUpdates(now: Boolean = false): Int = extensions.checkForUpdates(now)

    /** How many installed Extensions have a newer version listed, as of the last update check. */
    fun updatesAvailable(): Int = extensions.updatesAvailable()

    /**
     * Updates the installed Extension [name] to the version its Extension Repository lists, checked against the list's
     * SHA-256 as an install is. Its Sources stay where they are in the order. Updating one with no update is harmless.
     */
    suspend fun updateExtension(name: String, from: ChangedFrom): InstallOutcome = extensions.updateExtension(name, from)

    /**
     * Tries again each installed Extension that needed a newer Celastrina than an earlier one: after an app update, one
     * that loads now gets its Sources back, or its first ones if it never loaded. Call it when Celastrina starts.
     */
    suspend fun recheckExtensions() = extensions.recheckExtensions()

    /** Updates every installed Extension that has an update, answering how each went by its name. */
    suspend fun updateAllExtensions(from: ChangedFrom): Map<String, InstallOutcome> = extensions.updateAllExtensions(from)

    /**
     * Removes the installed Extension [name]: its Sources leave the order at once, with their Failover memory, and its
     * code is gone by the next start. Removing one that isn't installed is harmless.
     */
    fun removeExtension(name: String, from: ChangedFrom): WriteOutcome = extensions.removeExtension(name, from)

    /** Removes the Extension Repository imported from [url] and every Extension installed from it. Harmless if it's gone. */
    fun removeRepository(url: String, from: ChangedFrom): WriteOutcome = extensions.removeRepository(url, from)

    /**
     * Installs the Extension [name] from the imported Extension Repository [repositoryUrl]. Its download must match the
     * list's SHA-256, and the Extension stays pinned to that Repository: one with the same name from another is refused.
     * Each Source it provides joins the end of the order, enabled. Installing it again is harmless.
     */
    suspend fun installExtension(repositoryUrl: String, name: String, from: ChangedFrom): InstallOutcome =
        extensions.installExtension(repositoryUrl, name, from)

    companion object {
        /** Goes up only on changes that break older callers of the agent's ContentProvider. */
        const val PROTOCOL_VERSION = 1

        /** What an Extension that links against code this Celastrina lacks says, wherever it is listed. */
        const val NEEDS_NEWER_CELASTRINA = "Needs a newer Celastrina"

        private const val TMDB_KEY = "tmdb-key"
        private val LEAVES_NOT_PLAYABLE = setOf(
            ConfigurationChange.Action.Reordered,
            ConfigurationChange.Action.TmdbKeySet,
            ConfigurationChange.Action.TmdbKeyCleared,
            ConfigurationChange.Action.RepositoryImported,
        )
    }
}
