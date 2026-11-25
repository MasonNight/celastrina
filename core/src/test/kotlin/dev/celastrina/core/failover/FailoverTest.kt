package dev.celastrina.core.failover

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.SourceLookup
import dev.celastrina.core.source.Stream
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Failover driven the way the Media3 adapter drives it: scripted player events, with the player's position reported
 * about every second, on a virtual clock.
 */
class FailoverTest {

    private val clock = VirtualClock()
    private val logger = RecordingLogger()
    private val everythingDecodes = Decoders { true }

    private val movieNight = TestSource("movie-night", "Movie Night Jellyfin")
    private val archive = TestSource("internet-archive", "Internet Archive")
    private val friends = TestSource("friends", "Friends' Emby")

    private val nightly1080 = movieNight.stream("1080", resolution = 1080)
    private val nightly720 = movieNight.stream("720", resolution = 720)
    private val archiveMp4 = archive.stream("mp4", resolution = 480)
    private val archiveOgv = archive.stream("ogv", resolution = 360)
    private val friendsHevc = friends.stream("hevc", resolution = 2160, videoCodec = "hevc")

    // Candidate order

    @Test
    fun `a failing Stream moves to the next in the Source order, then the next Source's own ranking`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv)))

        assertSwitchedTo(nightly720, failover.fail())
        assertSwitchedTo(archiveMp4, failover.fail())
        assertSwitchedTo(archiveOgv, failover.fail())
    }

    @Test
    fun `quality isn't compared across Sources`() {
        val failover = playing(nightly720, lookup(movieNight to listOf(nightly720), friends to listOf(friendsHevc), archive to listOf(archiveMp4)))

        assertSwitchedTo(friendsHevc, failover.fail())
    }

    @Test
    fun `Streams the TV can't decode are left out`() {
        val h264Only = Decoders { mimeType -> mimeType == "video/avc" }
        val archiveH264 = archive.stream("h264", videoCodec = "h264")
        val archiveUnknown = archive.stream("unknown", videoCodec = null)
        val failover = playing(
            nightly1080,
            lookup(movieNight to listOf(nightly1080), friends to listOf(friendsHevc), archive to listOf(archiveH264, archiveUnknown)),
            decoders = h264Only,
        )

        assertSwitchedTo(archiveH264, failover.fail())
        // A Stream that doesn't say its codec might play, so it stays in.
        assertSwitchedTo(archiveUnknown, failover.fail())
    }

    @Test
    fun `Play's automatic choice is the first candidate, skipping Streams the TV can't decode`() {
        val noHevc = Decoders { it != "video/hevc" }

        assertEquals(nightly1080, lookup(friends to listOf(friendsHevc), movieNight to listOf(nightly1080, nightly720)).candidates(noHevc).first())
    }

    @Test
    fun `codec names are understood however a Source spells them`() {
        val spellings = mapOf(
            "video/avc" to listOf("h264", "AVC", "avc1", "x264"),
            "video/hevc" to listOf("hevc", "H265", "x265", "hvc1"),
            "video/x-vnd.on2.vp9" to listOf("vp9"),
            "video/av01" to listOf("av1"),
            "video/mp4v-es" to listOf("mpeg4", "xvid"),
            "video/mpeg2" to listOf("mpeg2video"),
        )

        spellings.forEach { (mimeType, names) ->
            val onlyThis = Decoders { it == mimeType }
            names.forEach { name -> assertTrue(archive.stream(name, videoCodec = name).isDecodableBy(onlyThis), "$name isn't $mimeType") }
        }
        assertEquals(false, archive.stream("mpeg2", videoCodec = "mpeg2video").isDecodableBy(Decoders { false }))
    }

    @Test
    fun `a Stream earlier in the order than the one that failed is tried first`() {
        val failover = playing(archiveOgv, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))

        assertSwitchedTo(nightly1080, failover.fail())
        assertSwitchedTo(archiveMp4, failover.fail())
    }

    @Test
    fun `no Stream is tried twice, and when none is left Failover says so`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        assertSwitchedTo(archiveMp4, failover.fail())
        val step = failover.fail()

        assertIs<FailoverStep.OutOfStreams>(step)
        assertEquals("Internet Archive isn't working, and there's no other Stream to try", step.notice.text)
    }

    // When a Stream counts as not working

    @Test
    fun `a Stream that shows no first frame within 15 s of being chosen fails`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)), started = false)

        assertNull(failover.report(14.seconds))
        val step = failover.report(1.seconds)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(StreamFailure.NoFirstFrame, step.notice.reason)
    }

    @Test
    fun `a Stream that shows its first frame in time keeps playing`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)), started = false)

        failover.report(14.seconds)
        assertNull(failover.on(PlayerEvent.FirstFrame))
        assertNull(failover.report(10.minutes))
    }

    @Test
    fun `the 15 s start over for each Stream tried`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)), started = false)

        failover.report(15.seconds)
        assertNull(failover.report(14.seconds))
        assertSwitchedTo(archiveOgv, failover.report(1.seconds))
    }

    @Test
    fun `an HTTP error, network error, unsupported format or decoder error the player reports fails the Stream`() {
        listOf(PlayerError.Http, PlayerError.Network, PlayerError.UnsupportedFormat, PlayerError.Decoder, PlayerError.Other).forEach { error ->
            val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

            val step = failover.on(PlayerEvent.Failed(error))

            assertSwitchedTo(archiveMp4, step)
            assertEquals(StreamFailure.Error(error), step.notice.reason)
        }
    }

    @Test
    fun `a stall over 20 s fails the Stream`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(1.minutes)

        failover.on(PlayerEvent.Buffering)
        assertNull(failover.report(20.seconds))
        val step = failover.report(1.seconds)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(StreamFailure.LongStall, step.notice.reason)
    }

    @Test
    fun `a stall that ends within 20 s is forgiven`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Buffering)
        failover.report(19.seconds)
        failover.on(PlayerEvent.Playing)

        assertNull(failover.report(10.minutes))
    }

    @Test
    fun `3 stalls within 2 minutes fail the Stream`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        assertNull(failover.stall(5.seconds))
        failover.report(50.seconds)
        assertNull(failover.stall(5.seconds))
        failover.report(50.seconds)
        val step = failover.on(PlayerEvent.Buffering)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(StreamFailure.RepeatedStalls, step.notice.reason)
    }

    @Test
    fun `3 stalls spread over more than 2 minutes are forgiven`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.stall(5.seconds)
        failover.report(60.seconds)
        failover.stall(5.seconds)
        failover.report(60.seconds)

        assertNull(failover.stall(5.seconds))
    }

    @Test
    fun `pausing doesn't count as a stall`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Paused)
        assertNull(failover.report(30.minutes))
        failover.on(PlayerEvent.Playing)

        assertNull(failover.report(1.minutes))
    }

    @Test
    fun `pausing in the middle of a stall stops it counting`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Buffering)
        failover.report(10.seconds)
        failover.on(PlayerEvent.Paused)

        assertNull(failover.report(5.minutes))
    }

    @Test
    fun `buffering after a seek isn't a stall, however long or often`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        repeat(3) {
            failover.on(PlayerEvent.Seeked)
            failover.on(PlayerEvent.Buffering)
            assertNull(failover.report(25.seconds))
            failover.on(PlayerEvent.Playing)
        }
    }

    @Test
    fun `buffering once playback has picked up again after a seek is a stall`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Seeked)
        failover.on(PlayerEvent.Buffering)
        failover.on(PlayerEvent.Playing)
        failover.on(PlayerEvent.Buffering)

        assertSwitchedTo(archiveMp4, failover.report(21.seconds))
    }

    @Test
    fun `stalls on one Stream aren't held against the next`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))
        failover.stall(5.seconds)
        failover.stall(5.seconds)
        failover.fail()

        assertNull(failover.stall(5.seconds))
    }

    // Where playback resumes

    @Test
    fun `the next Stream resumes about 5 s before the failure point`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(42.minutes)

        val step = failover.on(PlayerEvent.Failed(PlayerError.Http))

        assertSwitchedTo(archiveMp4, step)
        assertEquals((42.minutes - 5.seconds).inWholeMilliseconds, step.startAtMillis)
    }

    @Test
    fun `a Stream that fails in its first 5 s is replaced from the start`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(3.seconds)

        assertEquals(0, (failover.fail() as FailoverStep.Switch).startAtMillis)
    }

    @Test
    fun `when the replacement never starts either, the next one resumes from the original failure point`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))
        failover.report(10.minutes)
        failover.on(PlayerEvent.Failed(PlayerError.Http))

        val step = failover.report(15.seconds, started = false)

        assertSwitchedTo(archiveOgv, step)
        assertEquals((10.minutes - 5.seconds).inWholeMilliseconds, step.startAtMillis)
    }

    @Test
    fun `playback resumed partway, as from Continue watching, fails over from where it got to`() {
        val failover = Failover(
            lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)),
            nightly1080,
            handPicked = false,
            decoders = everythingDecodes,
            clock = clock,
            logger = logger,
            startAtMillis = 20.minutes.inWholeMilliseconds,
        )

        val step = failover.report(15.seconds, started = false)

        assertEquals((20.minutes - 5.seconds).inWholeMilliseconds, (step as FailoverStep.Switch).startAtMillis)
    }

    // What the Household sees

    @Test
    fun `the notice names the Source that failed and the one tried next`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))

        assertEquals("Movie Night Jellyfin isn't working, trying Internet Archive…", failover.fail()!!.notice.text)
        assertEquals("This Stream from Internet Archive isn't working, trying another from Internet Archive…", failover.fail()!!.notice.text)
    }

    @Test
    fun `a hand-picked Stream fails over too, and the notice says playback moved away from the Household's choice`() {
        val failover = playing(archiveOgv, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)), handPicked = true)

        val first = failover.fail()!!
        assertSwitchedTo(nightly1080, first)
        assertTrue(first.notice.awayFromHouseholdsChoice)
        assertEquals("The Stream you chose from Internet Archive isn't working, trying Movie Night Jellyfin…", first.notice.text)

        // Once playback has left the Household's choice, later Failovers are ordinary ones.
        val second = failover.fail()!!
        assertEquals(false, second.notice.awayFromHouseholdsChoice)
        assertEquals("Movie Night Jellyfin isn't working, trying Internet Archive…", second.notice.text)
    }

    @Test
    fun `each Failover is logged with what failed and why`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Failed(PlayerError.Http))

        assertTrue(logger.messages.any { "movie-night" in it && "1080" in it && "Http" in it && "internet-archive" in it }, "${logger.messages}")
    }

    // Streams that arrive late

    @Test
    fun `Streams that arrive late join the candidates in their Source's place`() {
        val failover = playing(archiveMp4, lookup(movieNight to null, archive to listOf(archiveMp4, archiveOgv)))

        assertNull(failover.lookupChanged(lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv))))

        assertSwitchedTo(nightly1080, failover.fail())
    }

    @Test
    fun `with nothing left to try while Sources are still being asked, Failover waits for their Streams`() {
        val failover = playing(archiveMp4, lookup(movieNight to null, friends to null, archive to listOf(archiveMp4)))

        val waiting = failover.fail()
        assertIs<FailoverStep.Waiting>(waiting)
        assertEquals("Internet Archive isn't working, waiting for your other Sources…", waiting.notice.text)

        assertNull(failover.lookupChanged(lookup(movieNight to LookupOutcome.NotAvailable, friends to null, archive to listOf(archiveMp4))))
        val step = failover.lookupChanged(lookup(movieNight to LookupOutcome.NotAvailable, friends to listOf(friendsHevc), archive to listOf(archiveMp4)))

        assertSwitchedTo(friendsHevc, step)
        assertEquals("Internet Archive isn't working, trying Friends' Emby…", step.notice.text)
    }

    @Test
    fun `when the last Source answers with nothing new, Failover is out of Streams`() {
        val failover = playing(archiveMp4, lookup(movieNight to null, archive to listOf(archiveMp4)))
        failover.fail()

        val step = failover.lookupChanged(lookup(movieNight to LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable), archive to listOf(archiveMp4)))

        assertIs<FailoverStep.OutOfStreams>(step)
    }

    @Test
    fun `the Stream playing is the one Failover moved to`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        assertEquals(nightly1080, failover.playing)

        failover.fail()

        assertEquals(archiveMp4, failover.playing)
    }

    // Scripting the player

    private fun playing(
        stream: Stream,
        lookup: Lookup,
        handPicked: Boolean = false,
        decoders: Decoders = everythingDecodes,
        started: Boolean = true,
    ): Failover = Failover(lookup, stream, handPicked, decoders, clock, logger).also {
        if (started) it.on(PlayerEvent.FirstFrame)
    }

    /** Lets [duration] pass with the player reporting its position each second. Returns the first step Failover took. */
    private fun Failover.report(duration: Duration, started: Boolean = true): FailoverStep? {
        repeat(duration.inWholeSeconds.toInt()) {
            clock.advanceBy(1.seconds)
            position += if (started) 1_000 else 0
            on(PlayerEvent.Position(position))?.let { return it }
        }
        return null
    }

    private var position = 0L

    /** A stall that lasts [duration] and then ends. */
    private fun Failover.stall(duration: Duration): FailoverStep? {
        on(PlayerEvent.Buffering)?.let { return it }
        report(duration, started = false)?.let { return it }
        return on(PlayerEvent.Playing)
    }

    /** The current Stream fails at once, and the next one, if any, shows its first frame. */
    private fun Failover.fail(): FailoverStep? = on(PlayerEvent.Failed(PlayerError.Http)).also {
        if (it is FailoverStep.Switch) {
            position = it.startAtMillis
            on(PlayerEvent.FirstFrame)
        }
    }

    private fun lookup(vararg sources: Pair<Source, Any?>) = Lookup(
        sources.map { (source, answer) ->
            SourceLookup(
                source,
                when (answer) {
                    null -> null
                    is LookupOutcome -> answer
                    is List<*> -> LookupOutcome.Streams(answer.filterIsInstance<Stream>())
                    else -> error("Not an answer: $answer")
                },
            )
        },
    )

    private class TestSource(override val id: String, override val name: String) : Source {
        override val kind = SourceKind(name, setOf(IdScheme.Tmdb), setOf(Title.Kind.Movie))
        override suspend fun lookUp(title: TitleReference) = LookupOutcome.NotAvailable

        fun stream(id: String, resolution: Int? = null, videoCodec: String? = "h264") = Stream(
            sourceId = this.id,
            id = id,
            url = "https://${this.id}.example/$id",
            delivery = Stream.Delivery.Progressive,
            resolution = resolution,
            videoCodec = videoCodec,
        )
    }
}

@OptIn(ExperimentalContracts::class)
private fun assertSwitchedTo(expected: Stream, step: FailoverStep?) {
    contract { returns() implies (step is FailoverStep.Switch) }
    assertIs<FailoverStep.Switch>(step, "Expected a switch to ${expected.id}")
    assertEquals(expected, step.stream)
}
