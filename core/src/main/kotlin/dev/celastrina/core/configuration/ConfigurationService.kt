package dev.celastrina.core.configuration

import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.slugOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one place that reads, validates and saves the Household's configuration. The on-TV screens and the
 * agent's ContentProvider both go through it, so they get the same rules and messages.
 */
class ConfigurationService(
    private val storage: Storage,
    private val http: HttpClient,
    private val appVersion: String,
    /** The TMDB key built into the app; blank in a build made without one. */
    private val projectTmdbKey: String,
) {
    private val lock = Any()
    private val announcements = MutableSharedFlow<ConfigurationChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each change as it is saved, for the on-screen notices. A command that changes nothing announces nothing. */
    val changes: SharedFlow<ConfigurationChange> = announcements.asSharedFlow()

    fun status(): Status = synchronized(lock) {
        Status(appVersion = appVersion, tmdbKey = tmdbKeyInUse(), sources = load().size, extensions = 0, protocolVersion = PROTOCOL_VERSION)
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
        load().mapIndexed { index, it ->
            SourceSummary(
                id = it.id,
                name = it.name,
                kind = it.kind,
                enabled = it.enabled,
                position = index + 1,
                testState = it.testState,
                hasLogin = false,
            )
        }
    }

    /** The enabled Sources, in the order a Lookup asks them. */
    fun sources(): List<Source> = synchronized(lock) {
        load().filter { it.enabled }.map { stored ->
            when (stored.kind) {
                InternetArchiveSource.Kind.id -> InternetArchiveSource(http, id = stored.id, name = stored.name)
                else -> error("Unknown Source Kind ${stored.kind} for Source ${stored.id}")
            }
        }
    }

    /** Gives a Source a new display name. Its ID stays the same. */
    fun rename(id: String, name: String, from: ChangedFrom): WriteOutcome {
        val newName = name.trim()
        if (newName.isEmpty()) return WriteOutcome.Invalid(WriteOutcome.Problem.BlankName, "A Source needs a name.")
        return change(id) { source ->
            if (source.name == newName) null
            else source.copy(name = newName) to ConfigurationChange(ConfigurationChange.Action.Renamed, newName, from, previousName = source.name)
        }
    }

    /** Lets Lookups ask the Source again. */
    fun enable(id: String, from: ChangedFrom): WriteOutcome = setEnabled(id, true, from)

    /** Keeps the Source, but stops Lookups asking it. */
    fun disable(id: String, from: ChangedFrom): WriteOutcome = setEnabled(id, false, from)

    private fun setEnabled(id: String, enabled: Boolean, from: ChangedFrom) = change(id) { source ->
        if (source.enabled == enabled) null
        else {
            val action = if (enabled) ConfigurationChange.Action.Enabled else ConfigurationChange.Action.Disabled
            source.copy(enabled = enabled) to ConfigurationChange(action, source.name, from)
        }
    }

    /** Removes the Source for good. Removing one that is already gone is harmless. */
    fun remove(id: String, from: ChangedFrom): WriteOutcome = change(id, ifMissing = WriteOutcome.Ok) { source ->
        null to ConfigurationChange(ConfigurationChange.Action.Removed, source.name, from)
    }

    /**
     * Applies [edit] to the Source [id] and saves it. [edit] answers the changed Source (null to remove it) and its
     * announcement, or null when there is nothing to change. A missing Source answers [ifMissing].
     */
    private fun change(
        id: String,
        ifMissing: WriteOutcome = WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID $id."),
        edit: (StoredSource) -> Pair<StoredSource?, ConfigurationChange>?,
    ): WriteOutcome {
        val announcement = synchronized(lock) {
            val sources = load()
            val source = sources.find { it.id == id } ?: return ifMissing
            val (changed, announcement) = edit(source) ?: return WriteOutcome.Ok
            save(sources.mapNotNull { if (it.id == id) changed else it })
            announcement
        }
        announcements.tryEmit(announcement)
        return WriteOutcome.Ok
    }

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
    )

    companion object {
        /** Goes up only on changes that break older callers of the agent's ContentProvider. */
        const val PROTOCOL_VERSION = 1

        private const val SOURCES = "sources.json"
        private const val TMDB_KEY = "tmdb-key"
        private const val INTERNET_ARCHIVE = "Internet Archive"
        private val json = Json { ignoreUnknownKeys = true }
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
)

/** How the Source's latest connection test went. */
enum class TestState { Untested, Passing, Failing }

/** What a write answers: saved, or refused with a [Problem] and a message the Household or agent can read. */
sealed interface WriteOutcome {
    data object Ok : WriteOutcome

    data class Invalid(val problem: Problem, val message: String) : WriteOutcome

    enum class Problem { UnknownSource, BlankName, BlankTmdbKey }
}

/** Where a change was made: on the TV, or by the agent over ADB. */
enum class ChangedFrom { Tv, Computer }

/** A saved change to the configuration, as the on-screen notice tells it. */
data class ConfigurationChange(
    val action: Action,
    /** The Source's name after the change; null for a change that isn't to a Source. */
    val sourceName: String?,
    val from: ChangedFrom,
    /** For a rename, the name it had before. */
    val previousName: String? = null,
) {
    enum class Action { Renamed, Enabled, Disabled, Removed, TmdbKeySet, TmdbKeyCleared }
}
