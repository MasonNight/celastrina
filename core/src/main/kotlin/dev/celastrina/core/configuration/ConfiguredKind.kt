package dev.celastrina.core.configuration

import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.stremio.StremioAddon
import dev.celastrina.core.source.stremio.StremioAddonKind

/**
 * What configuring a Source of one Source Kind involves: which settings it takes, how its address is read, how its
 * connection is tested, and how a saved one becomes a Source. Extensions' Sources aren't configured this way: they come
 * from installing the Extension.
 */
internal sealed class ConfiguredKind(val kind: SourceKind) {
    open val takesUrl: Boolean get() = false
    open val takesLogin: Boolean get() = false

    /** [text] as this Kind keeps its address, or null when it isn't one. Only asked of a Kind that [takesUrl]. */
    open fun addressOf(text: String?): String? = null

    /** What a setting this Kind doesn't take is told. */
    abstract val unneededSetting: String

    /** What an address this Kind can't read is told. */
    open val badUrl: String get() = ""

    /** Tests the saved Source [source]'s connection, logging in with [logInWith] where the Kind logs in. */
    open suspend fun test(tests: ConnectionTests, source: StoredSource, logInWith: String?): WriteOutcome = WriteOutcome.Ok

    /** The saved Source [stored], ready for Lookups. */
    abstract fun source(tests: ConnectionTests, stored: StoredSource): Source

    object InternetArchive : ConfiguredKind(InternetArchiveSource.Kind) {
        override val unneededSetting = "The ${kind.name} needs no URL or login."
        override fun source(tests: ConnectionTests, stored: StoredSource) = InternetArchiveSource(tests.http, id = stored.id, name = stored.name)
    }

    /** Jellyfin or Emby, which log in with a username and password. */
    class MediaServer(kind: SourceKind) : ConfiguredKind(kind) {
        override val takesUrl get() = true
        override val takesLogin get() = true
        override fun addressOf(text: String?) = serverAddress(text)
        override val unneededSetting = "The ${kind.name} needs no URL or login."
        override val badUrl = "A Jellyfin or Emby Source needs the server's address, starting http:// or https://, such as http://192.168.1.20:8096."
        override suspend fun test(tests: ConnectionTests, source: StoredSource, logInWith: String?) = tests.testServer(source, logInWith)
        override fun source(tests: ConnectionTests, stored: StoredSource) = tests.server(stored).source(stored.id, stored.name, kind, tests.savedLogin(stored.id))
    }

    object StremioAddonSource : ConfiguredKind(StremioAddonKind) {
        override val takesUrl get() = true
        override fun addressOf(text: String?) = StremioAddon.manifestUrlOf(text)
        override val unneededSetting = "A Stremio-compatible add-on needs only its manifest URL, no login."
        override val badUrl = "A Stremio-compatible add-on Source needs its manifest URL, starting http:// or https:// and ending /manifest.json."
        override suspend fun test(tests: ConnectionTests, source: StoredSource, logInWith: String?) = tests.testAddon(source)
        override fun source(tests: ConnectionTests, stored: StoredSource) = tests.addon(stored).source(stored.id, stored.name, tests.savedManifest(stored.id))
    }

    companion object {
        val all: List<ConfiguredKind> = listOf(InternetArchive) + MediaServerKinds.all.map(::MediaServer) + StremioAddonSource

        /** The configured Kind whose Source Kind ID is [id], such as `jellyfin`; null for an Extension's or an unknown one. */
        fun of(id: String): ConfiguredKind? = all.find { it.kind.id == id }
    }
}

/**
 * What a Source called [name] whose connection failed for [reason] is told. The one table of reasons for every Kind:
 * each says in its own words what can't be reached, what turned the login down, and what answered wrongly.
 */
internal fun failureMessage(name: String, reason: Reason, unreachable: String, authRequired: String, sourceError: String) = when (reason) {
    Reason.Unreachable -> unreachable
    Reason.AuthRequired -> authRequired
    Reason.RateLimited -> "$name is refusing requests for now. Try again later."
    Reason.SourceError -> sourceError
}
