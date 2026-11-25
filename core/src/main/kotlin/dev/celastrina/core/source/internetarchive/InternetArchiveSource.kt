package dev.celastrina.core.source.internetarchive

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.failureReason
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder

/** Where the Internet Archive Source Kind finds Wikidata and the Archive. */
data class InternetArchiveSettings(
    val wikidataSparqlUrl: String = "https://query.wikidata.org/sparql",
    val metadataBaseUrl: String = "https://archive.org/metadata",
    val downloadBaseUrl: String = "https://archive.org/download",
)

/**
 * The Internet Archive test Source. It finds a Movie's Archive items through Wikidata (P724), and offers an item's
 * files only when the item carries a Creative Commons licence or public-domain marking, or Wikidata marks the Movie
 * public domain (P6216 = Q19652).
 */
class InternetArchiveSource(
    private val http: HttpClient,
    private val settings: InternetArchiveSettings = InternetArchiveSettings(),
    override val id: SourceId = SourceId("internet-archive"),
    override val name: String = "Internet Archive",
) : Source {

    override val kind: SourceKind get() = Kind

    override suspend fun lookUp(title: TitleReference): LookupOutcome = try {
        val films = filmsOnWikidata(title)
        val files = films.map { it.archiveId }.distinct().flatMap { archiveId ->
            val item = item(archiveId)
            val publicDomain = films.any { it.archiveId == archiveId && it.publicDomain }
            if (publicDomain || item.licensed) item.files.map { archiveId to it } else emptyList()
        }
        val streams = files
            .mapNotNull { (archiveId, file) -> file.tier?.let { Triple(archiveId, file, it) } }
            .sortedWith(compareBy({ it.third }, { -(it.second.height ?: 0) }, { it.second.name }))
            .map { (archiveId, file) -> file.toStream(archiveId) }
        if (streams.isEmpty()) LookupOutcome.NotAvailable else LookupOutcome.Streams(streams)
    } catch (e: LookupFailed) {
        LookupOutcome.Failure(e.reason)
    }

    private suspend fun filmsOnWikidata(title: TitleReference): List<WikidataFilm> {
        val matches = buildList {
            add("""{ ?film wdt:P4947 "${title.tmdbId}" . }""")
            title.imdbId?.takeIf { IMDB_ID.matches(it) }?.let { add("""{ ?film wdt:P345 "$it" . }""") }
            title.wikidataId?.takeIf { WIKIDATA_ID.matches(it) }?.let { add("{ VALUES ?film { wd:$it } }") }
        }.joinToString(" UNION ")
        val query = "SELECT ?film ?archiveId ?copyrightStatus WHERE { $matches ?film wdt:P724 ?archiveId . " +
            "OPTIONAL { ?film wdt:P6216 ?copyrightStatus . } }"
        val url = settings.wikidataSparqlUrl + "?format=json&query=" + URLEncoder.encode(query, "UTF-8")
        val results = getJson(url, mapOf("Accept" to "application/sparql-results+json", "User-Agent" to USER_AGENT))
        return parse {
            results.jsonObject.getValue("results").jsonObject.getValue("bindings").jsonArray.map { row ->
                val binding = row.jsonObject
                WikidataFilm(
                    archiveId = binding.value("archiveId")!!,
                    publicDomain = binding.value("copyrightStatus") == PUBLIC_DOMAIN,
                )
            }
        }
    }

    private suspend fun item(archiveId: String): ArchiveItem {
        val response = getJson(settings.metadataBaseUrl + "/" + pathSegment(archiveId))
        return parse {
            val metadata = response.jsonObject
            val licences = metadata["metadata"]?.jsonObject?.get("licenseurl").strings()
            ArchiveItem(
                licensed = licences.any { CREATIVE_COMMONS.containsMatchIn(it) },
                files = if (metadata["is_dark"]?.jsonPrimitive?.contentOrNull == "true") {
                    emptyList()
                } else {
                    metadata["files"]?.jsonArray.orEmpty().map { it.jsonObject.toArchiveFile() }
                },
            )
        }
    }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement {
        val response: HttpResponse = try {
            http.get(url, headers)
        } catch (e: IOException) {
            throw LookupFailed(LookupOutcome.Failure.Reason.Unreachable)
        }
        response.failureReason()?.let { throw LookupFailed(it) }
        return parse { json.parseToJsonElement(response.bodyText()) }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw LookupFailed(LookupOutcome.Failure.Reason.SourceError)
    } catch (e: IllegalArgumentException) {
        throw LookupFailed(LookupOutcome.Failure.Reason.SourceError)
    } catch (e: NoSuchElementException) {
        throw LookupFailed(LookupOutcome.Failure.Reason.SourceError)
    } catch (e: NullPointerException) {
        throw LookupFailed(LookupOutcome.Failure.Reason.SourceError)
    }

    private fun ArchiveFile.toStream(archiveId: String) = Stream(
        sourceId = id,
        id = "$archiveId/$name",
        url = settings.downloadBaseUrl + "/" + pathSegment(archiveId) + "/" + name.split('/').joinToString("/", transform = ::pathSegment),
        delivery = Stream.Delivery.Progressive,
        label = name,
        resolution = height,
        videoCodec = when {
            isH264 -> "h264"
            format.contains("MPEG4") -> "mpeg4"
            else -> null
        },
        container = extension,
        sizeBytes = size,
    )

    /** Ends a lookup early with [reason]. */
    private class LookupFailed(val reason: LookupOutcome.Failure.Reason) : Exception()

    private class WikidataFilm(val archiveId: String, val publicDomain: Boolean)

    private class ArchiveItem(val licensed: Boolean, val files: List<ArchiveFile>)

    private class ArchiveFile(val name: String, val format: String, val original: Boolean, val height: Int?, val size: Long?) {
        val extension = name.substringAfterLast('.', "").lowercase()
        val isH264 = format.contains("h.264", ignoreCase = true)

        /** Where this file ranks: lower first. Null when it is never offered. */
        val tier: Int? = when {
            extension == "mp4" && isH264 -> 0
            original && extension in setOf("mp4", "m4v", "mkv") -> 1
            extension == "mp4" -> 2
            else -> null
        }
    }

    private fun JsonObject.toArchiveFile() = ArchiveFile(
        name = getValue("name").jsonPrimitive.content,
        format = this["format"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        original = this["source"]?.jsonPrimitive?.contentOrNull == "original",
        height = this["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 },
        size = this["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
    )

    private fun JsonObject.value(name: String): String? = this[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull

    /** The Archive gives most metadata fields as a string, but as an array when an item has several values. */
    private fun JsonElement?.strings(): List<String> = when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull)
        is JsonArray -> flatMap { it.strings() }
        else -> emptyList()
    }

    private fun pathSegment(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        /** Movies only: Wikidata links almost no Series or episodes to Archive items. */
        val Kind = SourceKind(
            name = "Internet Archive",
            idSchemes = setOf(IdScheme.Tmdb, IdScheme.Imdb, IdScheme.Wikidata),
            titleKinds = setOf(Title.Kind.Movie),
        )

        private const val USER_AGENT = "Celastrina (https://github.com/MasonNight/celastrina)"
        private const val PUBLIC_DOMAIN = "http://www.wikidata.org/entity/Q19652"
        private val CREATIVE_COMMONS = Regex("""^https?://(www\.)?creativecommons\.org/(licenses|publicdomain)/""")
        private val IMDB_ID = Regex("""tt\d+""")
        private val WIKIDATA_ID = Regex("""Q\d+""")
        private val json = Json
    }
}
