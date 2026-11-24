package dev.celastrina.core.catalogue

/**
 * Everything the Catalogue knows that a Source can match a Title on: what is asked of every Source.
 *
 * [alternativeTitles] are the Title's other known names, such as translations and working titles.
 */
data class TitleReference(
    val kind: Title.Kind,
    val tmdbId: Int,
    val imdbId: String?,
    val wikidataId: String?,
    val title: String,
    val originalTitle: String,
    val alternativeTitles: List<String>,
    val year: Int?,
)
