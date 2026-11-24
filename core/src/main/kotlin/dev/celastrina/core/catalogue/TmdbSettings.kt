package dev.celastrina.core.catalogue

/**
 * Where the Catalogue's TMDB lives and how to authenticate to it.
 *
 * [apiKey] is either a v4 read access token (sent as `Authorization: Bearer`) or a v3 API key
 * (sent as the `api_key` query parameter); TMDB issues both and the Household may have either.
 */
data class TmdbSettings(
    val apiKey: String,
    val apiBaseUrl: String = "https://api.themoviedb.org/3",
) {
    override fun toString() = "TmdbSettings(apiKey=<redacted>, apiBaseUrl=$apiBaseUrl)"
}
