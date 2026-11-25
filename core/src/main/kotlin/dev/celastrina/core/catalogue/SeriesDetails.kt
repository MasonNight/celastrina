package dev.celastrina.core.catalogue

/**
 * What a Series' detail screen shows: its [seasons] as tabs, in the order they are shown.
 * [reference] is the Series' part of each episode's Title reference.
 */
data class SeriesDetails(
    val title: Title,
    val backdropUrl: String?,
    val genres: List<String>,
    val synopsis: String,
    val seasons: List<Season>,
    internal val reference: TitleReference,
) {
    /** The episode's number counting from the Series' first, specials excluded; null for a special. */
    internal fun absoluteEpisodeNumber(season: Int, episode: Int): Int? {
        if (season == Season.SPECIALS) return null
        val earlier = seasons.filter { !it.isSpecials && it.number < season }.sumOf { it.episodeCount }
        return earlier + episode
    }
}

/** One season of a Series, numbered as TMDB numbers it. TMDB's season 0 holds the Series' specials. */
data class Season(val number: Int, val name: String, val episodeCount: Int) {
    val isSpecials get() = number == SPECIALS

    internal companion object {
        const val SPECIALS = 0
    }
}

/** One episode of a Series, as its card shows it, and the [reference] its Sources are asked about when it is picked. */
data class Episode(
    val stillUrl: String?,
    /** Its number within its season. */
    val number: Int,
    val name: String,
    val runtimeMinutes: Int?,
    val reference: TitleReference,
)
