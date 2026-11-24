package dev.celastrina.core.catalogue

/** What a Movie's detail screen shows, and the [reference] its Sources are asked about. */
data class MovieDetails(
    val title: Title,
    val backdropUrl: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val synopsis: String,
    val reference: TitleReference,
)
