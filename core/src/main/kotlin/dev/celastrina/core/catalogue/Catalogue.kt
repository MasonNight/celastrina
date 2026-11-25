package dev.celastrina.core.catalogue

import dev.celastrina.core.port.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * The Catalogue, backed by TMDB. Names and text follow [locale], the TV's, where TMDB has them in its language, and
 * fall back to English.
 */
class Catalogue(
    private val http: HttpClient,
    private val tmdb: TmdbSettings,
    private val locale: Locale,
    private val cache: CatalogueCache? = null,
) {
    private val homeRows = HomeRows(::listPage)

    /** The first Titles of [homeRow]. */
    suspend fun row(homeRow: HomeRow): Row = homeRows.first(homeRow)

    /** [row] with its next Titles added, as you scroll right. A row that can't load more comes back unchanged. */
    suspend fun more(row: Row): Row = homeRows.more(row)

    /** What home's hero shows while [title] has focus. */
    suspend fun hero(title: Title): Hero {
        val path = if (title.kind == Title.Kind.Movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}"
        val details = getJson<TmdbHeroDetails>(
            path,
            "append_to_response" to "images,translations",
            "include_image_language" to listOf(locale.language, ENGLISH, "null").filter { it.isNotEmpty() }.distinct().joinToString(","),
            "language" to language,
        )
        val translations = details.translations?.translations.orEmpty()
        val inTvLanguage = translations.filter { it.language == locale.language }
        val inEnglish = translations.filter { it.language == ENGLISH }.sortedByDescending { it.country == "US" }
        val logos = details.images?.logos.orEmpty()
        return Hero(
            // TMDB gives the original name when it has none in the TV's language; English reads better than that.
            name = details.name.takeIf { inTvLanguage.any { it.data.name.isNotBlank() } }
                ?: inEnglish.map { it.data.name }.firstOrNull { it.isNotBlank() }
                ?: details.name,
            logoUrl = imageUrl(
                LOGO_SIZE,
                (logos.firstOrNull { it.language == locale.language } ?: logos.firstOrNull { it.language == ENGLISH })?.filePath,
            ),
            backdropUrl = imageUrl(
                BACKDROP_SIZE,
                details.images?.backdrops.orEmpty().firstOrNull { it.language == null }?.filePath ?: details.backdropPath,
            ),
            year = yearOf(details.releaseDate ?: details.firstAirDate),
            runtimeMinutes = details.runtime?.takeIf { it > 0 },
            seasonCount = details.numberOfSeasons,
            genres = details.genres.map { it.name },
            blurb = details.overview?.takeIf { it.isNotBlank() }
                ?: inEnglish.map { it.data.overview.orEmpty() }.firstOrNull { it.isNotBlank() }
                ?: "",
        )
    }

    /** The detail screen's view of the Movie with TMDB ID [tmdbId], with the Title reference for its Sources. */
    suspend fun movieDetails(tmdbId: Int): MovieDetails {
        val movie = getJson<TmdbMovieDetails>(
            "/movie/$tmdbId",
            "append_to_response" to "external_ids,alternative_titles",
            "language" to language,
        )
        val title = Title(
            tmdbId = movie.id,
            kind = Title.Kind.Movie,
            name = movie.title,
            year = yearOf(movie.releaseDate),
            posterUrl = imageUrl(POSTER_SIZE, movie.posterPath),
            category = HomeRows.categoryOf(movie.genres.map { it.id }, movie.originalLanguage),
        )
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

    /**
     * The Movies and Series TMDB finds for [query], in its relevance order: its first page, without people or adult
     * Titles. Search asks only TMDB, never the Sources.
     */
    suspend fun search(query: String): List<Title> =
        getJson<TmdbMixedPage>("/search/multi", "include_adult" to "false", "language" to language, "query" to query).titles()

    /** This week's trending Movies and Series, mixed in TMDB's order: what search shows before anything is typed. */
    suspend fun trending(): List<Title> = getJson<TmdbMixedPage>("/trending/all/week", "language" to language).titles()

    private fun TmdbMixedPage.titles(): List<Title> = results.filterNot { it.adult }.mapNotNull { item ->
        when (item.mediaType) {
            "movie" -> item.toTitle(Title.Kind.Movie)
            "tv" -> item.toTitle(Title.Kind.Series)
            else -> null
        }
    }

    private suspend fun listPage(source: ListSource, page: Int): HomeRows.ListPage {
        val list = getJson<TmdbListPage>(source.path, *source.query.toTypedArray(), "language" to language, "page" to "$page", freshForMillis = ROW_FRESH_FOR_MILLIS)
        return HomeRows.ListPage(
            titles = list.results.map { Listed(it.toTitle(source.kind), it.popularity) },
            nextPage = (page + 1).takeIf { it <= minOf(list.totalPages, TMDB_LAST_PAGE) },
        )
    }

    /** The TV's language as TMDB takes it, such as `de-DE`; English when the TV has none. */
    private val language: String = locale.toLanguageTag().takeUnless { locale.language.isEmpty() } ?: "en-US"

    /** The detail screen's view of the Series with TMDB ID [tmdbId]. Specials, when TMDB has any, are the last season. */
    suspend fun seriesDetails(tmdbId: Int): SeriesDetails {
        val series = getJson<TmdbSeriesDetails>(
            "/tv/$tmdbId",
            "append_to_response" to "external_ids,alternative_titles",
            "language" to language,
        )
        val seasons = series.seasons.map { Season(it.seasonNumber, it.name, it.episodeCount) }
        val title = Title(
            tmdbId = series.id,
            kind = Title.Kind.Series,
            name = series.name,
            year = yearOf(series.firstAirDate),
            posterUrl = imageUrl(POSTER_SIZE, series.posterPath),
            category = HomeRows.categoryOf(series.genres.map { it.id }, series.originalLanguage),
        )
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
        val tmdbSeason = getJson<TmdbSeasonDetails>("/tv/${series.title.tmdbId}/season/${season.number}", "language" to language)
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

    /** TMDB's answer at [path], from the cache while it's younger than [freshForMillis]. */
    private suspend inline fun <reified T> getJson(path: String, vararg query: Pair<String, String>, freshForMillis: Long = TITLE_FRESH_FOR_MILLIS): T {
        if (tmdb.apiKey.isBlank()) throw CatalogueUnavailable(CatalogueUnavailable.Reason.NoKey)
        val request = url(path, query.toList(), withKey = false)
        cache?.fresh(request, freshForMillis)?.let { fresh -> decodeOrNull<T>(fresh)?.let { return it } }
        val response = try {
            http.get(url(path, query.toList()), authHeaders())
        } catch (e: IOException) {
            // Offline, what's cached is better than nothing, however old.
            cache?.kept(request)?.let { kept -> decodeOrNull<T>(kept)?.let { return it } }
            throw CatalogueUnavailable(CatalogueUnavailable.Reason.Unreachable, e)
        }
        when {
            response.status == 401 -> throw CatalogueUnavailable(CatalogueUnavailable.Reason.KeyRejected)
            !response.isSuccessful -> throw CatalogueUnavailable(CatalogueUnavailable.Reason.ServiceError)
        }
        val body = response.bodyText()
        val decoded = try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw CatalogueUnavailable(CatalogueUnavailable.Reason.ServiceError, e)
        }
        cache?.store(request, body)
        return decoded
    }

    /** A cached answer, or null when it no longer reads (if what the Catalogue reads has changed shape since). */
    private inline fun <reified T> decodeOrNull(cached: String): T? =
        try {
            json.decodeFromString<T>(cached)
        } catch (_: SerializationException) {
            null
        }

    private val usesBearerToken get() = tmdb.apiKey.startsWith("eyJ")

    private fun url(path: String, query: List<Pair<String, String>>, withKey: Boolean = true): String {
        val parameters = if (usesBearerToken || !withKey) query else query + ("api_key" to tmdb.apiKey)
        val queryString = parameters.joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, "UTF-8") }
        return tmdb.apiBaseUrl + path + if (queryString.isEmpty()) "" else "?$queryString"
    }

    private fun authHeaders(): Map<String, String> =
        if (usesBearerToken) mapOf("Authorization" to "Bearer ${tmdb.apiKey}") else emptyMap()

    private fun TmdbListItem.toTitle(kind: Title.Kind) = Title(
        tmdbId = id,
        kind = kind,
        name = (if (kind == Title.Kind.Movie) title else name).orEmpty(),
        year = yearOf(if (kind == Title.Kind.Movie) releaseDate else firstAirDate),
        posterUrl = imageUrl(POSTER_SIZE, posterPath),
        category = HomeRows.categoryOf(genreIds, originalLanguage),
    )

    private fun yearOf(releaseDate: String?) = releaseDate?.take(4)?.toIntOrNull()

    private fun imageUrl(size: String, path: String?) = path?.let { "$IMAGE_BASE_URL/$size$it" }

    private companion object {
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w1280"
        const val LOGO_SIZE = "w500"
        const val ENGLISH = "en"

        /** TMDB serves no list page past 500, whatever its `total_pages` says. */
        const val TMDB_LAST_PAGE = 500
        const val STILL_SIZE = "w300"

        /** Title metadata is kept for 7 days. */
        const val TITLE_FRESH_FOR_MILLIS = 7 * 24 * 60 * 60 * 1000L

        /** Row lists are kept for a day, as TMDB's trending lists change daily. */
        const val ROW_FRESH_FOR_MILLIS = 24 * 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A page of a TMDB list: trending or discover, of Movies (which have a `title`) or Series (a `name`). */
@Serializable
private class TmdbListPage(
    val results: List<TmdbListItem>,
    @SerialName("total_pages") val totalPages: Int = 1,
)

/** A page of TMDB results that mixes Movies, Series and people, each item saying which in its `media_type`. */
@Serializable
private class TmdbMixedPage(val results: List<TmdbListItem>)

@Serializable
private class TmdbListItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val adult: Boolean = false,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
private class TmdbMovieDetails(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
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
private class TmdbGenre(val id: Int, val name: String)

@Serializable
private class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("wikidata_id") val wikidataId: String? = null,
)

@Serializable
private class TmdbAlternativeTitles(val titles: List<TmdbAlternativeTitle> = emptyList())

@Serializable
private class TmdbAlternativeTitle(val title: String)

/** A Movie or Series with its images and translations appended, for the hero. Movies have a `title`, Series a `name`. */
@Serializable
private class TmdbHeroDetails(
    private val title: String? = null,
    @SerialName("name") private val seriesName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val images: TmdbImages? = null,
    val translations: TmdbTranslations? = null,
) {
    val name: String get() = title ?: seriesName.orEmpty()
}

@Serializable
private class TmdbImages(val backdrops: List<TmdbImage> = emptyList(), val logos: List<TmdbImage> = emptyList())

@Serializable
private class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
)

@Serializable
private class TmdbTranslations(val translations: List<TmdbTranslation> = emptyList())

@Serializable
private class TmdbTranslation(
    @SerialName("iso_639_1") val language: String,
    @SerialName("iso_3166_1") val country: String,
    val data: TmdbTranslationData,
)

@Serializable
private class TmdbTranslationData(
    private val title: String? = null,
    @SerialName("name") private val seriesName: String? = null,
    val overview: String? = null,
) {
    val name: String get() = title ?: seriesName.orEmpty()
}

@Serializable
private class TmdbSeriesDetails(
    val id: Int,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
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
