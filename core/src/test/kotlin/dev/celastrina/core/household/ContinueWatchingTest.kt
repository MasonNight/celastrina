package dev.celastrina.core.household

import dev.celastrina.core.catalogue.AirDate
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.source.Stream
import dev.celastrina.core.testing.VirtualClock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ContinueWatchingTest {

    @TempDir
    lateinit var dir: File

    private val clock = VirtualClock()

    /** Continue watching kept where the app keeps it, so a new one on [dir] is the TV after a restart. */
    private fun continueWatching() = ContinueWatching(FileStorage(dir), clock)

    @Test
    fun `the position playback got to is in Continue watching after a restart, with how far through it is`() {
        continueWatching().save(theMatrix, archiveMp4, positionMillis = 30.minutes.inWholeMilliseconds, durationMillis = 2.hours.inWholeMilliseconds)

        val afterRestart = continueWatching().row.value

        assertEquals(listOf(theMatrix), afterRestart.map { it.watching })
        assertEquals(30.minutes.inWholeMilliseconds, afterRestart.single().positionMillis)
        assertEquals(0.25f, afterRestart.single().progress)
    }

    @Test
    fun `the row shows the most recently watched first, and a Series once, at the episode watched last`() {
        val continueWatching = continueWatching()
        continueWatching.save(breakingBad(1, 2), archiveMp4, positionMillis = 10_000, durationMillis = 2_800_000)
        clock.advanceBy(1.hours)
        continueWatching.save(theMatrix, archiveMp4, positionMillis = 10_000, durationMillis = 8_160_000)
        clock.advanceBy(1.hours)
        continueWatching.save(breakingBad(1, 3), archiveMp4, positionMillis = 10_000, durationMillis = 2_800_000)

        assertEquals(listOf(breakingBad(1, 3), theMatrix), continueWatching.row.value.map { it.watching })
    }

    @Test
    fun `a Title watched to the end leaves the row, and so does a Series whose latest episode was`() {
        val continueWatching = continueWatching()
        continueWatching.save(theMatrix, archiveMp4, positionMillis = 7_800_000, durationMillis = 8_160_000)
        continueWatching.save(breakingBad(1, 2), archiveMp4, positionMillis = 10_000, durationMillis = 2_800_000)
        clock.advanceBy(1.hours)
        continueWatching.save(breakingBad(1, 3), archiveMp4, positionMillis = 2_700_000, durationMillis = 2_800_000)

        assertEquals(emptyList(), continueWatching.row.value)
    }

    @Test
    fun `each Title and episode keeps its own position and the Stream that last worked for it`() {
        val continueWatching = continueWatching()
        continueWatching.save(breakingBad(1, 2), nightly1080, positionMillis = 120_000, durationMillis = 2_800_000)
        continueWatching.save(breakingBad(1, 3), archiveMp4, positionMillis = 300_000, durationMillis = 2_800_000)

        val episode2 = continueWatching().of(breakingBad(1, 2).reference)

        assertEquals(120_000, episode2?.positionMillis)
        assertEquals(WorkedStream("movie-night", "1080"), episode2?.lastWorked)
        assertEquals(WorkedStream("internet-archive", "mp4"), continueWatching().of(breakingBad(1, 3).reference)?.lastWorked)
        assertEquals(null, continueWatching().of(theMatrix.reference))
    }

    @Test
    fun `a Title or episode is known by its kind, TMDB ID and episode number, whatever else the Catalogue says about it later`() {
        val continueWatching = continueWatching()
        continueWatching.save(breakingBad(1, 2), nightly1080, positionMillis = 120_000, durationMillis = 2_800_000)
        val renamed = breakingBad(1, 2).reference.copy(title = "Breaking Bad (Remastered)", alternativeTitles = listOf("BB"), imdbId = null)
        continueWatching.save(breakingBad(1, 2).copy(episodeLabel = "S1 E2 · Cat's in the Bag..."), archiveMp4, positionMillis = 240_000, durationMillis = 2_800_000)

        assertEquals(240_000, continueWatching.of(renamed)?.positionMillis)
        assertEquals(1, continueWatching.row.value.size)
        assertEquals(null, continueWatching.of(renamed.copy(kind = Title.Kind.Movie, episode = null)))
    }

    @Test
    fun `resuming a Title watched to the end starts again from the beginning, on the Stream that last worked`() {
        val continueWatching = continueWatching()
        continueWatching.save(theMatrix, nightly1080, positionMillis = 7_800_000, durationMillis = 8_160_000)

        val watched = continueWatching.of(theMatrix.reference)

        assertEquals(0, watched?.resumeAtMillis)
        assertEquals(WorkedStream("movie-night", "1080"), watched?.lastWorked)
    }

    @Test
    fun `resuming starts where playback got to`() {
        val continueWatching = continueWatching()
        continueWatching.save(theMatrix, nightly1080, positionMillis = 1_800_000, durationMillis = 8_160_000)

        assertEquals(1_800_000, continueWatching.of(theMatrix.reference)?.resumeAtMillis)
    }
}

private val theMatrix = Watching(
    title = Title(603, Title.Kind.Movie, "The Matrix", 1999, "https://image.tmdb.org/t/p/w342/matrix.jpg", category = null),
    reference = TitleReference(Title.Kind.Movie, 603, "tt0133093", "Q83495", "The Matrix", "The Matrix", listOf("Matrix"), 1999),
    episodeLabel = null,
    imageUrl = "https://image.tmdb.org/t/p/w780/matrix-backdrop.jpg",
)

private val breakingBad = Title(1396, Title.Kind.Series, "Breaking Bad", 2008, "https://image.tmdb.org/t/p/w342/bb.jpg", category = null)

private fun breakingBad(season: Int, episode: Int) = Watching(
    title = breakingBad,
    reference = TitleReference(
        Title.Kind.Series, 1396, "tt0903747", "Q1079", "Breaking Bad", "Breaking Bad", emptyList(), 2008,
        TitleReference.Episode(season, episode, absoluteEpisode = (season - 1) * 7 + episode, airDate = AirDate(2008, 1, 20)),
    ),
    episodeLabel = "S$season E$episode",
    imageUrl = "https://image.tmdb.org/t/p/w300/bb-s${season}e$episode.jpg",
)

private fun stream(sourceId: String, id: String) =
    Stream(sourceId = sourceId, id = id, url = "https://$sourceId.example/$id", delivery = Stream.Delivery.Progressive)

private val archiveMp4 = stream("internet-archive", "mp4")

private val nightly1080 = stream("movie-night", "1080")
