package dev.celastrina.core.configuration

import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.ConnectionResult
import dev.celastrina.core.source.jellyfin.MediaServer
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.jellyfin.MediaServerLogin
import dev.celastrina.core.source.slugOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
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
) {
    private val lock = Any()
    private val announcements = MutableSharedFlow<ConfigurationChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each change as it is saved, for the on-screen notices. A command that changes nothing announces nothing. */
    val changes: SharedFlow<ConfigurationChange> = announcements.asSharedFlow()

    fun status(): Status = synchronized(lock) {
        Status(appVersion = appVersion, sources = load().size, extensions = 0, protocolVersion = PROTOCOL_VERSION)
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
                hasLogin = login(it.id) != null,
            )
        }
    }

    /** The enabled Sources, in the order a Lookup asks them. */
    fun sources(): List<Source> = synchronized(lock) {
        load().filter { it.enabled }.map { stored ->
            when (val kind = kindOf(stored.kind)) {
                InternetArchiveSource.Kind -> InternetArchiveSource(http, id = stored.id, name = stored.name)
                in MediaServerKinds.all -> server(stored).source(stored.id, stored.name, kind!!, login(stored.id))
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
            if (new.url != null || new.username != null || new.password != null) return unneededSetting()
            null
        } else {
            val url = serverAddress(new.url) ?: return badUrl()
            if (new.username.isNullOrBlank() || new.password == null) {
                return invalid(WriteOutcome.Problem.MissingLogin, "A ${kind.name} Source needs a username and a password (which may be empty).")
            }
            url
        }
        val username = new.username?.trim()

        val (id, isNew) = synchronized(lock) {
            val sources = load()
            val sameName = sources.find { it.name.equals(name, ignoreCase = true) }
            when {
                sameName == null -> {
                    val id = uniqueId(slugOf(name), sources)
                    save(sources + StoredSource(id = id, name = name, kind = kind.id, url = url, username = username))
                    id to true
                }
                sameName.kind == kind.id && sameName.url == url && sameName.username == username -> sameName.id to false
                else -> return invalid(WriteOutcome.Problem.NameTaken, "There's already a Source called ${sameName.name}. Choose another name.")
            }
        }
        if (isNew) announcements.tryEmit(ConfigurationChange(ConfigurationChange.Action.Added, name, from))
        return when (val tested = test(id, logInWith = new.password)) {
            WriteOutcome.Ok -> WriteOutcome.Added(id)
            else -> tested
        }
    }

    /**
     * Changes a Source's name, server address or login; anything left null stays as it is. The ID never changes.
     * A new username needs the password too.
     */
    suspend fun update(id: String, changes: SourceChanges, from: ChangedFrom): WriteOutcome {
        val newName = changes.name?.trim()
        if (newName != null && newName.isEmpty()) return blankName()
        val newUrl = changes.url?.let { serverAddress(it) ?: return badUrl() }
        val newUsername = changes.username?.trim()
        if (newUsername != null && (newUsername.isEmpty() || changes.password == null)) {
            return invalid(WriteOutcome.Problem.MissingLogin, "A new username needs its password too.")
        }
        val serverSettings = changes.url != null || changes.username != null || changes.password != null
        val kind = synchronized(lock) { load().find { it.id == id }?.kind } ?: return unknownSource(id)
        if (serverSettings && kindOf(kind) !in MediaServerKinds.all) return unneededSetting()
        val outcome = change(id) { source ->
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

    private fun setEnabled(id: String, enabled: Boolean, from: ChangedFrom) = change(id) { source ->
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
    fun remove(id: String, from: ChangedFrom): WriteOutcome = change(id, ifMissing = WriteOutcome.Ok) { source ->
        storage.delete(loginKey(id))
        Edit(null, ConfigurationChange(ConfigurationChange.Action.Removed, source.name, from))
    }

    /**
     * Tests the Source [id]'s connection and saves how it went: a Jellyfin or Emby server logs in again with
     * [logInWith] when given, and otherwise checks the saved login. The Internet Archive has nothing to test.
     */
    private suspend fun test(id: String, logInWith: String?): WriteOutcome {
        val source = synchronized(lock) { load().find { it.id == id } } ?: return WriteOutcome.Ok
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

    /** Applies [edit] to the Source [id] and saves it. A missing Source answers [ifMissing]. */
    private fun change(id: String, ifMissing: WriteOutcome = unknownSource(id), edit: (StoredSource) -> Edit): WriteOutcome {
        val announcement = synchronized(lock) {
            val sources = load()
            val source = sources.find { it.id == id } ?: return ifMissing
            val result = edit(source)
            if (result.changed != source) save(sources.mapNotNull { if (it.id == id) result.changed else it })
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
        /** A Jellyfin or Emby server's address, without a trailing '/'. */
        val url: String? = null,
        val username: String? = null,
    )

    companion object {
        /** Goes up only on changes that break older callers of the agent's ContentProvider. */
        const val PROTOCOL_VERSION = 1

        private const val SOURCES = "sources.json"
        private const val DEVICE_ID = "device-id"
        private const val INTERNET_ARCHIVE = "Internet Archive"
        private val KINDS = listOf(InternetArchiveSource.Kind) + MediaServerKinds.all
        private val json = Json { ignoreUnknownKeys = true }

        private fun kindOf(id: String): SourceKind? = KINDS.find { it.id == id }

        private fun loginKey(id: String) = "login-$id.json"

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
        private fun unneededSetting() = invalid(WriteOutcome.Problem.UnneededSetting, "The Internet Archive needs no URL or login.")
        private fun badUrl() = invalid(
            WriteOutcome.Problem.BadUrl,
            "A Jellyfin or Emby Source needs the server's address, starting http:// or https://, such as http://192.168.1.20:8096.",
        )
    }
}

data class Status(val appVersion: String, val sources: Int, val extensions: Int, val protocolVersion: Int)

/** A Source to add. [kind] is a Source Kind's ID; Jellyfin and Emby need [url], [username] and [password]. */
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

    enum class Problem { UnknownSource, BlankName, UnknownKind, BadUrl, MissingLogin, NameTaken, RepeatedSource, UnneededSetting }
}

/** Where a change was made: on the TV, or by the agent over ADB. */
enum class ChangedFrom { Tv, Computer }

/** A saved change to the configuration, as the on-screen notice tells it. */
data class ConfigurationChange(
    val action: Action,
    /** The Source's name after the change; for a new order, the Source now first. */
    val sourceName: String,
    val from: ChangedFrom,
    /** For a rename, the name it had before. */
    val previousName: String? = null,
) {
    enum class Action { Added, Renamed, Changed, Enabled, Disabled, Removed, Reordered }
}
