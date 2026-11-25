package dev.celastrina.core.source

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

        assertEquals("quick", first.source.id.value)
        assertTrue(first.outcome is LookupOutcome.Streams)
    }

    @Test
    fun `a Source that hasn't answered after 8 seconds is unreachable, and the others still answer`() = runTest {
        val hangs = FakeSource("hangs", moviesOnly) { awaitCancellation() }
        val slowButInTime = FakeSource("slow", moviesOnly) {
            delay(7.seconds)
            LookupOutcome.NotAvailable
        }

        val answers = StreamLookup(listOf(hangs, slowButInTime), RecordingLogger()).lookUp(nightOfTheLivingDead).toList()

        assertEquals(
            listOf("slow" to LookupOutcome.NotAvailable, "hangs" to LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable)),
            answers.map { it.source.id.value to it.outcome },
        )
        assertEquals(8.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `while a Lookup runs, every Source asked shows as still asking until it answers`() = runTest {
        val slowAnswer = CompletableDeferred<LookupOutcome>()
        val first = FakeSource("first", moviesOnly) { slowAnswer.await() }
        val second = FakeSource("second", moviesOnly) { LookupOutcome.NotAvailable }
        val seriesOnly = FakeSource("series-only", SourceKind("Series shelf", setOf(IdScheme.Tmdb), setOf(Title.Kind.Series))) { LookupOutcome.NotAvailable }
        val progress = mutableListOf<Lookup>()

        StreamLookup(listOf(first, second, seriesOnly), RecordingLogger()).watch(nightOfTheLivingDead).collect {
            progress += it
            if (!it.isFinished && it.sources.count { s -> s.isLooking } == 1) slowAnswer.complete(LookupOutcome.NotAvailable)
        }

        assertEquals(listOf(listOf(true, true), listOf(true, false), listOf(false, false)), progress.map { l -> l.sources.map { it.isLooking } })
        assertEquals(listOf("first", "second"), progress.last().sources.map { it.source.id.value })
    }

    @Test
    fun `Streams are in the Household's Source order, then each Source's own order, whatever order they arrive in`() = runTest {
        val preferred = FakeSource("preferred", moviesOnly) {
            delay(2.seconds)
            LookupOutcome.Streams(listOf(stream("p1", "https://a.example/1.mp4", Stream.Delivery.Progressive), stream("p2", "https://a.example/2.mp4", Stream.Delivery.Progressive)))
        }
        val other = FakeSource("other", moviesOnly) {
            LookupOutcome.Streams(listOf(stream("o1", "https://b.example/1.mp4", Stream.Delivery.Progressive)))
        }

        val lookup = StreamLookup(listOf(preferred, other), RecordingLogger()).watch(nightOfTheLivingDead).last()

        assertEquals(listOf("p1", "p2", "o1"), lookup.streams.map { it.id })
    }

    @Test
    fun `a Lookup knows when it was asked`() = runTest {
        val clock = VirtualClock()
        val source = FakeSource("movie-shelf", moviesOnly) { LookupOutcome.NotAvailable }

        val lookup = StreamLookup(listOf(source), RecordingLogger(), clock).watch(nightOfTheLivingDead).last()

        assertEquals(clock.nowMillis(), lookup.askedAtMillis)
    }

    @Test
    fun `asking the Sources again keeps each one's Streams listed until it answers again`() = runTest {
        val clock = VirtualClock()
        val old = stream("old", "https://example.org/film.mp4?token=old", Stream.Delivery.Progressive)
        val fresh = stream("fresh", "https://example.org/film.mp4?token=fresh", Stream.Delivery.Progressive)
        var round = 0
        val freshAnswer = CompletableDeferred<LookupOutcome>()
        val shelf = FakeSource("movie-shelf", moviesOnly) { if (round == 0) LookupOutcome.Streams(listOf(old)) else freshAnswer.await() }
        val other = FakeSource("other", moviesOnly) { LookupOutcome.NotAvailable }
        val streamLookup = StreamLookup(listOf(shelf, other), RecordingLogger(), clock)
        val previous = streamLookup.watch(nightOfTheLivingDead).last()
        round = 1
        clock.advanceBy(31.minutes)
        val progress = mutableListOf<Lookup>()

        streamLookup.watch(nightOfTheLivingDead, previous).collect {
            progress += it
            if (it.sources.count { s -> s.isLooking } == 1) freshAnswer.complete(LookupOutcome.Streams(listOf(fresh)))
        }

        assertEquals(listOf(listOf(true, true), listOf(true, false), listOf(false, false)), progress.map { l -> l.sources.map { it.isLooking } })
        assertEquals(listOf(listOf(old), listOf(old), listOf(fresh)), progress.map { it.streams })
        assertEquals(LookupStatus.Found(streams = 1, fromSources = 1, bestResolution = null, stillAsking = 2), progress.first().status)
        assertTrue(progress.all { it.askedAtMillis == clock.nowMillis() })
        assertEquals(2, shelf.timesAsked)
    }

    @Test
    fun `the status counts Streams and Sources, gives the best quality and says how many are still being asked`() {
        val a = FakeSource("a", moviesOnly) { LookupOutcome.NotAvailable }
        val b = FakeSource("b", moviesOnly) { LookupOutcome.NotAvailable }
        val c = FakeSource("c", moviesOnly) { LookupOutcome.NotAvailable }
        fun streams(vararg resolutions: Int?) =
            LookupOutcome.Streams(resolutions.mapIndexed { i, r -> stream("s$i", "https://a.example/$i.mp4", Stream.Delivery.Progressive).copy(resolution = r) })
        fun lookup(vararg answers: Pair<Source, LookupOutcome?>) = Lookup(answers.map { (source, outcome) -> SourceLookup(source, outcome) })
        val failed = LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable)

        assertEquals(LookupStatus.Found(streams = 3, fromSources = 2, bestResolution = 720, stillAsking = 1), lookup(a to streams(720, 480), b to streams(null), c to null).status)
        assertEquals(LookupStatus.Found(streams = 1, fromSources = 1, bestResolution = 2160, stillAsking = 0), lookup(a to streams(2160), b to LookupOutcome.NotAvailable).status)
        assertEquals(LookupStatus.Found(streams = 1, fromSources = 1, bestResolution = null, stillAsking = 0), lookup(a to streams(null)).status)
        assertEquals(LookupStatus.Asking(sources = 2), lookup(a to null, b to null, c to LookupOutcome.NotAvailable).status)
        assertEquals(LookupStatus.NotAvailable, lookup(a to LookupOutcome.NotAvailable, b to LookupOutcome.NotAvailable).status)
        assertEquals(LookupStatus.NoStreams(notAvailable = 1, failed = 2), lookup(a to LookupOutcome.NotAvailable, b to failed, c to failed).status)
        assertEquals(LookupStatus.NoSourceCanLook, lookup().status)
    }

    private fun stream(id: String, url: String, delivery: Stream.Delivery) =
        Stream(sourceId = SourceId("movie-shelf"), id = id, url = url, delivery = delivery)

    private class FakeSource(
        name: String,
        override val kind: SourceKind,
        private val answer: suspend () -> LookupOutcome,
    ) : Source {
        override val id = SourceId(name)
        override val name = name
        var timesAsked = 0

        override suspend fun lookUp(title: TitleReference): LookupOutcome {
            timesAsked++
            return answer()
        }
    }
}
