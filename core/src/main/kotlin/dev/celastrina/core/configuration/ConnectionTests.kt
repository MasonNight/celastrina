package dev.celastrina.core.configuration

import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.jellyfin.ConnectionResult
import dev.celastrina.core.source.jellyfin.MediaServer
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.jellyfin.MediaServerLogin
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.stremio.ManifestCheck
import dev.celastrina.core.source.stremio.StremioAddon
import dev.celastrina.core.source.stremio.StremioAddonKind
import java.util.UUID

/**
 * Tests Sources' connections and keeps what a test brings back: a Jellyfin or Emby server's login, or an add-on's
 * manifest. The Internet Archive and Extensions have nothing to test.
 */
internal class ConnectionTests(store: ConfigurationStore, private val http: HttpClient, private val appVersion: String) : StoreAccess(store) {

    fun quickConnectFailure(name: String, url: String, reason: Reason, starting: Boolean) = when (reason) {
        Reason.Unreachable -> "Can't reach $name at $url."
        Reason.AuthRequired -> if (starting) {
            "Quick Connect is turned off on $name. Turn it on in the server's dashboard, or log in with a username and password."
        } else {
            "$name turned down the Quick Connect login. Start again for a new code."
        }
        Reason.RateLimited -> "$name is refusing requests for now. Try again later."
        Reason.SourceError -> "$name answered, but doesn't offer Quick Connect as a Jellyfin server does. Check the address, or log in with a username and password."
    }

    /**
     * Tests the Source [id]'s connection and saves how it went: a Jellyfin or Emby server logs in again with
     * [logInWith] when given, and otherwise checks the saved login. The Internet Archive has nothing to test.
     */
    suspend fun test(id: SourceId, logInWith: String?): WriteOutcome {
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
    suspend fun testAddon(source: StoredSource): WriteOutcome {
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

    fun addon(source: StoredSource) = StremioAddon(http, checkNotNull(source.url) { "Source ${source.id} has no URL" })

    fun failureMessage(source: StoredSource, reason: Reason, loggedIn: Boolean, hadLogin: Boolean) = when (reason) {
        Reason.Unreachable -> "Can't reach ${source.name} at ${source.url}."
        Reason.AuthRequired -> when {
            loggedIn -> "${source.name} turned down the username or password."
            hadLogin -> "${source.name} turned down the saved login. Log in again."
            else -> "${source.name} has no login yet. Log in again with its password."
        }
        Reason.RateLimited -> "${source.name} is refusing requests for now. Try again later."
        Reason.SourceError -> "${source.name} answered, but not as a Jellyfin or Emby server does. Check the address."
    }

    fun server(source: StoredSource) = MediaServer(http, checkNotNull(source.url) { "Source ${source.id} has no URL" }, deviceId(source.id), appVersion)

    /**
     * Names this TV to the Source [id]'s Jellyfin or Emby server. A server keeps one session per device, so each Source
     * is its own device: a second Source on the same server, logging in, doesn't sign the first out. Made once, then
     * kept. The one device ID an earlier Celastrina used for every Source goes to the first that asks with a login.
     */
    fun deviceId(id: SourceId): String = synchronized(lock) {
        storage.read(deviceIdKey(id))?.decodeToString()?.let { return it }
        val legacy = storage.read(LEGACY_DEVICE_ID)?.decodeToString()?.takeIf { login(id) != null }
        val deviceId = legacy ?: UUID.randomUUID().toString()
        storage.write(deviceIdKey(id), deviceId.encodeToByteArray())
        if (legacy != null) storage.delete(LEGACY_DEVICE_ID)
        deviceId
    }
}
