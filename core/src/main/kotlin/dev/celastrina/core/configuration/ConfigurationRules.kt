package dev.celastrina.core.configuration

import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
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

internal val json = Json { ignoreUnknownKeys = true }

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
internal fun unneededSetting(kind: ConfiguredKind?) =
    invalid(WriteOutcome.Problem.UnneededSetting, kind?.unneededSetting ?: "A Source from an Extension takes no URL or login.")
internal fun badUrl(kind: ConfiguredKind) = invalid(WriteOutcome.Problem.BadUrl, kind.badUrl)
