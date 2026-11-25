package dev.celastrina.core.extension

import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/** What the Household is told, every time, before importing an Extension Repository. */
object ExtensionWarning {
    /** Goes up whenever [text] changes in substance, so an acknowledgement of an older text doesn't count. */
    const val VERSION = 1

    const val TEXT = "Extensions are unverified code from third parties, and Celastrina runs them with no isolation. " +
        "An Extension can read your other Sources' logins, everything Celastrina stores, and your home network. " +
        "Import this Extension Repository only if you trust whoever runs it."
}

/** An Extension Repository the Household imported, and the Extensions it lists. */
data class ExtensionRepository(val url: String, val name: String, val extensions: List<AvailableExtension>)

/** One Extension an Extension Repository lists. [name] identifies it; [displayName] is what people read. */
data class AvailableExtension(
    val name: String,
    val displayName: String,
    val version: Int,
    val description: String?,
    val authors: List<String>,
    val languages: List<String>,
    val installed: Boolean,
    /** The version installed, or null when it isn't. */
    val installedVersion: Int? = null,
) {
    /** Installed, and the Extension Repository lists a newer version. Updates are only ever made by hand. */
    val updateAvailable: Boolean get() = installedVersion != null && version > installedVersion
}

/** One Extension as an Extension Repository's list gives it: where its code is, and the SHA-256 the code must have. */
@Serializable
internal data class ListedExtension(
    val name: String,
    val displayName: String,
    val version: Int,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val url: String,
    /** Lowercase hex, or null when the list gives none. */
    val sha256: String?,
)

/** An Extension Repository's lists, as fetched. */
internal class FetchedRepository(val name: String, val extensions: List<ListedExtension>)

/** Thrown when an Extension Repository can't be read: [reason] and a message the Household can act on. */
internal class RepositoryUnreadable(val reason: Reason, override val message: String) : Exception(message)

/**
 * Reads CloudStream Extension Repositories: a `repo.json` naming one or more plugin lists, each an array of plugins.
 * URLs in them may be relative to the file that gives them.
 */
internal class RepositoryReader(private val http: HttpClient) {

    suspend fun read(url: String): FetchedRepository {
        val repository = json<RepoJson>(url)
        val extensions = repository.pluginLists.flatMap { listUrl ->
            val absolute = resolve(url, listUrl)
            json<List<PluginJson>>(absolute).map { it.toListed(absolute) }
        }
        return FetchedRepository(repository.name, extensions.distinctBy { it.name })
    }

    /** The code at [url]. */
    suspend fun download(url: String): ByteArray {
        val response = try {
            http.get(url)
        } catch (e: IOException) {
            throw RepositoryUnreadable(Reason.Unreachable, "Can't reach $url.")
        }
        if (!response.isSuccessful) throw RepositoryUnreadable(Reason.SourceError, "$url answered HTTP ${response.status}.")
        return response.body
    }

    private suspend inline fun <reified T> json(url: String): T {
        val text = download(url).decodeToString()
        return try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw RepositoryUnreadable(Reason.SourceError, "$url isn't an Extension Repository's JSON.")
        } catch (e: IllegalArgumentException) {
            throw RepositoryUnreadable(Reason.SourceError, "$url isn't an Extension Repository's JSON.")
        }
    }

    @Serializable
    private class RepoJson(val name: String, val pluginLists: List<String>)

    @Serializable
    private class PluginJson(
        val url: String,
        val version: Int,
        val name: String,
        val internalName: String,
        val description: String? = null,
        val authors: List<String> = emptyList(),
        val language: String? = null,
        @SerialName("fileHash") val fileHash: String? = null,
    ) {
        fun toListed(listUrl: String) = ListedExtension(
            name = internalName,
            displayName = name,
            version = version,
            description = description,
            authors = authors,
            language = language,
            url = resolve(listUrl, url),
            sha256 = fileHash?.lowercase()?.removePrefix("sha256-")?.takeIf { SHA256.matches(it) },
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val SHA256 = Regex("[0-9a-f]{64}")

        fun resolve(base: String, url: String): String = try {
            URI(base).resolve(url.trim()).toString()
        } catch (e: URISyntaxException) {
            throw RepositoryUnreadable(Reason.SourceError, "$url, listed in $base, isn't a URL.")
        } catch (e: IllegalArgumentException) {
            throw RepositoryUnreadable(Reason.SourceError, "$url, listed in $base, isn't a URL.")
        }
    }
}
