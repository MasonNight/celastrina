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

    /** The detail screen's view of the Movie with TMDB ID [tmdbId], with the Title reference for its Sources. */
    suspend fun movieDetails(tmdbId: Int): MovieDetails {
        val movie = getJson<TmdbMovieDetails>("/movie/$tmdbId", "append_to_response" to "external_ids,alternative_titles")
        val title = movieTitle(movie.id, movie.title, movie.releaseDate, movie.posterPath)
        return MovieDetails(
            title = title,
            backdropUrl = imageUrl(BACKDROP_SIZE, movie.backdropPath),
            runtimeMinutes = movie.runtime?.takeIf { it > 0 },
            genres = movie.genres.map { it.name },
            synopsis = movie.overview.orEmpty(),
            reference = TitleReference(
                kind = Title.Kind.Movie,
                tmdbId = movie.id,
                imdbId = movie.externalIds?.imdbId?.takeIf { it.isNotBlank() },
                wikidataId = movie.externalIds?.wikidataId?.takeIf { it.isNotBlank() },
                title = movie.title,
                originalTitle = movie.originalTitle ?: movie.title,
                alternativeTitles = movie.alternativeTitles?.titles.orEmpty().map { it.title }.distinct(),
                year = title.year,
            ),
        )
    }

    /** The detail screen's view of the Series with TMDB ID [tmdbId]. Specials, when TMDB has any, are the last season. */
    suspend fun seriesDetails(tmdbId: Int): SeriesDetails {
        val series = getJson<TmdbSeriesDetails>("/tv/$tmdbId", "append_to_response" to "external_ids,alternative_titles")
        val seasons = series.seasons.map { Season(it.seasonNumber, it.name, it.episodeCount) }
        val title = Title(series.id, Title.Kind.Series, series.name, yearOf(series.firstAirDate), imageUrl(POSTER_SIZE, series.posterPath))
        return SeriesDetails(
            title = title,
            backdropUrl = imageUrl(BACKDROP_SIZE, series.backdropPath),
            genres = series.genres.map { it.name },
            synopsis = series.overview.orEmpty(),
            seasons = seasons.filterNot { it.isSpecials } + seasons.filter { it.isSpecials },
            reference = TitleReference(
                kind = Title.Kind.Series,
                tmdbId = series.id,
                imdbId = series.externalIds?.imdbId?.takeIf { it.isNotBlank() },
                wikidataId = series.externalIds?.wikidataId?.takeIf { it.isNotBlank() },
                title = series.name,
                originalTitle = series.originalName ?: series.name,
                alternativeTitles = series.alternativeTitles?.results.orEmpty().map { it.title }.distinct(),
                year = title.year,
            ),
        )
    }

    /** The episodes of [season] of [series], in TMDB's order. */
    suspend fun episodes(series: SeriesDetails, season: Season): List<Episode> {
        val tmdbSeason = getJson<TmdbSeasonDetails>("/tv/${series.title.tmdbId}/season/${season.number}")
        return tmdbSeason.episodes.map { episode ->
            Episode(
                stillUrl = imageUrl(STILL_SIZE, episode.stillPath),
                number = episode.episodeNumber,
                name = episode.name,
                runtimeMinutes = episode.runtime?.takeIf { it > 0 },
                reference = series.reference.copy(
                    episode = TitleReference.Episode(
                        season = season.number,
                        episode = episode.episodeNumber,
                        absoluteEpisode = series.absoluteEpisodeNumber(season.number, episode.episodeNumber),
                        airDate = AirDate.parse(episode.airDate),
                    ),
                ),
            )
        }
    }

    private suspend inline fun <reified T> getJson(path: String, vararg query: Pair<String, String>): T {
        if (tmdb.apiKey.isBlank()) throw CatalogueUnavailable(CatalogueUnavailable.Reason.NoKey)
        val response = try {
            http.get(url(path, query.toList()), authHeaders())
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

    private fun url(path: String, query: List<Pair<String, String>>): String {
        val parameters = if (usesBearerToken) query else query + ("api_key" to tmdb.apiKey)
        val queryString = parameters.joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, "UTF-8") }
        return tmdb.apiBaseUrl + path + if (queryString.isEmpty()) "" else "?$queryString"
    }

    private fun authHeaders(): Map<String, String> =
        if (usesBearerToken) mapOf("Authorization" to "Bearer ${tmdb.apiKey}") else emptyMap()

    private fun TmdbMovie.toTitle() = movieTitle(id, title, releaseDate, posterPath)

    private fun movieTitle(tmdbId: Int, name: String, releaseDate: String?, posterPath: String?) = Title(
        tmdbId = tmdbId,
        kind = Title.Kind.Movie,
        name = name,
        year = yearOf(releaseDate),
        posterUrl = imageUrl(POSTER_SIZE, posterPath),
    )

    private fun yearOf(releaseDate: String?) = releaseDate?.take(4)?.toIntOrNull()

    private fun imageUrl(size: String, path: String?) = path?.let { "$IMAGE_BASE_URL/$size$it" }

    private companion object {
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w1280"
        const val STILL_SIZE = "w300"
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

@Serializable
private class TmdbMovieDetails(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("alternative_titles") val alternativeTitles: TmdbAlternativeTitles? = null,
)

@Serializable
private class TmdbGenre(val name: String)

@Serializable
private class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("wikidata_id") val wikidataId: String? = null,
)

@Serializable
private class TmdbAlternativeTitles(val titles: List<TmdbAlternativeTitle> = emptyList())

@Serializable
private class TmdbAlternativeTitle(val title: String)

@Serializable
private class TmdbSeriesDetails(
    val id: Int,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeason> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("alternative_titles") val alternativeTitles: TmdbSeriesAlternativeTitles? = null,
)

@Serializable
private class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

/** TMDB lists a Series' alternative titles under `results`, where a Movie's are under `titles`. */
@Serializable
private class TmdbSeriesAlternativeTitles(val results: List<TmdbAlternativeTitle> = emptyList())

@Serializable
private class TmdbSeasonDetails(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String,
    val runtime: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
)
