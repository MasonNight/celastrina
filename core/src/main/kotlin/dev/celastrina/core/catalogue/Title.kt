package dev.celastrina.core.catalogue

/** A single entry in the Catalogue, as much of it as browsing needs. */
data class Title(
    val tmdbId: Int,
    val kind: Kind,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
) {
    enum class Kind { Movie, Series }
}

/** A named, ordered list of Titles shown on home. */
data class Row(val name: String, val titles: List<Title>)
