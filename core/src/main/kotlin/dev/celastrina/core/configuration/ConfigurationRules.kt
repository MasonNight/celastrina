package dev.celastrina.core.configuration

import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.stremio.StremioAddonKind
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours

// What the configuration classes share: storage keys, the Source Kinds they know, and the rules and messages for settings.

internal const val SOURCES = "sources.json"
/** Where an earlier Celastrina kept the one device ID it used for every Jellyfin and Emby Source. */
internal const val LEGACY_DEVICE_ID = "device-id"
internal const val INTERNET_ARCHIVE = "Internet Archive"
internal const val EXTENSIONS = "extensions.json"
internal const val WARNING_ACKNOWLEDGED = "extension-warning-acknowledged"
internal const val EXTENSION_KIND = "extension"
internal const val UPDATES_CHECKED = "extension-updates-checked"
internal val UPDATE_CHECK_INTERVAL = 24.hours.inWholeMilliseconds


internal fun codeKey(sha256: String) = "extension-$sha256.cs3"

internal fun sha256Of(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** [text] as an http(s) URL with a host, or null when it isn't one. */
internal fun webAddress(text: String): String? {
    val trimmed = text.trim()
    val uri = try {
        URI(trimmed)
    } catch (e: URISyntaxException) {
        return null
    }
    return trimmed.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() }
}

internal val KINDS = listOf(InternetArchiveSource.Kind) + MediaServerKinds.all + StremioAddonKind
internal val json = Json { ignoreUnknownKeys = true }

internal fun kindOf(id: String): SourceKind? = KINDS.find { it.id == id }

internal fun loginKey(id: SourceId) = "login-$id.json"

internal fun deviceIdKey(id: SourceId) = "device-id-$id"

internal fun manifestKey(id: SourceId) = "manifest-$id.json"

/** [text] as a server address, `http(s)://host[:port][/path]` without a trailing '/', or null when it isn't one. */
internal fun serverAddress(text: String?): String? {
    val trimmed = text?.trim()?.trimEnd('/') ?: return null
    val uri = try {
        URI(trimmed)
    } catch (e: URISyntaxException) {
        return null
    }
    return trimmed.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawQuery == null }
}

internal fun invalid(problem: WriteOutcome.Problem, message: String) = WriteOutcome.Invalid(problem, message)
internal fun blankName() = invalid(WriteOutcome.Problem.BlankName, "A Source needs a name.")
internal fun unknownSource(id: SourceId) = invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID $id.")
internal fun unneededSetting(kind: SourceKind) = invalid(
    WriteOutcome.Problem.UnneededSetting,
    if (kind == StremioAddonKind) "A Stremio-compatible add-on needs only its manifest URL, no login." else "The ${kind.name} needs no URL or login.",
)
internal fun badUrl(kind: SourceKind) = invalid(
    WriteOutcome.Problem.BadUrl,
    if (kind == StremioAddonKind) {
        "A Stremio-compatible add-on Source needs its manifest URL, starting http:// or https:// and ending /manifest.json."
    } else {
        "A Jellyfin or Emby Source needs the server's address, starting http:// or https://, such as http://192.168.1.20:8096."
    },
)
