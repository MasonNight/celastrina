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
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.configuration.SourceChanges
import dev.celastrina.core.configuration.SourceSummary
import dev.celastrina.core.configuration.Status
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.extension.ExtensionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The agent's way to configure Celastrina over ADB (ADR 0002), a thin adapter over [ConfigurationService].
 *
 *     adb shell content call --uri content://dev.celastrina.configuration --method <command> [--arg <base64 JSON>]
 *     adb shell content query --uri content://dev.celastrina.configuration/<sources|status>
 *
 * Commands: `status`, `list`, `add` ({"kind", "name", and for `jellyfin` or `emby` "url", "username", "password", for
 * `stremio` the add-on's manifest URL as "url"}), `update` ({"id"} and any of "name", "url", "username", "password"), `set-order` ({"ids": [...]}, the Sources to
 * put first), `enable`, `disable` and `remove` ({"id"}), `set-tmdb-key` ({"key"}) and `clear-tmdb-key`.
 *
 * Extensions: `import-repository` ({"url", "warning_acknowledged": <warning version>}) answers the Extension
 * Repository's `repository` with its `extensions`; without the current warning version it answers `invalid` with
 * problem `warning_not_acknowledged`, the `warning` to show the person word for word, and its `warning_version`.
 * `install-extension` ({"repository": <its URL>, "name"}) answers the `ids` of the Sources its providers became.
 * `extensions` answers every imported Extension Repository (`repositories`, each with its `extensions`, which say
 * `installed`, `installed_version` and `update_available`) and `updates_available`, as of the last update check;
 * `check-updates` checks every Repository now, then answers the same. Nothing updates by itself: `update-extension`
 * ({"name"}) updates one, answering like `install-extension`, and `update-extensions` updates every one with an update,
 * answering each one's reply in `extensions`. `remove-extension` ({"name"}) and `remove-repository` ({"url"}) take
 * the Extensions' Sources out of the order at once; their code is gone after Celastrina next starts.
 *
 * `status` says
 * which TMDB key is in use (`project`, `override` or `none`), never the key. Every reply is one JSON object in the returned Bundle's
 * `reply`, with `result` set to `ok`, `invalid` (with `problem` and `message`) or `failure` (with `id`, `reason` and
 * `message`: saved, but failing its connection test). `add` replies the Source's `id` either way. Writes that test a
 * connection block until the test is done (a few seconds at most). Only the `shell` user, root and Celastrina itself
 * may call it.
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
            "add" -> args.onlyFields("kind", "name", "url", "username", "password") ?: run {
                val kind = args.string("kind") ?: return args.missing("kind")
                val name = args.string("name") ?: return args.missing("name")
                if (args.wrongType("url", "username", "password")) return invalid("bad_argument", "url, username and password must be strings.")
                val source = NewSource(kind, name, args.string("url"), args.string("username"), args.string("password"))
                runBlocking { configuration.add(source, ChangedFrom.Computer) }.toJson()
            }
            "update" -> args.onlyFields("id", "name", "url", "username", "password") ?: run {
                val id = args.string("id") ?: return args.missing("id")
                if (args.wrongType("name", "url", "username", "password")) return invalid("bad_argument", "name, url, username and password must be strings.")
                val changes = SourceChanges(args.string("name"), args.string("url"), args.string("username"), args.string("password"))
                runBlocking { configuration.update(id, changes, ChangedFrom.Computer) }.toJson()
            }
            "set-order" -> args.onlyFields("ids") ?: run {
                val ids = args.strings("ids") ?: return invalid("bad_argument", "--arg needs \"ids\", a list of Source IDs.")
                configuration.setOrder(ids, ChangedFrom.Computer).toJson()
            }
            "enable", "disable", "remove" -> args.onlyFields("id") ?: run {
                val id = args.string("id") ?: return args.missing("id")
                when (method) {
                    "enable" -> runBlocking { configuration.enable(id, ChangedFrom.Computer) }
                    "disable" -> configuration.disable(id, ChangedFrom.Computer)
                    else -> configuration.remove(id, ChangedFrom.Computer)
                }.toJson()
            }
            "set-tmdb-key" -> args.onlyFields("key") ?: run {
                val key = args.string("key") ?: return args.missing("key")
                configuration.setTmdbKey(key, ChangedFrom.Computer).toJson()
            }
            "clear-tmdb-key" -> args.onlyFields() ?: configuration.clearTmdbKey(ChangedFrom.Computer).toJson()
            "import-repository" -> args.onlyFields("url", "warning_acknowledged") ?: run {
                val url = args.string("url") ?: return args.missing("url")
                val acknowledged = args.int("warning_acknowledged")
                if (args.wrongType("warning_acknowledged") && acknowledged == null) {
                    return invalid("bad_argument", "warning_acknowledged must be the warning's version, a number.")
                }
                runBlocking { configuration.importRepository(url, acknowledged, ChangedFrom.Computer) }.toJson(url)
            }
            "install-extension" -> args.onlyFields("repository", "name") ?: run {
                val repository = args.string("repository") ?: return args.missing("repository")
                val name = args.string("name") ?: return args.missing("name")
                runBlocking { configuration.installExtension(repository, name, ChangedFrom.Computer) }.toJson(repository, name)
            }
            "extensions" -> args.onlyFields() ?: extensionsReply()
            "check-updates" -> args.onlyFields() ?: run {
                runBlocking { configuration.checkForUpdates(now = true) }
                extensionsReply()
            }
            "update-extension" -> args.onlyFields("name") ?: run {
                val name = args.string("name") ?: return args.missing("name")
                runBlocking { configuration.updateExtension(name, ChangedFrom.Computer) }.toJson(repository = null, name)
            }
            "update-extensions" -> args.onlyFields() ?: run {
                val outcomes = runBlocking { configuration.updateAllExtensions(ChangedFrom.Computer) }
                ok { put("extensions", JsonArray(outcomes.map { (name, outcome) -> JsonObject(mapOf("name" to JsonPrimitive(name)) + outcome.toJson(repository = null, name)) })) }
            }
            "remove-extension" -> args.onlyFields("name") ?: run {
                val name = args.string("name") ?: return args.missing("name")
                configuration.removeExtension(name, ChangedFrom.Computer).toJson()
            }
            "remove-repository" -> args.onlyFields("url") ?: run {
                val url = args.string("url") ?: return args.missing("url")
                configuration.removeRepository(url, ChangedFrom.Computer).toJson()
            }
            else -> invalid("unknown_command", "There's no command $method. Commands: ${COMMANDS.joinToString()}.")
        }
    }

    private fun extensionsReply() = ok {
        put("updates_available", configuration.updatesAvailable())
        put("repositories", JsonArray(configuration.repositories().map { it.toJson() }))
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
        fun string(name: String): String? = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** The whole-number argument [name], or null when it's missing or not one. */
        fun int(name: String): Int? = (fields[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        /** True when any of [names] is given but isn't a string. */
        fun wrongType(vararg names: String): Boolean = names.any { fields[it] != null && string(it) == null }

        /** The argument [name] as a list of strings, or null when it isn't one. */
        fun strings(name: String): List<String>? = (fields[name] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
        }

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
            problem?.let { put("problem", it) }
            if (updateAvailable) put("update_available", true)
        }

        val COMMANDS = listOf(
            "status", "list", "add", "update", "set-order", "enable", "disable", "remove", "set-tmdb-key", "clear-tmdb-key",
            "import-repository", "install-extension", "extensions", "check-updates", "update-extension", "update-extensions",
            "remove-extension", "remove-repository",
        )

        fun ExtensionRepository.toJson() = buildJsonObject {
            put("url", url)
            put("name", name)
            put(
                "extensions",
                JsonArray(
                    extensions.map {
                        buildJsonObject {
                            put("name", it.name)
                            put("display_name", it.displayName)
                            put("version", it.version)
                            put("description", it.description)
                            put("authors", JsonArray(it.authors.map(::JsonPrimitive)))
                            put("languages", JsonArray(it.languages.map(::JsonPrimitive)))
                            put("installed", it.installed)
                            put("installed_version", it.installedVersion)
                            put("update_available", it.updateAvailable)
                        }
                    },
                ),
            )
        }

        fun ImportOutcome.toJson(url: String) = when (this) {
            is ImportOutcome.Imported -> ok { put("repository", repository.toJson()) }
            is ImportOutcome.WarningNotAcknowledged -> buildJsonObject {
                put("result", "invalid")
                put("problem", "warning_not_acknowledged")
                put("message", "Show the person this warning word for word, and import again with warning_acknowledged $warningVersion only if they say yes.")
                put("warning", warning)
                put("warning_version", warningVersion)
            }
            is ImportOutcome.Invalid -> invalid(problem.wire(), message)
            is ImportOutcome.Failed -> buildJsonObject {
                put("result", "failure")
                put("url", url)
                put("reason", reason.wire())
                put("message", message)
            }
        }

        fun InstallOutcome.toJson(repository: String?, name: String) = when (this) {
            is InstallOutcome.Installed -> ok { put("ids", JsonArray(sourceIds.map(::JsonPrimitive))) }
            is InstallOutcome.Invalid -> invalid(problem.wire(), message)
            is InstallOutcome.Failed -> buildJsonObject {
                put("result", "failure")
                repository?.let { put("repository", it) }
                put("name", name)
                put("reason", reason.wire())
                put("message", message)
            }
        }

        fun WriteOutcome.toJson() = when (this) {
            WriteOutcome.Ok -> ok()
            is WriteOutcome.Added -> ok { put("id", sourceId) }
            is WriteOutcome.Invalid -> invalid(problem.wire(), message)
            is WriteOutcome.Failed -> buildJsonObject {
                put("result", "failure")
                put("id", sourceId)
                put("reason", reason.wire())
                put("message", message)
            }
        }

        /** `UnknownSource` → `unknown_source`. */
        fun Enum<*>.wire() = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()
    }
}
