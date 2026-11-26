package dev.celastrina.core.household

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What is being watched: a Movie, or one episode of a Series, with what its Continue watching card shows.
 * [reference] is what the Sources are asked about when it resumes.
 */
data class Watching(
    val title: Title,
    val reference: TitleReference,
    /** The episode as its card names it, such as "S1 E3 · Pilot"; null for a Movie. */
    val episodeLabel: String?,
    /** A landscape picture for the card: the episode's still or the Title's backdrop. */
    val imageUrl: String?,
)

/**
 * This TV's Continue watching: where playback got to in each Title or episode, and the Stream that last worked for it,
 * which goes first next time (see [candidates][dev.celastrina.core.failover.candidates]). It survives restarts, and
 * removing a Source leaves it as it is. One instance per app.
 */
class ContinueWatching(private val storage: Storage, private val clock: Clock) {

    private var watched: List<Watched> = load()
    private val rowState = MutableStateFlow(rowOf(watched))

    /**
     * The Continue watching row: one card per Title, most recently watched first. A Series shows the episode watched
     * last, and a Title whose latest watch reached the end leaves the row.
     */
    val row: StateFlow<List<Watched>> = rowState.asStateFlow()

    /** Remembers that [watching] has got to [positionMillis] of [durationMillis] (null when the player doesn't know) on [stream]. */
    @Synchronized
    fun save(watching: Watching, stream: Stream, positionMillis: Long, durationMillis: Long?) {
        val entry = Watched(watching, positionMillis, durationMillis, WorkedStream(stream.sourceId, stream.id), clock.nowMillis())
        watched = (listOf(entry) + watched.filterNot { it.watching.reference.isSameAs(watching.reference) }).take(KEPT)
        storage.write(KEY, json.encodeToString(StoredWatched.serializer(), StoredWatched(watched.map(::stored))).encodeToByteArray())
        rowState.value = rowOf(watched)
    }

    /** How far playback got in [reference]'s Title or episode, and on which Stream; null if it hasn't been watched here. */
    @Synchronized
    fun of(reference: TitleReference): Watched? = watched.firstOrNull { it.watching.reference.isSameAs(reference) }

    private fun rowOf(watched: List<Watched>) = watched
        .sortedByDescending { it.watchedAtMillis }
        .distinctBy { it.watching.title.kind to it.watching.title.tmdbId }
        .filterNot { it.isFinished }

    private fun load(): List<Watched> =
        storage.read(KEY)?.let { json.decodeFromString(StoredWatched.serializer(), it.decodeToString()).watched.map(::restored) }.orEmpty()

    private companion object {
        const val KEY = "continue-watching.json"

        /** Titles and episodes remembered, most recently watched first; older ones are forgotten. */
        const val KEPT = 200
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The same Title or episode, whatever else the Catalogue says about it now. */
private fun TitleReference.isSameAs(other: TitleReference) =
    kind == other.kind && tmdbId == other.tmdbId && episode?.season == other.episode?.season && episode?.episode == other.episode?.episode

/** How far playback got in a Title or episode, and on which Stream. */
data class Watched(
    val watching: Watching,
    val positionMillis: Long,
    /** How long it runs, when the player knew. */
    val durationMillis: Long?,
    /** The Stream that last showed a picture for it. */
    val lastWorked: WorkedStream,
    val watchedAtMillis: Long,
) {
    /** How far through it playback got, from 0 to 1, when its duration is known. */
    val progress: Float? get() = durationMillis?.takeIf { it > 0 }?.let { (positionMillis.toFloat() / it).coerceIn(0f, 1f) }

    /** Watched to the end: past 95%, which leaves the credits. */
    val isFinished: Boolean get() = (progress ?: 0f) >= FINISHED_AT

    /** Where playing it again starts: where playback got to, or the beginning once it was watched to the end. */
    val resumeAtMillis: Long get() = if (isFinished) 0 else positionMillis

    private companion object {
        const val FINISHED_AT = 0.95f
    }
}

/** A Stream that worked, known by its Source and its Stream ID within that Source, which outlast its URL. */
data class WorkedStream(val sourceId: SourceId, val streamId: String) {
    fun isOf(stream: Stream) = sourceId == stream.sourceId && streamId == stream.id
}

// The stored shape, kept apart from the domain types so renaming one doesn't lose what a TV has saved.

@Serializable
private data class StoredWatched(val watched: List<StoredEntry>)

@Serializable
private data class StoredEntry(
    val title: StoredTitle,
    val reference: StoredReference,
    val episodeLabel: String? = null,
    val imageUrl: String? = null,
    val positionMillis: Long,
    val durationMillis: Long? = null,
    val sourceId: String,
    val streamId: String,
    val watchedAtMillis: Long,
)

private fun stored(watched: Watched) = with(watched) {
    StoredEntry(
        stored(watching.title), stored(watching.reference), watching.episodeLabel, watching.imageUrl,
        positionMillis, durationMillis, lastWorked.sourceId.value, lastWorked.streamId, watchedAtMillis,
    )
}

private fun restored(entry: StoredEntry) = with(entry) {
    Watched(
        Watching(restored(title), restored(reference), episodeLabel, imageUrl),
        positionMillis, durationMillis, WorkedStream(SourceId(sourceId), streamId), watchedAtMillis,
    )
}
