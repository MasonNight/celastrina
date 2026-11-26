package dev.celastrina.core.catalogue

/**
 * Everything the Catalogue knows that a Source can match a Title on: what is asked of every Source.
 *
 * [alternativeTitles] are the Title's other known names, such as translations and working titles.
 * [episode] says which episode of a Series is wanted, and is null for a Movie.
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
    val episode: Episode? = null,
) {
    /**
     * One episode of a Series, numbered as TMDB numbers it: [season] 0 holds the specials.
     * [absoluteEpisode] counts every episode from the first, specials excluded, so it is null for a special.
     */
    data class Episode(
        val season: Int,
        val episode: Int,
        val absoluteEpisode: Int?,
        val airDate: AirDate?,
    )
}

/** A calendar day, such as the day an episode first aired. */
data class AirDate(val year: Int, val month: Int, val day: Int) {
    override fun toString() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    internal companion object {
        /** Reads an ISO 8601 day, such as `2012-01-01`; anything else is null. */
        fun parse(text: String?): AirDate? {
            val (year, month, day) = text?.let(Regex("""(\d{4})-(\d{2})-(\d{2})""")::matchEntire)?.destructured ?: return null
            return AirDate(year.toInt(), month.toInt(), day.toInt())
        }
    }
}
