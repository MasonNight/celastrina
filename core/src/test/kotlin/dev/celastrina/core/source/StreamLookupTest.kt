package dev.celastrina.core.source

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamLookupTest {

    private val nightOfTheLivingDead = TitleReference(
        kind = Title.Kind.Movie,
        tmdbId = 10331,
        imdbId = "tt0063350",
        wikidataId = "Q623051",
        title = "Night of the Living Dead",
        originalTitle = "Night of the Living Dead",
        alternativeTitles = emptyList(),
        year = 1968,
    )

    private val moviesOnly = SourceKind("Movie shelf", idSchemes = setOf(IdScheme.Tmdb), titleKinds = setOf(Title.Kind.Movie))

    @Test
    fun `a Source that can't match the Title isn't asked`() = runTest {
        val movieShelf = FakeSource("movie-shelf", moviesOnly) { LookupOutcome.NotAvailable }
        val imdbOnly = FakeSource("imdb-only", SourceKind("IMDb only", setOf(IdScheme.Imdb), setOf(Title.Kind.Movie, Title.Kind.Series))) {
            LookupOutcome.NotAvailable
        }
        val lookup = StreamLookup(listOf(movieShelf, imdbOnly), RecordingLogger())

        val answers = lookup.lookUp(nightOfTheLivingDead.copy(kind = Title.Kind.Series, imdbId = null)).toList()

        assertEquals(emptyList(), answers)
        assertEquals(0, movieShelf.timesAsked + imdbOnly.timesAsked)
    }

    @Test
    fun `DRM, torrent, external-app, Usenet and non-http Streams are dropped and logged`() = runTest {
        val playable = listOf(
            stream("mp4", "https://example.org/film.mp4", Stream.Delivery.Progressive),
            stream("hls", "http://example.org/film.m3u8", Stream.Delivery.Hls),
            stream("dash", "https://example.org/film.mpd", Stream.Delivery.Dash),
        )
        val unplayable = listOf(
            stream("widevine", "https://example.org/locked.mpd", Stream.Delivery.Dash).copy(drm = true),
            stream("torrent", "magnet:?xt=urn:btih:0123456789abcdef", Stream.Delivery.Torrent),
            stream("external", "https://example.org/watch", Stream.Delivery.ExternalApp),
            stream("usenet", "https://example.org/film.nzb", Stream.Delivery.Usenet),
            stream("ftp", "ftp://example.org/film.mp4", Stream.Delivery.Progressive),
        )
        val logger = RecordingLogger()
        val source = FakeSource("movie-shelf", moviesOnly) { LookupOutcome.Streams(unplayable + playable) }

        val answer = StreamLookup(listOf(source), logger).lookUp(nightOfTheLivingDead).first()

        assertEquals(LookupOutcome.Streams(playable), answer.outcome)
        assertEquals(5, logger.messages.size)
        listOf("widevine", "torrent", "external", "usenet", "ftp").forEach { id ->
            assertTrue(logger.messages.any { "movie-shelf" in it && id in it }, "Dropping $id isn't logged: ${logger.messages}")
        }
    }

    @Test
    fun `a Source with only unplayable Streams answers Not available`() = runTest {
        val source = FakeSource("movie-shelf", moviesOnly) {
            LookupOutcome.Streams(listOf(stream("torrent", "magnet:?xt=urn:btih:0123456789abcdef", Stream.Delivery.Torrent)))
        }

        val answer = StreamLookup(listOf(source), RecordingLogger()).lookUp(nightOfTheLivingDead).first()

        assertEquals(LookupOutcome.NotAvailable, answer.outcome)
    }

    @Test
    fun `a Source that throws answers source_error`() = runTest {
        val source = FakeSource("movie-shelf", moviesOnly) { error("Unexpected page layout") }

        val answer = StreamLookup(listOf(source), RecordingLogger()).lookUp(nightOfTheLivingDead).first()

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.SourceError), answer.outcome)
    }

    @Test
    fun `Streams arrive as soon as one Source answers, without waiting for slower Sources`() = runTest {
        val slowAnswer = CompletableDeferred<LookupOutcome>()
        val slow = FakeSource("slow", moviesOnly) { slowAnswer.await() }
        val quick = FakeSource("quick", moviesOnly) {
            LookupOutcome.Streams(listOf(stream("mp4", "https://example.org/film.mp4", Stream.Delivery.Progressive)))
        }

        val first = StreamLookup(listOf(slow, quick), RecordingLogger()).lookUp(nightOfTheLivingDead).first()

        assertEquals("quick", first.source.id)
        assertTrue(first.outcome is LookupOutcome.Streams)
    }

    private fun stream(id: String, url: String, delivery: Stream.Delivery) =
        Stream(sourceId = "movie-shelf", id = id, url = url, delivery = delivery)

    private class FakeSource(
        override val id: String,
        override val kind: SourceKind,
        private val answer: suspend () -> LookupOutcome,
    ) : Source {
        override val name = id
        var timesAsked = 0

        override suspend fun lookUp(title: TitleReference): LookupOutcome {
            timesAsked++
            return answer()
        }
    }
}
