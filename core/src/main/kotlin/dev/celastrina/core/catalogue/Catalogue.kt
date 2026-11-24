package dev.celastrina.core.catalogue

import dev.celastrina.core.port.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder

/** The Catalogue, backed by TMDB. */
class Catalogue(
    private val http: HttpClient,
    private val tmdb: TmdbSettings,
) {
    /** This week's trending Movies, in TMDB's order. */
    suspend fun moviesRow(): Row {
        val page = getJson<TmdbPage>("/trending/movie/week")
        return Row("Movies", page.results.map { it.toTitle() })
    }

    private suspend inline fun <reified T> getJson(path: String): T {
        val response = try {
            http.get(url(path), authHeaders())
        } catch (e: IOException) {
            throw CatalogueUnavailable(CatalogueUnavailable.Reason.Unreachable, e)
        }
        when {
            response.status == 401 -> throw CatalogueUnavailable(CatalogueUnavailable.Reason.KeyRejected)
            !response.isSuccessful -> throw CatalogueUnavailable(CatalogueUnavailable.Reason.ServiceError)
        }
        return try {
            json.decodeFromString<T>(response.bodyText())
        } catch (e: SerializationException) {
            throw CatalogueUnavailable(CatalogueUnavailable.Reason.ServiceError, e)
        }
    }

    private val usesBearerToken get() = tmdb.apiKey.startsWith("eyJ")

    private fun url(path: String): String {
        val query = if (usesBearerToken) "" else "?api_key=" + URLEncoder.encode(tmdb.apiKey, Charsets.UTF_8)
        return tmdb.apiBaseUrl + path + query
    }

    private fun authHeaders(): Map<String, String> =
        if (usesBearerToken) mapOf("Authorization" to "Bearer ${tmdb.apiKey}") else emptyMap()

    private fun TmdbMovie.toTitle() = Title(
        tmdbId = id,
        kind = Title.Kind.Movie,
        name = title,
        year = releaseDate?.take(4)?.toIntOrNull(),
        posterUrl = posterPath?.let { "$IMAGE_BASE_URL/$POSTER_SIZE$it" },
    )

    private companion object {
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
        const val POSTER_SIZE = "w342"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private class TmdbPage(val results: List<TmdbMovie>)

@Serializable
private class TmdbMovie(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)
