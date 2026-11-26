package dev.celastrina.testextension

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Films and Series on the Internet Archive, under the same rights rule as Celastrina's Internet Archive Source: an item
 * is played only when it carries a Creative Commons licence or public-domain marking, or Wikidata marks the Title public
 * domain (P6216 = Q19652).
 *
 * It opens a Title by IMDb ID through Wikidata (P345 to P724), or finds films by title with the Archive's search. An
 * item whose video files are named `SxxEyy` is a Series with those episodes.
 */
class InternetArchiveProvider : MainAPI() {
    override var name = "Internet Archive (test)"
    override var mainUrl = "https://archive.org"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val supportedSyncNames = setOf(SyncIdName.Imdb)

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || !IMDB_ID.matches(id)) return null
        val query = "SELECT ?film ?archiveId ?copyrightStatus WHERE { { ?film wdt:P345 \"$id\" . } ?film wdt:P724 ?archiveId . " +
            "OPTIONAL { ?film wdt:P6216 ?copyrightStatus . } }"
        val response = app.get(
            "$WIKIDATA?format=json&query=" + encode(query),
            headers = mapOf("Accept" to "application/sparql-results+json", "User-Agent" to USER_AGENT),
        )
        val bindings = mapper.readTree(response.text).path("results").path("bindings")
        val film = bindings.firstOrNull() ?: return null
        val archiveId = film.path("archiveId").path("value").asText()
        val publicDomain = bindings.any { it.path("copyrightStatus").path("value").asText() == PUBLIC_DOMAIN }
        return "$mainUrl/details/$archiveId" + if (publicDomain) PUBLIC_DOMAIN_MARK else ""
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/advancedsearch.php?q=" + encode("title:(\"$query\") AND mediatype:(movies)") +
            "&fl[]=identifier&fl[]=title&fl[]=year&rows=20&output=json"
        val docs = mapper.readValue(app.get(url).text, SearchResult::class.java).response.docs
        return docs.map { doc ->
            newMovieSearchResponse(doc.title, "$mainUrl/details/${doc.identifier}", TvType.Movie) {
                year = doc.year?.toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = item(url)
        val episodes = item.videos().mapNotNull { it.episode }.distinct()
        return if (episodes.isEmpty()) {
            newMovieLoadResponse(item.title, url, TvType.Movie, dataUrl = url) { year = item.year }
        } else {
            newTvSeriesLoadResponse(
                item.title,
                url,
                TvType.TvSeries,
                episodes.map { (season, episode) ->
                    newEpisode("$url$EPISODE_MARK$season-$episode") {
                        this.season = season
                        this.episode = episode
                    }
                },
            ) { year = item.year }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val item = item(data.substringBefore(EPISODE_MARK))
        if (!item.licensed && PUBLIC_DOMAIN_MARK !in data) return false
        val episode = data.substringAfter(EPISODE_MARK, "").split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
        val files = item.videos().filter { episode == null || it.episode == episode[0] to episode[1] }
        files.sortedWith(compareBy({ it.tier }, { -(it.height ?: 0) })).forEach { file ->
            callback(
                newExtractorLink(name, file.name, "$mainUrl/download/${item.id}/${file.name.split('/').joinToString("/") { encode(it).replace("+", "%20") }}", ExtractorLinkType.VIDEO) {
                    quality = file.height ?: UNKNOWN_QUALITY
                },
            )
        }
        // The Archive only lists files. These test links exercise Extension link types without live Streams.
        if (item.id == "Night.Of.The.Living.Dead_1080p") {
            callback(newExtractorLink(name, "Plain video", "$mainUrl/test-stream/video", ExtractorLinkType.VIDEO))
            callback(newExtractorLink(name, "HLS", "$mainUrl/test-stream/hls", ExtractorLinkType.M3U8))
            callback(newExtractorLink(name, "DASH", "$mainUrl/test-stream/dash", ExtractorLinkType.DASH))
        }
        return files.isNotEmpty()
    }

    private suspend fun item(url: String): Item {
        val id = url.substringAfter("/details/").substringBefore('#')
        val metadata = mapper.readTree(app.get("$mainUrl/metadata/$id").text)
        val fields = metadata.path("metadata")
        return Item(
            id = id,
            title = fields.path("title").asText(id),
            year = fields.path("year").asText().toIntOrNull(),
            licensed = fields.path("licenseurl").values().any { CREATIVE_COMMONS.containsMatchIn(it) },
            files = metadata.path("files").map { file ->
                File(
                    name = file.path("name").asText(),
                    format = file.path("format").asText(),
                    original = file.path("source").asText() == "original",
                    height = file.path("height").asText().toIntOrNull()?.takeIf { it > 0 },
                )
            },
        )
    }

    /** The Archive gives a field as a string, or as an array when an item has several values. */
    private fun JsonNode.values(): List<String> = if (isArray) map { it.asText() } else listOfNotNull(textValue())

    private class Item(val id: String, val title: String, val year: Int?, val licensed: Boolean, val files: List<File>) {
        /** The files worth playing: never Ogg Video, never a sample. */
        fun videos() = files.filter { it.tier != null && "/sample/" !in "/${it.name.lowercase()}" }
    }

    private class File(val name: String, val format: String, val original: Boolean, val height: Int?) {
        private val extension = name.substringAfterLast('.', "").lowercase()

        /** The season and episode its name gives as `SxxEyy`, or null. */
        val episode: Pair<Int, Int>? = EPISODE.find(name.substringAfterLast('/'))?.destructured?.let { (s, e) -> s.toInt() to e.toInt() }

        /** h.264 MP4 first, then original MP4 and MKV, then other MP4; null for anything else. */
        val tier: Int? = when {
            extension == "mp4" && format.contains("h.264", ignoreCase = true) -> 0
            original && extension in setOf("mp4", "m4v", "mkv") -> 1
            extension == "mp4" -> 2
            else -> null
        }
    }

    private class SearchResult(@JsonProperty("response") val response: Docs)

    private class Docs(@JsonProperty("docs") val docs: List<Doc>)

    private class Doc(
        @JsonProperty("identifier") val identifier: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("year") val year: String?,
    )

    private companion object {
        const val WIKIDATA = "https://query.wikidata.org/sparql"

        /** Wikimedia refuses a client that doesn't say who it is and where to find out more, such as OkHttp's own. */
        const val USER_AGENT = "CelastrinaTestExtension/1 (https://github.com/MasonNight/celastrina)"
        const val PUBLIC_DOMAIN = "http://www.wikidata.org/entity/Q19652"
        const val PUBLIC_DOMAIN_MARK = "#public-domain"
        const val EPISODE_MARK = "#episode="
        const val UNKNOWN_QUALITY = 400
        val IMDB_ID = Regex("""tt\d+""")
        val EPISODE = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""")
        val CREATIVE_COMMONS = Regex("""^https?://(www\.)?creativecommons\.org/(licenses|publicdomain)/""")

        fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
    }
}
