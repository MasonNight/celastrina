package dev.celastrina.core.catalogue

/**
 * What home's hero shows for the focused Title. [logoUrl] is the Title's logo in the TV's language, else in English;
 * null means show [name] as plain text. [backdropUrl] is a backdrop without text where TMDB has one.
 * [runtimeMinutes] is for Movies and [seasonCount] for Series.
 */
data class Hero(
    val name: String,
    val logoUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val seasonCount: Int?,
    val genres: List<String>,
    val blurb: String,
)
