package dev.celastrina.core.catalogue

/** What a Movie's detail screen shows, and the [reference] its Sources are asked about. */
data class MovieDetails(
    val title: Title,
    /** The title logo in the TV's language, else English; null when there's none, and the name is shown as text. */
    val logoUrl: String?,
    /** The backdrop without text, where TMDB has one. */
    val backdropUrl: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val synopsis: String,
    val reference: TitleReference,
)
