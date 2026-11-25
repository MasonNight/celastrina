package dev.celastrina.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.SourceSummary
import dev.celastrina.core.configuration.Status
import dev.celastrina.core.configuration.WriteOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The agent's way to configure Celastrina over ADB (ADR 0002), a thin adapter over [ConfigurationService].
 *
 *     adb shell content call --uri content://dev.celastrina.configuration --method <command> [--arg <base64 JSON>]
 *     adb shell content query --uri content://dev.celastrina.configuration/<sources|status>
 *
 * Commands: `status`, `list`, `update` ({"id", "name"}), `enable`, `disable` and `remove` ({"id"}), `set-tmdb-key`
 * ({"key"}) and `clear-tmdb-key`. `status` says which TMDB key is in use (`project`, `override` or `none`), never
 * the key. Every reply is
 * one JSON object in the returned Bundle's `reply`, with `result` set to `ok`, `invalid` (with `problem` and
 * `message`) or `failure` (with `reason`). Only the `shell` user, root and Celastrina itself may call it.
 */
class ConfigurationProvider : ContentProvider() {

    private val configuration: ConfigurationService
        get() = (checkNotNull(context).applicationContext as CelastrinaApplication).configuration

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        refuseStrangers()
        return Bundle().apply { putString(REPLY, reply(method, arg).toString()) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        refuseStrangers()
        return when (uri.lastPathSegment) {
            "sources" -> MatrixCursor(SOURCE_COLUMNS).apply {
                configuration.list().forEach { s ->
                    addRow(arrayOf<Any>(s.id, s.name, s.kind, s.enabled.toString(), s.position, s.testState.wire(), s.hasLogin.toString()))
                }
            }
            "status" -> configuration.status().let { s ->
                MatrixCursor(STATUS_COLUMNS).apply { addRow(arrayOf<Any>(s.appVersion, s.tmdbKey.wire(), s.sources, s.extensions, s.protocolVersion)) }
            }
            else -> throw IllegalArgumentException("Query content://${uri.authority}/sources or /status, not $uri")
        }
    }

    private fun reply(method: String, arg: String?): JsonObject {
        val args = try {
            Arguments(arg)
        } catch (e: BadArgument) {
            return invalid("bad_argument", e.message!!)
        }
        return when (method) {
            "status" -> configuration.status().toJson()
            "list" -> ok { put("sources", JsonArray(configuration.list().map { it.toJson() })) }
            "update" -> args.onlyFields("id", "name") ?: run {
                val id = args.required("id") ?: return args.missing("id")
                val name = args.required("name") ?: return args.missing("name")
                configuration.rename(id, name, ChangedFrom.Computer).toJson()
            }
            "enable", "disable", "remove" -> args.onlyFields("id") ?: run {
                val id = args.required("id") ?: return args.missing("id")
                when (method) {
                    "enable" -> configuration.enable(id, ChangedFrom.Computer)
                    "disable" -> configuration.disable(id, ChangedFrom.Computer)
                    else -> configuration.remove(id, ChangedFrom.Computer)
                }.toJson()
            }
            "set-tmdb-key" -> args.onlyFields("key") ?: run {
                val key = args.required("key") ?: return args.missing("key")
                configuration.setTmdbKey(key, ChangedFrom.Computer).toJson()
            }
            "clear-tmdb-key" -> args.onlyFields() ?: configuration.clearTmdbKey(ChangedFrom.Computer).toJson()
            else -> invalid(
                "unknown_command",
                "There's no command $method. Commands: status, list, update, enable, disable, remove, set-tmdb-key, clear-tmdb-key.",
            )
        }
    }

    /** Lets in only `adb shell`, root and Celastrina itself: the trusted ADB connection is the only authentication. */
    private fun refuseStrangers() {
        val caller = Binder.getCallingUid()
        val appId = caller % PER_USER_RANGE
        if (appId != ROOT_UID && appId != SHELL_UID && caller != Process.myUid()) {
            throw SecurityException("Only adb shell, root or Celastrina can configure Celastrina, not uid $caller")
        }
    }

    /** The decoded `--arg`: a JSON object, base64-encoded so quoting works the same from every computer's shell. */
    private class Arguments(arg: String?) {
        private val fields: JsonObject = if (arg.isNullOrEmpty()) JsonObject(emptyMap()) else {
            val text = try {
                Base64.decode(arg, Base64.DEFAULT).decodeToString(throwOnInvalidSequence = true)
            } catch (e: IllegalArgumentException) {
                throw BadArgument("--arg isn't base64. Encode the JSON arguments with base64.")
            } catch (e: CharacterCodingException) {
                throw BadArgument("--arg isn't base64-encoded UTF-8 JSON.")
            }
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                throw BadArgument("--arg isn't a JSON object: $text")
            } catch (e: IllegalArgumentException) {
                throw BadArgument("--arg isn't a JSON object: $text")
            }
        }

        /** The string argument [name], or null when it's missing or not a string. */
        fun required(name: String): String? = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun missing(name: String) = invalid("bad_argument", "--arg needs \"$name\", a string.")

        /** A refusal when the arguments carry a field this command doesn't take, or null when they don't. */
        fun onlyFields(vararg names: String): JsonObject? =
            (fields.keys - names.toSet()).takeIf { it.isNotEmpty() }?.let {
                val takes = if (names.isEmpty()) "no arguments" else names.joinToString()
                invalid("bad_argument", "Unexpected arguments: ${it.joinToString()}. This command takes $takes.")
            }
    }

    private class BadArgument(message: String) : Exception(message)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use content call")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Use content call")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use content call")

    private companion object {
        const val REPLY = "reply"
        const val ROOT_UID = 0
        const val SHELL_UID = 2000 // Process.SHELL_UID is API 29.
        const val PER_USER_RANGE = 100_000
        val SOURCE_COLUMNS = arrayOf("id", "name", "kind", "enabled", "position", "test_state", "has_login")
        val STATUS_COLUMNS = arrayOf("app_version", "tmdb_key", "sources", "extensions", "protocol_version")

        fun ok(fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("result", "ok")
            fields()
        }

        fun invalid(problem: String, message: String) = buildJsonObject {
            put("result", "invalid")
            put("problem", problem)
            put("message", message)
        }

        fun Status.toJson() = ok {
            put("app_version", appVersion)
            put("tmdb_key", tmdbKey.wire())
            put("sources", sources)
            put("extensions", extensions)
            put("protocol_version", protocolVersion)
        }

        fun SourceSummary.toJson(): JsonElement = buildJsonObject {
            put("id", id)
            put("name", name)
            put("kind", kind)
            put("enabled", enabled)
            put("position", position)
            put("test_state", testState.wire())
            put("has_login", hasLogin)
        }

        fun WriteOutcome.toJson() = when (this) {
            WriteOutcome.Ok -> ok()
            is WriteOutcome.Invalid -> invalid(problem.wire(), message)
        }

        /** `UnknownSource` → `unknown_source`. */
        fun Enum<*>.wire() = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()
    }
}
