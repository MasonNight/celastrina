package dev.celastrina.core.household

import dev.celastrina.core.catalogue.AirDate
import dev.celastrina.core.catalogue.Category
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import kotlinx.serialization.Serializable

// How Household state stores Titles and Title references, kept apart from the domain types so renaming one doesn't
// lose what a TV has saved.

@Serializable
internal data class StoredTitle(
    val tmdbId: Int,
    val kind: String,
    val name: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val category: String? = null,
)

internal fun stored(title: Title) = StoredTitle(title.tmdbId, title.kind.name, title.name, title.year, title.posterUrl, title.category?.name)

internal fun restored(title: StoredTitle) = Title(
    title.tmdbId,
    Title.Kind.valueOf(title.kind),
    title.name,
    title.year,
    title.posterUrl,
    title.category?.let { name -> Category.entries.firstOrNull { it.name == name } },
)

@Serializable
internal data class StoredReference(
    val kind: String,
    val tmdbId: Int,
    val imdbId: String? = null,
    val wikidataId: String? = null,
    val title: String,
    val originalTitle: String,
    val alternativeTitles: List<String> = emptyList(),
    val year: Int? = null,
    val episode: StoredEpisode? = null,
)

@Serializable
internal data class StoredEpisode(val season: Int, val episode: Int, val absoluteEpisode: Int? = null, val airDate: String? = null)

internal fun stored(reference: TitleReference) = with(reference) {
    StoredReference(
        kind.name, tmdbId, imdbId, wikidataId, title, originalTitle, alternativeTitles, year,
        episode?.let { StoredEpisode(it.season, it.episode, it.absoluteEpisode, it.airDate?.toString()) },
    )
}

internal fun restored(reference: StoredReference) = with(reference) {
    TitleReference(
        Title.Kind.valueOf(kind), tmdbId, imdbId, wikidataId, title, originalTitle, alternativeTitles, year,
        episode?.let { TitleReference.Episode(it.season, it.episode, it.absoluteEpisode, AirDate.parse(it.airDate)) },
    )
}
