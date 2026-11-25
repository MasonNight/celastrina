package dev.celastrina.core.source.jellyfin

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder

/** The Jellyfin and Emby Source Kinds. Both servers speak the same API, so one adapter serves both. */
object MediaServerKinds {
    val Jellyfin = SourceKind("Jellyfin", idSchemes = setOf(IdScheme.Tmdb, IdScheme.Imdb), titleKinds = setOf(Title.Kind.Movie, Title.Kind.Series))
    val Emby = SourceKind("Emby", idSchemes = setOf(IdScheme.Tmdb, IdScheme.Imdb), titleKinds = setOf(Title.Kind.Movie, Title.Kind.Series))
    val all = listOf(Jellyfin, Emby)
}

/** What Celastrina keeps of a login to a Jellyfin or Emby server: the access token it issued, never the password. */
@Serializable
internal data class MediaServerLogin(val userId: String, val accessToken: String)

/** How a login attempt or connection test went: the login on success, or why it failed. */
internal sealed interface ConnectionResult {
    data class Connected(val login: MediaServerLogin) : ConnectionResult
    data class Failed(val reason: Reason) : ConnectionResult
}

/**
 * One Jellyfin or Emby server at [url] (no trailing '/'). [deviceId] names this TV to the server, which keeps one
 * session, and so one access token, per device and user.
 */
internal class MediaServer(
    private val http: HttpClient,
    private val url: String,
    private val deviceId: String,
    private val appVersion: String,
) {
    /** Logs in as [username], answering the access token to keep. */
    suspend fun logIn(username: String, password: String): ConnectionResult = connect {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val response = checked(http.postJson("$url/Users/AuthenticateByName", body, mapOf(AUTHORIZATION to authorization(token = null))))
        val auth = parse<AuthenticationResult>(response)
        MediaServerLogin(userId = auth.user.id, accessToken = auth.accessToken)
    }

    /** Checks the server is there and still accepts [login]. */
    suspend fun check(login: MediaServerLogin): ConnectionResult = connect {
        checked(http.get("$url/Users/${login.userId}", mapOf(AUTHORIZATION to authorization(login.accessToken))))
        login
    }

    fun source(id: String, name: String, kind: SourceKind, login: MediaServerLogin?): Source = MediaServerSource(id, name, kind, login)

    private suspend fun connect(attempt: suspend () -> MediaServerLogin): ConnectionResult = try {
        ConnectionResult.Connected(attempt())
    } catch (e: RequestFailed) {
        ConnectionResult.Failed(e.reason)
    } catch (e: IOException) {
        ConnectionResult.Failed(Reason.Unreachable)
    }

    private inner class MediaServerSource(
        override val id: String,
        override val name: String,
        override val kind: SourceKind,
        private val login: MediaServerLogin?,
    ) : Source {

        override suspend fun lookUp(title: TitleReference): LookupOutcome {
            val login = login ?: return LookupOutcome.Failure(Reason.AuthRequired)
            return try {
                val headers = mapOf(AUTHORIZATION to authorization(login.accessToken))
                val matches = find(title, login, headers)
                val items = when (val wanted = title.episode) {
                    null -> matches
                    else -> matches.firstOrNull()?.let { episode(it, wanted, login, headers) }?.let(::listOf).orEmpty()
                }
                val streams = items.flatMap { item -> item.mediaSources.map { it.toStream(item, headers) } }
                if (streams.isEmpty()) LookupOutcome.NotAvailable else LookupOutcome.Streams(streams)
            } catch (e: RequestFailed) {
                LookupOutcome.Failure(e.reason)
            } catch (e: IOException) {
                LookupOutcome.Failure(Reason.Unreachable)
            }
        }

        /**
         * Searches the server by each of the Title's names in turn, keeping the items whose TMDB or IMDb ID is the
         * Title's. Jellyfin ignores ID filters in a search, so the IDs are checked here.
         */
        private suspend fun find(title: TitleReference, login: MediaServerLogin, headers: Map<String, String>): List<Item> {
            val type = if (title.kind == Title.Kind.Movie) "Movie" else "Series"
            val names = (listOf(title.title, title.originalTitle) + title.alternativeTitles)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_NAMES_SEARCHED)
            for (name in names) {
                val search = "$url/Items?userId=${login.userId}&Recursive=true&IncludeItemTypes=$type&Fields=$FIELDS&searchTerm=${encode(name)}"
                val found = parse<Items>(checked(http.get(search, headers))).items.filter { it.isTitle(title) }
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        }

        private suspend fun episode(series: Item, wanted: TitleReference.Episode, login: MediaServerLogin, headers: Map<String, String>): Item? {
            val episodes = "$url/Shows/${series.id}/Episodes?userId=${login.userId}&season=${wanted.season}&Fields=$FIELDS"
            return parse<Items>(checked(http.get(episodes, headers))).items
                .firstOrNull { it.season == wanted.season && it.episode == wanted.episode }
        }

        private fun MediaSource.toStream(item: Item, headers: Map<String, String>): Stream {
            val video = streams.firstOrNull { it.type == "Video" }
            return Stream(
                sourceId = this@MediaServerSource.id,
                id = id,
                url = "$url/Videos/${item.id}/stream?static=true&MediaSourceId=$id",
                delivery = Stream.Delivery.Progressive,
                label = name,
                resolution = video?.let { nominalHeight(it.width, it.height) },
                videoCodec = video?.codec,
                container = container?.substringBefore(','),
                audioLanguages = streams.filter { it.type == "Audio" }.mapNotNull { it.language.known() }.distinct(),
                bitrate = bitrate,
                sizeBytes = size,
                headers = headers,
                // Jellyfin and Emby turn any text subtitle, in the file or beside it, into WebVTT on request.
                subtitles = streams.filter { it.type == "Subtitle" && it.isTextSubtitle }.map {
                    Stream.Subtitle("$url/Videos/${item.id}/$id/Subtitles/${it.index}/Stream.vtt", language = it.language.known(), label = it.displayTitle)
                },
            )
        }
    }

    /** The `MediaBrowser` scheme both servers read, naming Celastrina and this TV, with the access token once there is one. */
    private fun authorization(token: String?) = buildString {
        append("""MediaBrowser Client="Celastrina", Device="Fire TV", DeviceId="$deviceId", Version="$appVersion"""")
        token?.let { append(""", Token="$it"""") }
    }

    /** Answers [response] if it succeeded, otherwise throws why the server can't help. */
    private fun checked(response: HttpResponse): HttpResponse = when {
        response.isSuccessful -> response
        response.status == 401 || response.status == 403 -> throw RequestFailed(Reason.AuthRequired)
        response.status == 429 -> throw RequestFailed(Reason.RateLimited)
        else -> throw RequestFailed(Reason.SourceError)
    }

    private inline fun <reified T> parse(response: HttpResponse): T = try {
        json.decodeFromString<T>(response.bodyText())
    } catch (e: SerializationException) {
        throw RequestFailed(Reason.SourceError)
    } catch (e: IllegalArgumentException) {
        throw RequestFailed(Reason.SourceError)
    }

    private class RequestFailed(val reason: Reason) : Exception(reason.name)

    @Serializable
    private data class AuthenticationResult(@SerialName("AccessToken") val accessToken: String, @SerialName("User") val user: User)

    @Serializable
    private data class User(@SerialName("Id") val id: String)

    @Serializable
    private data class Items(@SerialName("Items") val items: List<Item> = emptyList())

    @Serializable
    private data class Item(
        @SerialName("Id") val id: String,
        @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
        @SerialName("ParentIndexNumber") val season: Int? = null,
        @SerialName("IndexNumber") val episode: Int? = null,
        @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
    ) {
        fun isTitle(title: TitleReference): Boolean {
            val ids = providerIds.mapKeys { it.key.lowercase() }
            return ids["tmdb"] == title.tmdbId.toString() || (title.imdbId != null && ids["imdb"] == title.imdbId)
        }
    }

    @Serializable
    private data class MediaSource(
        @SerialName("Id") val id: String,
        @SerialName("Name") val name: String? = null,
        @SerialName("Container") val container: String? = null,
        @SerialName("Size") val size: Long? = null,
        @SerialName("Bitrate") val bitrate: Long? = null,
        @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList(),
    )

    @Serializable
    private data class MediaStream(
        @SerialName("Type") val type: String,
        @SerialName("Index") val index: Int,
        @SerialName("Codec") val codec: String? = null,
        @SerialName("Language") val language: String? = null,
        @SerialName("Width") val width: Int? = null,
        @SerialName("Height") val height: Int? = null,
        @SerialName("DisplayTitle") val displayTitle: String? = null,
        @SerialName("IsTextSubtitleStream") val isTextSubtitle: Boolean = false,
    )

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val FIELDS = "ProviderIds,MediaSources"

        /** Enough to try the title, the original title and a few alternatives within the Lookup's timeout. */
        const val MAX_NAMES_SEARCHED = 5

        val json = Json { ignoreUnknownKeys = true }

        /** Spaces as %20: the form encoding's '+' isn't a space to every server. */
        fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        /** "und" is how both servers say they don't know the language. */
        fun String?.known(): String? = this?.takeUnless { it.isBlank() || it == "und" }

        /**
         * The picture height the Household would call it: a 1920×800 widescreen film is 1080p, as both servers label
         * it, though only 800 pixels high.
         */
        fun nominalHeight(width: Int?, height: Int?): Int? {
            val fromWidth = when {
                width == null -> null
                width >= 3800 -> 2160
                width >= 2500 -> 1440
                width >= 1900 -> 1080
                width >= 1260 -> 720
                else -> null
            }
            return listOfNotNull(height, fromWidth).maxOrNull()
        }
    }
}
