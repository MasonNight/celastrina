package dev.celastrina.core.catalogue

/** A single entry in the Catalogue, as much of it as browsing needs. [category] is null for most Titles. */
data class Title(
    val tmdbId: Int,
    val kind: Kind,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val category: Category?,
) {
    enum class Kind { Movie, Series }
}

/**
 * A label that groups Titles for browsing, worked out from a Title's genres and original language, never curated.
 * A Title is in at most one.
 */
enum class Category {
    /** Animation with Japanese as the original language. */
    Anime,

    /** Animation in any other original language. */
    Cartoons,
}

/** The rows of Titles on home that come from the Catalogue, in the order they're stacked. */
enum class HomeRow(val label: String) {
    /** This week's trending Movies, except Anime and Cartoons. */
    Movies("Movies"),

    /** This week's trending Series, except Anime and Cartoons. */
    Series("Series"),

    /** Anime Movies and Series, by popularity. */
    Anime("Anime"),

    /** Cartoons Movies and Series, by popularity. */
    Cartoons("Cartoons"),
}

/**
 * A named, ordered list of Titles shown on home, as far as it has loaded. While [canLoadMore], `Catalogue.more`
 * returns it with the next Titles added; it stops at about 100 Titles. A row [fromOfflineCache] was read, at least in
 * part, from what the cache kept because TMDB couldn't be reached: it may be old, so it's worth loading again once the TV
 * is back online.
 */
class Row internal constructor(
    val name: String,
    val titles: List<Title>,
    internal val rest: RowRest?,
    val fromOfflineCache: Boolean = false,
) {
    val canLoadMore: Boolean get() = rest != null
}
