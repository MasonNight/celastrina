package dev.celastrina.core.failover

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.SourceLookup
import dev.celastrina.core.source.Stream
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
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

    @TempDir
    lateinit var dir: File

    /** This TV's Failover memory, kept where the app keeps it, so a new [FailoverMemory] on [dir] is the TV after a restart. */
    private val memory by lazy { FailoverMemory(FileStorage(dir), clock) }
    private var sourcesAskedAgain = 0

    private val movieNight = TestSource("movie-night", "Movie Night Jellyfin")
    private val archive = TestSource("internet-archive", "Internet Archive")
    private val friends = TestSource("friends", "Friends' Emby")

    private val nightly1080 = movieNight.stream("1080", resolution = 1080)
    private val nightly720 = movieNight.stream("720", resolution = 720)
    private val archiveMp4 = archive.stream("mp4", resolution = 480)
    private val archiveOgv = archive.stream("ogv", resolution = 360)
    private val friendsHevc = friends.stream("hevc", resolution = 2160, videoCodec = "hevc")

    @Test
    fun `Failover checks up to two upcoming unknown Candidates and asks again when it moves on`() {
        val first = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val second = archiveMp4.copy(delivery = Stream.Delivery.Unknown)
        val third = archiveOgv.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(nightly1080, first), archive to listOf(second, archive.stream("known"), third))
        val failover = playing(nightly1080, lookup, started = false)

        assertEquals(listOf(first, second), failover.backgroundAddressChecks)
        assertSwitchedTo(first, failover.failUnstarted())
        assertEquals(listOf(second, third), failover.backgroundAddressChecks)
    }

    @Test
    fun `background Address checks follow the current Candidate order as Sources answer and checks finish`() {
        val first = movieNight.stream("first").copy(delivery = Stream.Delivery.Unknown)
        val second = movieNight.stream("second").copy(delivery = Stream.Delivery.Unknown)
        val third = archive.stream("third").copy(delivery = Stream.Delivery.Unknown)
        val fourth = archive.stream("fourth").copy(delivery = Stream.Delivery.Unknown)
        val fifth = archive.stream("fifth").copy(delivery = Stream.Delivery.Unknown)
        val failover = playing(
            archiveMp4,
            lookup(movieNight to null, archive to listOf(archiveMp4, third, fourth, fifth)),
            started = false,
        )
        assertEquals(listOf(third, fourth), failover.backgroundAddressChecks)

        val updated = lookup(movieNight to listOf(first, second), archive to listOf(archiveMp4, third, fourth, fifth))
        assertNull(failover.lookupChanged(updated))
        assertEquals(listOf(first, second), failover.backgroundAddressChecks)

        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(first, PlayerEvent.AddressCheckHttpError(404))))
        assertEquals(listOf(second, third), failover.backgroundAddressChecks)
        val answer = PlayerEvent.AddressChecked("https://video.example/watch", Stream.Delivery.Hls)
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(third, answer)))
        assertEquals(listOf(second, fourth), failover.backgroundAddressChecks)

        assertSwitchedTo(second, failover.failUnstarted())
        assertEquals(listOf(fourth, fifth), failover.backgroundAddressChecks)
    }

    @Test
    fun `Failover skips Candidates found dead while the current Stream loads and remembers only permanent failures`() {
        val permanent = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val passing = archiveMp4.copy(delivery = Stream.Delivery.Unknown)
        val next = friendsHevc.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(nightly1080, permanent), archive to listOf(passing, archiveOgv), friends to listOf(next))

        for (answer in listOf(PlayerEvent.AddressCheckHttpError(503), PlayerEvent.AddressCheckTimedOut)) {
            val failover = playing(nightly1080, lookup, started = false)
            val checks = failover.backgroundAddressChecks
            assertEquals(setOf(permanent, passing), checks.toSet())
            assertNull(failover.on(PlayerEvent.CandidateAddressChecked(permanent, PlayerEvent.AddressCheckHttpError(404))))
            assertNull(failover.on(PlayerEvent.CandidateAddressChecked(passing, answer)))
            assertEquals(listOf(next), failover.backgroundAddressChecks)
            assertEquals(listOf(permanent), lookup.candidates(everythingDecodes, memory).takeLast(1))

            val expected = if (answer == PlayerEvent.AddressCheckTimedOut) archiveOgv else next
            assertSwitchedTo(expected, failover.failUnstarted())
            clock.advanceBy(25.hours)
        }
    }

    @Test
    fun `Failover plays a Candidate checked alive from its final address without another check`() {
        val unknown = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(nightly1080, unknown))
        val failover = playing(nightly1080, lookup, started = false)
        val answer = PlayerEvent.AddressChecked("https://video.example/watch", Stream.Delivery.Hls)
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(unknown, answer)))
        assertTrue(failover.backgroundAddressChecks.isEmpty())

        val resolved = unknown.copy(url = answer.url, delivery = answer.delivery)
        assertSwitchedTo(resolved, failover.failUnstarted())
        assertNull(failover.addressCheck)
        assertNull(failover.on(PlayerEvent.FirstFrame))
        assertEquals(resolved, failover.worked)
    }

    @Test
    fun `giving up lists background Address check failures under their Sources`() {
        val unknown = archiveMp4.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(unknown))
        val failover = playing(nightly1080, lookup, started = false)
        val notFound = PlayerEvent.AddressCheckHttpError(404)
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(unknown, notFound)))
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(unknown, notFound)))

        val gaveUp = assertIs<FailoverStep.GaveUp>(failover.failUnstarted())
        assertEquals(
            SourceSummary(archive.name, SourceSummary.State.StreamsFailed(listOf(FailoverReason.Fault(PlayerFault.Http(404))), 0)),
            gaveUp.sources.last(),
        )
    }

    @Test
    fun `an in-flight background check can answer for the Candidate now chosen to play`() {
        val unknown = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(nightly1080, unknown))
        val failover = playing(nightly1080, lookup, started = false)
        assertEquals(listOf(unknown), failover.backgroundAddressChecks)
        assertSwitchedTo(unknown, failover.failUnstarted())
        assertEquals(unknown, failover.addressCheck)

        val answer = PlayerEvent.AddressChecked("https://video.example/watch", Stream.Delivery.Hls)
        val play = assertIs<FailoverStep.Play>(failover.on(PlayerEvent.CandidateAddressChecked(unknown, answer)))
        assertEquals(answer.url, play.stream.url)
    }

    @Test
    fun `only an unknown Stream is checked and it plays from the settled address`() {
        val unknown = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(unknown, nightly720))
        assertNull(playing(nightly720, lookup, started = false).addressCheck)

        val failover = playing(unknown, lookup, started = false)
        assertEquals(unknown, failover.addressCheck)
        assertNull(failover.report(9.seconds, started = false))
        val finalUrl = "https://video.example/watch"
        val play = assertIs<FailoverStep.Play>(failover.on(PlayerEvent.AddressChecked(finalUrl, Stream.Delivery.Hls)))
        assertEquals(unknown.copy(url = finalUrl, delivery = Stream.Delivery.Hls), play.stream)
        assertNull(failover.addressCheck)
        assertNull(failover.report(14.seconds, started = false))
        assertNull(failover.on(PlayerEvent.FirstFrame))
        assertEquals(play.stream, failover.worked)
    }

    @Test
    fun `an Address check still waiting after 10 s skips just that Stream for this playback`() {
        val slow = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(slow, nightly720))
        val failover = playing(slow, lookup, handPicked = true, started = false)
        assertEquals(slow, failover.addressCheck)
        assertNull(failover.report(9.seconds, started = false))

        clock.advanceBy(1_001.milliseconds)
        val step = failover.on(PlayerEvent.Position(0))
        assertSwitchedTo(nightly720, step)
        assertTrue(step.notice.awayFromHouseholdsChoice)
        assertEquals(slow, lookup.candidates(everythingDecodes, memory).first())
    }

    @Test
    fun `an HTTP error from the Address check has the same classification as a player failure`() {
        for (status in listOf(429, 503, 404)) {
            val checked = nightly1080.copy(id = "checked-$status", delivery = Stream.Delivery.Unknown)
            val sameHost = nightly720.copy(id = "same-$status")
            val redirectedHost = archiveOgv.copy(url = "https://video.example/other")
            val lookup = lookup(movieNight to listOf(checked, sameHost), archive to listOf(redirectedHost, archiveMp4))
            val failover = playing(checked, lookup, started = false)
            val next = failover.on(PlayerEvent.AddressCheckHttpError(status, "https://video.example/watch"))

            assertSwitchedTo(sameHost, next)
            assertEquals(if (status == 404) sameHost else checked, lookup.candidates(everythingDecodes, memory).first())
            assertEquals(FailoverReason.Fault(PlayerFault.Http(status)), next.notice.reason)
        }
    }

    @Test
    fun `Candidates sharing a redirector can still play after one resolved server is rate limited`() {
        val first = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val sameServer = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val otherServer = movieNight.stream("other-server").copy(delivery = Stream.Delivery.Unknown)
        val failover = playing(first, lookup(movieNight to listOf(first, sameServer, otherServer)), started = false)
        val busy = PlayerEvent.AddressChecked("https://busy.example/first", Stream.Delivery.Hls)
        val alsoBusy = PlayerEvent.AddressChecked("https://busy.example/second", Stream.Delivery.Hls)
        val available = PlayerEvent.AddressChecked("https://available.example/third", Stream.Delivery.Hls)

        failover.on(busy)
        failover.on(PlayerEvent.CandidateAddressChecked(sameServer, alsoBusy))
        failover.on(PlayerEvent.CandidateAddressChecked(otherServer, available))

        assertSwitchedTo(otherServer.copy(url = available.url, delivery = available.delivery), failover.failUnstarted(PlayerFault.Http(429)))
    }

    @Test
    fun `a redirected Stream's passing HTTP failure skips its resolved server for this playback`() {
        val unknown = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val redirectedHost = archiveMp4.copy(url = "https://video.example/other")
        for (status in listOf(429, 503)) {
            val sameOrigin = nightly720.copy(id = "same-origin-$status")
            val lookup = lookup(movieNight to listOf(unknown, sameOrigin), archive to listOf(redirectedHost, archiveOgv))
            val failover = playing(unknown, lookup, started = false)
            failover.on(PlayerEvent.AddressChecked("https://video.example/watch", Stream.Delivery.Hls))

            assertSwitchedTo(sameOrigin, failover.failUnstarted(PlayerFault.Http(status)))
            assertSwitchedTo(archiveOgv, failover.failUnstarted())
            assertEquals(unknown, lookup.candidates(everythingDecodes, memory).first())
        }
    }

    @Test
    fun `a background HTTP failure also skips Candidates already checked on its resolved host`() {
        val passing = nightly720.copy(delivery = Stream.Delivery.Unknown)
        val sameOrigin = movieNight.stream("same-origin")
        val alreadyChecked = archiveMp4.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(
            movieNight to listOf(nightly1080, passing, sameOrigin),
            archive to listOf(alreadyChecked, archiveOgv),
        )
        val failover = playing(nightly1080, lookup, started = false)
        val resolved = PlayerEvent.AddressChecked("https://video.example/live", Stream.Delivery.Hls)
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(alreadyChecked, resolved)))
        val busy = PlayerEvent.AddressCheckHttpError(503, "https://video.example/busy")
        assertNull(failover.on(PlayerEvent.CandidateAddressChecked(passing, busy)))

        assertSwitchedTo(sameOrigin, failover.failUnstarted())
        assertSwitchedTo(archiveOgv, failover.failUnstarted())
    }

    @Test
    fun `an inconclusive Address check plays the original Stream with a full 15 s for its first frame`() {
        val unknown = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val lookup = lookup(movieNight to listOf(unknown, nightly720))
        val failover = playing(unknown, lookup, started = false)
        assertNull(failover.report(9.seconds, started = false))

        val play = assertIs<FailoverStep.Play>(failover.on(PlayerEvent.AddressCheckUnavailable))
        assertEquals(unknown, play.stream)
        assertNull(failover.report(14.seconds, started = false))
        val next = failover.report(1.seconds, started = false)
        assertSwitchedTo(nightly720, next)
        assertEquals(FailoverReason.NoFirstFrame, next.notice.reason)
    }

    @Test
    fun `checks count toward the 90 s without playback`() {
        val streams = (1..11).map { movieNight.stream("check-$it").copy(delivery = Stream.Delivery.Unknown) }
        val failover = playing(streams.first(), lookup(movieNight to streams), started = false)
        for (next in streams.drop(1).take(8)) {
            assertEquals(next, assertIs<FailoverStep.Switch>(failover.report(10.seconds, started = false)).stream)
            assertEquals(next, failover.addressCheck)
        }
        assertNull(failover.report(9.seconds, started = false))
        val gaveUp = assertIs<FailoverStep.GaveUp>(failover.report(1.seconds, started = false))
        assertEquals(GiveUpReason.NoPlaybackTimeLimit, gaveUp.because)
    }

    // Candidate order

    @Test
    fun `a failing Stream moves to the next in the Source order, then the next Source's own ranking`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv)))

        assertSwitchedTo(nightly720, failover.fail())
        assertSwitchedTo(archiveMp4, failover.fail())
        assertSwitchedTo(archiveOgv, failover.fail())
    }

    @Test
    fun `a passing failure skips the host across Sources for this playback only`() {
        for (status in listOf(429, 503)) {
            val busy = nightly1080.copy(id = "busy-$status", url = "https://Busy.example/title")
            val sameHost = nightly720.copy(id = "same-$status", url = "http://busy.example:8080/other-title")
            val sameHostOtherSource = archiveMp4.copy(id = "archive-$status", url = "https://busy.example/another-title")
            val lookup = lookup(movieNight to listOf(busy, sameHost), archive to listOf(sameHostOtherSource, archiveOgv))

            assertSwitchedTo(archiveOgv, playing(busy, lookup).failUnstarted(PlayerFault.Http(status)))
            assertSwitchedTo(sameHost, playing(busy, lookup).failUnstarted(PlayerFault.Http(404)))
        }
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
    fun `unknown delivery keeps a Stream eligible only if its codec can play`() {
        val unsupported = friendsHevc.copy(delivery = Stream.Delivery.Unknown)
        val supported = nightly1080.copy(delivery = Stream.Delivery.Unknown)
        val noCodec = nightly720.copy(delivery = Stream.Delivery.Unknown, videoCodec = null)
        val lookup = lookup(friends to listOf(unsupported), movieNight to listOf(supported, noCodec))

        assertEquals(listOf(supported, noCodec), lookup.candidates(Decoders { it == "video/avc" }, memory))
    }

    @Test
    fun `Play's automatic choice is the first candidate, skipping Streams the TV can't decode`() {
        val noHevc = Decoders { it != "video/hevc" }

        assertEquals(nightly1080, lookup(friends to listOf(friendsHevc), movieNight to listOf(nightly1080, nightly720)).candidates(noHevc, memory).first())
    }

    @Test
    fun `the Stream that last worked for the Title or episode goes first, ahead of the Source order`() {
        val lookup = lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv))

        assertEquals(
            listOf(archiveMp4, nightly1080, nightly720, archiveOgv),
            lookup.candidates(everythingDecodes, memory, lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")),
        )
    }

    @Test
    fun `the Stream that last worked doesn't go first once it has failed since`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        playing(archiveMp4, lookup).fail()

        assertEquals(listOf(nightly1080, archiveMp4), lookup.candidates(everythingDecodes, memory, lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")))
    }

    @Test
    fun `the Stream that last worked must be one the TV can decode`() {
        val lookup = lookup(movieNight to listOf(nightly1080), friends to listOf(friendsHevc))

        assertEquals(listOf(nightly1080), lookup.candidates(Decoders { it != "video/hevc" }, memory, lastWorked = WorkedStream(SourceId("friends"), "hevc")))
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
    fun `no Stream is tried twice, and when none is left Failover gives up`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        assertSwitchedTo(archiveMp4, failover.fail())
        val step = failover.failAndGetStep()

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(GiveUpReason.NothingLeftToTry, step.because)
        assertNull(failover.playing)
    }

    @Test
    fun `the Stream that worked is the last one to show a picture`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)), started = false)
        assertNull(failover.worked)

        failover.on(PlayerEvent.FirstFrame)
        assertEquals(nightly1080, failover.worked)

        failover.failUnstarted()
        assertEquals(nightly1080, failover.worked)

        failover.on(PlayerEvent.FirstFrame)
        assertEquals(archiveMp4, failover.worked)
    }

    // Resuming from Continue watching

    @Test
    fun `resuming waits for the Source of the Stream that last worked, then plays that Stream`() {
        val lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")

        assertEquals(StreamChoice.Waiting, lookup(movieNight to listOf(nightly1080), archive to null).resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis()))
        assertEquals(
            StreamChoice.Play(archiveMp4),
            lookup(movieNight to listOf(nightly1080), archive to listOf(archiveOgv, archiveMp4)).resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis()),
        )
    }

    @Test
    fun `resuming waits at most 3 s for the Source of the Stream that last worked, then plays Play's choice`() {
        val lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")
        // The Sources were asked just now; the Internet Archive is still being asked.
        val stillAsking = lookup(movieNight to listOf(nightly1080), archive to null)

        assertEquals(StreamChoice.Waiting, stillAsking.resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis() + 2_999))
        assertEquals(StreamChoice.Play(nightly1080), stillAsking.resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis() + 3_000))
    }

    @Test
    fun `when the Stream that last worked is gone, resuming plays Play's choice`() {
        val lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")

        assertEquals(
            StreamChoice.Play(nightly1080),
            lookup(movieNight to listOf(nightly1080), archive to LookupOutcome.NotAvailable, friends to null).resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis()),
        )
        // Its Source was removed.
        assertEquals(StreamChoice.Play(nightly1080), lookup(movieNight to listOf(nightly1080)).resumeChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis()))
    }

    @Test
    fun `resuming with nothing to play waits for the Sources still being asked, then says so`() {
        assertEquals(StreamChoice.Waiting, lookup(movieNight to LookupOutcome.NotAvailable, archive to null).resumeChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis()))
        assertEquals(
            StreamChoice.NothingToPlay(
                listOf(
                    SourceSummary("Movie Night Jellyfin", SourceSummary.State.NotAvailable),
                    SourceSummary("Internet Archive", SourceSummary.State.NotAvailable),
                ),
            ),
            lookup(movieNight to LookupOutcome.NotAvailable, archive to LookupOutcome.NotAvailable).resumeChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis()),
        )
    }

    // Playing an episode in one click

    @Test
    fun `one click waits for a Source earlier in the order that answers within 5 s, even when a later one answered sooner`() {
        val laterAnsweredFirst = lookup(movieNight to null, archive to listOf(archiveMp4))
        assertEquals(StreamChoice.Waiting, laterAnsweredFirst.oneClickChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis() + 4_000))

        val bothAnswered = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        assertEquals(StreamChoice.Play(nightly1080), bothAnswered.oneClickChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis() + 4_500))
    }

    @Test
    fun `one click waits at most 5 s for a Source earlier in the order, then plays the best Stream so far`() {
        val firstStillAsking = lookup(movieNight to null, archive to listOf(archiveMp4))

        assertEquals(StreamChoice.Waiting, firstStillAsking.oneClickChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis() + 4_999))
        assertEquals(StreamChoice.Play(archiveMp4), firstStillAsking.oneClickChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis() + 5_000))
    }

    @Test
    fun `when nothing has arrived after 5 s, one click plays the first Stream that does`() {
        val later = clock.nowMillis() + 12_000

        assertEquals(StreamChoice.Waiting, lookup(movieNight to null, archive to null).oneClickChoice(everythingDecodes, memory, null, nowMillis = later))
        assertEquals(StreamChoice.Play(archiveMp4), lookup(movieNight to null, archive to listOf(archiveMp4)).oneClickChoice(everythingDecodes, memory, null, nowMillis = later))
    }

    @Test
    fun `one click plays the Stream that last worked for the episode at once`() {
        val lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")

        assertEquals(
            StreamChoice.Play(archiveMp4),
            lookup(movieNight to null, archive to listOf(archiveMp4)).oneClickChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis()),
        )
    }

    @Test
    fun `one click waits up to 5 s for the Source of the Stream that last worked, even later in the order`() {
        val lastWorked = WorkedStream(SourceId("internet-archive"), "mp4")
        val archiveStillAsking = lookup(movieNight to listOf(nightly1080), archive to null)

        assertEquals(StreamChoice.Waiting, archiveStillAsking.oneClickChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis() + 4_999))
        assertEquals(StreamChoice.Play(nightly1080), archiveStillAsking.oneClickChoice(everythingDecodes, memory, lastWorked, nowMillis = clock.nowMillis() + 5_000))
    }

    @Test
    fun `when no Source has a Stream this TV can play, one click says what happened at each Source`() {
        val unreachable = LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable)

        assertEquals(StreamChoice.Waiting, lookup(movieNight to LookupOutcome.NotAvailable, archive to null).oneClickChoice(everythingDecodes, memory, null, nowMillis = clock.nowMillis()))
        assertEquals(
            StreamChoice.NothingToPlay(
                listOf(
                    SourceSummary("Movie Night Jellyfin", SourceSummary.State.NotAvailable),
                    SourceSummary("Internet Archive", SourceSummary.State.LookupFailed(LookupOutcome.Failure.Reason.Unreachable)),
                    SourceSummary("Friends' Emby", SourceSummary.State.NothingThisTvCanDecode),
                ),
            ),
            lookup(movieNight to LookupOutcome.NotAvailable, archive to unreachable, friends to listOf(friendsHevc))
                .oneClickChoice(Decoders { it != "video/hevc" }, memory, null, nowMillis = clock.nowMillis()),
        )
    }

    // When a Stream counts as not working

    @Test
    fun `a Stream that shows no first frame within 15 s of being chosen fails`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        val failover = playing(nightly1080, lookup, started = false)

        assertNull(failover.report(14.seconds))
        val step = failover.report(1.seconds)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(FailoverReason.NoFirstFrame, step.notice.reason)
        assertEquals(listOf(archiveMp4, nightly1080), lookup.candidates(everythingDecodes, memory))
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
        listOf(PlayerFault.Http(), PlayerFault.Network, PlayerFault.UnsupportedFormat, PlayerFault.Decoder, PlayerFault.Other).forEach { error ->
            val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

            val step = failover.on(PlayerEvent.Failed(error))

            assertSwitchedTo(archiveMp4, step)
            assertEquals(FailoverReason.Fault(error), step.notice.reason)
        }
    }

    @Test
    fun `a stall over 20 s fails the Stream`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        val failover = playing(nightly1080, lookup)
        failover.report(1.minutes)

        failover.on(PlayerEvent.Buffering)
        assertNull(failover.report(20.seconds))
        val step = failover.report(1.seconds)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(FailoverReason.LongStall, step.notice.reason)
        assertEquals(listOf(archiveMp4, nightly1080), lookup.candidates(everythingDecodes, memory))
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
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        val failover = playing(nightly1080, lookup)

        assertNull(failover.stall(5.seconds))
        failover.report(50.seconds)
        assertNull(failover.stall(5.seconds))
        failover.report(50.seconds)
        val step = failover.on(PlayerEvent.Buffering)

        assertSwitchedTo(archiveMp4, step)
        assertEquals(FailoverReason.RepeatedStalls, step.notice.reason)
        assertEquals(listOf(archiveMp4, nightly1080), lookup.candidates(everythingDecodes, memory))
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

        val step = failover.on(PlayerEvent.Failed(PlayerFault.Http()))

        assertSwitchedTo(archiveMp4, step)
        assertEquals((42.minutes - 5.seconds).inWholeMilliseconds, step.startAtMillis)
    }

    @Test
    fun `a Stream that fails in its first 5 s is replaced from the start`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(3.seconds)

        assertEquals(0, failover.fail().startAtMillis)
    }

    @Test
    fun `when the replacement never starts either, the next one resumes from the original failure point`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))
        failover.report(10.minutes)
        failover.on(PlayerEvent.Failed(PlayerFault.Http()))

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
            memory = memory,
            clock = clock,
            logger = logger,
            askSourcesAgain = { sourcesAskedAgain++ },
            startAtMillis = 20.minutes.inWholeMilliseconds,
        )

        val step = failover.report(15.seconds, started = false)

        assertEquals((20.minutes - 5.seconds).inWholeMilliseconds, (step as FailoverStep.Switch).startAtMillis)
    }

    // What the Household sees

    @Test
    fun `the notice updates the Source and Candidate count on successive switches`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4)))

        assertSwitchedTo(nightly720, failover.failUnstarted())
        assertEquals(FailoverNotice.Next.Trying("Movie Night Jellyfin", 2, 3), failover.notice?.next)

        assertSwitchedTo(archiveMp4, failover.failUnstarted())
        assertEquals(FailoverNotice.Next.Trying("Internet Archive", 3, 3), failover.notice?.next)
    }

    @Test
    fun `the notice stays while the next Stream loads and clears on its first frame`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        assertSwitchedTo(archiveMp4, failover.failUnstarted())
        failover.report(10.seconds, started = false)
        assertEquals(FailoverNotice.Next.Trying("Internet Archive", 2, 2), failover.notice?.next)

        failover.on(PlayerEvent.FirstFrame)
        assertNull(failover.notice)
    }

    @Test
    fun `the running notice counts Candidates as their Sources answer`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to null))

        assertSwitchedTo(nightly720, failover.failUnstarted())
        assertEquals(FailoverNotice.Next.Trying("Movie Night Jellyfin", 2, 2), failover.notice?.next)

        failover.lookupChanged(lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv)))

        assertEquals(FailoverNotice.Next.Trying("Movie Night Jellyfin", 2, 4), failover.notice?.next)
    }

    @Test
    fun `a hand-picked Stream fails over too, and the notice says playback moved away from the Household's choice`() {
        val failover = playing(archiveOgv, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)), handPicked = true)

        val first = failover.fail()
        assertSwitchedTo(nightly1080, first)
        assertTrue(first.notice.awayFromHouseholdsChoice)
        assertEquals(FailoverNotice.Next.Trying("Movie Night Jellyfin", 2, 3), first.notice.next)

        // Once playback has left the Household's choice, later Failovers are ordinary ones.
        val second = failover.fail()
        assertEquals(false, second.notice.awayFromHouseholdsChoice)
        assertEquals(FailoverNotice.Next.Trying("Internet Archive", 3, 3), second.notice.next)
    }

    @Test
    fun `passing and permanent failures are logged with HTTP status and without Stream addresses`() {
        val first = nightly1080.copy(id = nightly1080.url)
        val last = archiveMp4.copy(id = archiveMp4.url)
        val failover = playing(first, lookup(movieNight to listOf(first), archive to listOf(last)))

        assertSwitchedTo(last, failover.failUnstarted(PlayerFault.Http(429)))
        assertIs<FailoverStep.GaveUp>(failover.failUnstarted(PlayerFault.Http(404)))

        assertTrue(logger.messages.any { "passing" in it && "HTTP 429" in it }, "${logger.messages}")
        assertTrue(logger.messages.any { "permanent" in it && "HTTP 404" in it }, "${logger.messages}")
        assertTrue(logger.messages.none { first.url in it || last.url in it }, "${logger.messages}")
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

        val waiting = failover.failAndGetStep()
        assertIs<FailoverStep.Waiting>(waiting)
        assertEquals("Internet Archive", waiting.notice.failedSourceName)
        assertEquals(FailoverNotice.Next.WaitingForSources, waiting.notice.next)

        assertNull(failover.lookupChanged(lookup(movieNight to LookupOutcome.NotAvailable, friends to null, archive to listOf(archiveMp4))))
        val step = failover.lookupChanged(lookup(movieNight to LookupOutcome.NotAvailable, friends to listOf(friendsHevc), archive to listOf(archiveMp4)))

        assertSwitchedTo(friendsHevc, step)
        assertEquals(FailoverNotice.Next.Trying("Friends' Emby", 2, 2), step.notice.next)
    }

    @Test
    fun `when the last Source answers with nothing new, Failover is out of Streams`() {
        val failover = playing(archiveMp4, lookup(movieNight to null, archive to listOf(archiveMp4)))
        failover.failAndGetStep()

        val step = failover.lookupChanged(lookup(movieNight to LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable), archive to listOf(archiveMp4)))

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(GiveUpReason.NothingLeftToTry, step.because)
    }

    @Test
    fun `the Stream playing is the one Failover moved to`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        assertEquals(nightly1080, failover.playing)

        failover.fail()

        assertEquals(archiveMp4, failover.playing)
    }

    // Failover memory

    @Test
    fun `a rate limited or temporarily unavailable Stream keeps its place next playback`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))

        for (status in listOf(429, 503)) {
            assertSwitchedTo(archiveMp4, playing(nightly1080, lookup).failUnstarted(PlayerFault.Http(status)))
            assertEquals(listOf(nightly1080, archiveMp4), lookup.candidates(everythingDecodes, FailoverMemory(FileStorage(dir), clock)))
        }
    }

    @Test
    fun `a Stream that failed goes to the end of the order next time`() {
        val lookup = lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4))
        playing(nightly1080, lookup).fail()

        assertEquals(listOf(nightly720, archiveMp4, nightly1080), lookup.candidates(everythingDecodes, memory))
    }

    @Test
    fun `a Stream that failed earlier is tried last when Failover moves on`() {
        val lookup = lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4))
        playing(nightly720, lookup).fail()

        val failover = playing(nightly1080, lookup)

        assertSwitchedTo(archiveMp4, failover.fail())
        assertSwitchedTo(nightly720, failover.fail())
    }

    @Test
    fun `a failed Stream is remembered across a restart, and by its Source and Stream ID, not its URL`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        playing(nightly1080, lookup).fail()

        val afterRestart = FailoverMemory(FileStorage(dir), clock)
        val sameStreamNewUrl = nightly1080.copy(url = "https://movie-night.example/1080?token=new")
        val sameIdOtherSource = archive.stream("1080")

        assertEquals(
            listOf(archiveMp4, sameIdOtherSource, sameStreamNewUrl),
            lookup(movieNight to listOf(sameStreamNewUrl), archive to listOf(archiveMp4, sameIdOtherSource)).candidates(everythingDecodes, afterRestart),
        )
    }

    @Test
    fun `permanent HTTP errors and unreadable formats go last for 24 hours`() {
        val errors = listOf(400, 403, 404, 410, 500).map { PlayerFault.Http(it) } + PlayerFault.UnsupportedFormat
        for (fault in errors) {
            val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
            assertSwitchedTo(archiveMp4, playing(nightly1080, lookup).failUnstarted(fault))

            clock.advanceBy(24.hours - 1.seconds)
            assertEquals(listOf(archiveMp4, nightly1080), lookup.candidates(everythingDecodes, FailoverMemory(FileStorage(dir), clock)), "$fault")
            clock.advanceBy(1.seconds)
            assertEquals(listOf(nightly1080, archiveMp4), lookup.candidates(everythingDecodes, FailoverMemory(FileStorage(dir), clock)), "$fault")
        }
    }

    @Test
    fun `failing again starts the 24 hours again`() {
        val lookup = lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4))
        playing(nightly1080, lookup).fail()
        clock.advanceBy(20.hours)
        playing(nightly1080, lookup).fail()

        clock.advanceBy(20.hours)

        assertEquals(listOf(archiveMp4, nightly1080), lookup.candidates(everythingDecodes, memory))
    }

    // A Stream list that may have expired

    @Test
    fun `a passing failure on an old Stream list moves on without asking the Sources again`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(31.minutes)

        assertSwitchedTo(archiveMp4, failover.failUnstarted(PlayerFault.Http(503)))
        assertEquals(0, sourcesAskedAgain)
    }

    @Test
    fun `a Failover more than 30 minutes after the Lookup asks the Sources again, and carries on with the Streams already known`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4, archiveOgv)))
        failover.report(31.minutes)

        assertSwitchedTo(archiveMp4, failover.fail())
        assertEquals(1, sourcesAskedAgain)
    }

    @Test
    fun `a Failover within 30 minutes of the Lookup doesn't ask the Sources again`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))
        failover.report(30.minutes)

        failover.fail()

        assertEquals(0, sourcesAskedAgain)
    }

    @Test
    fun `while the Sources are asked again, known Streams are still tried, and the refreshed ones join`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4)))
        failover.report(40.minutes)
        failover.fail()
        val refreshedMp4 = archiveMp4.copy(url = "https://internet-archive.example/mp4?fresh")
        val archiveNew = archive.stream("new")

        assertNull(failover.lookupChanged(lookup(movieNight to AskingAgain(nightly1080, nightly720), archive to AskingAgain(archiveMp4))))
        assertEquals(1, sourcesAskedAgain)
        assertNull(failover.lookupChanged(lookup(movieNight to AskingAgain(nightly1080, nightly720), archive to listOf(refreshedMp4, archiveNew))))
        failover.report(1.minutes)

        assertSwitchedTo(refreshedMp4, failover.fail())
        assertSwitchedTo(archiveNew, failover.fail())
        assertEquals(1, sourcesAskedAgain)
    }

    @Test
    fun `when the known Streams run out of an old list, Failover waits for the Sources it asked again`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080)))
        failover.report(31.minutes)

        val waiting = failover.failAndGetStep()
        assertIs<FailoverStep.Waiting>(waiting)
        assertEquals(1, sourcesAskedAgain)

        assertNull(failover.lookupChanged(lookup(movieNight to AskingAgain(nightly1080))))
        val refreshed = movieNight.stream("1080-again")
        assertSwitchedTo(refreshed, failover.lookupChanged(lookup(movieNight to listOf(nightly1080, refreshed))))
    }

    @Test
    fun `the refreshed list is new, so the Sources aren't asked again for another 30 minutes`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv)))
        failover.report(31.minutes)
        failover.fail()
        failover.lookupChanged(lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, archiveOgv)))

        failover.report(29.minutes)
        failover.fail()
        assertEquals(1, sourcesAskedAgain)

        failover.report(2.minutes)
        failover.fail()
        assertEquals(2, sourcesAskedAgain)
    }

    // Giving up

    @Test
    fun `Failover keeps trying after many fast failures until a Stream shows its first frame`() {
        val streams = (1..12).map { archive.stream("s$it") }
        val failover = playing(streams[0], lookup(archive to streams), started = false)

        for (next in streams.drop(1)) {
            clock.advanceBy(500.milliseconds)
            assertSwitchedTo(next, failover.failUnstarted())
        }
        failover.on(PlayerEvent.FirstFrame)

        assertEquals(streams.last(), failover.worked)
    }

    @Test
    fun `Failover gives up after 90 s without playback even with Candidates left`() {
        val streams = (1..7).map { archive.stream("s$it") }
        val failover = playing(streams.first(), lookup(archive to streams))
        failover.report(10.minutes)

        failover.on(PlayerEvent.Buffering)
        assertSwitchedTo(streams[1], failover.report(21.seconds, started = false))
        for (next in streams.subList(2, 6)) {
            assertSwitchedTo(next, failover.report(15.seconds, started = false))
        }
        assertNull(failover.report(8.seconds, started = false))
        val step = failover.report(1.seconds, started = false)

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(GiveUpReason.NoPlaybackTimeLimit, step.because)
    }

    @Test
    fun `Failover gives up after 90 s waiting for Sources that never answer`() {
        val failover = playing(archiveMp4, lookup(movieNight to null, archive to listOf(archiveMp4)))
        assertIs<FailoverStep.Waiting>(failover.failAndGetStep())

        assertNull(failover.report(89.seconds, started = false))
        val step = failover.report(1.seconds, started = false)

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(GiveUpReason.NoPlaybackTimeLimit, step.because)
    }

    @Test
    fun `pausing isn't time without playback`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080), archive to listOf(archiveMp4)))

        failover.on(PlayerEvent.Paused)
        assertNull(failover.report(10.minutes, started = false))
        failover.on(PlayerEvent.Playing)

        assertSwitchedTo(archiveMp4, failover.fail())
    }

    @Test
    fun `once Failover has given up it does nothing more`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080)))
        assertIs<FailoverStep.GaveUp>(failover.failAndGetStep())

        assertNull(failover.on(PlayerEvent.Failed(PlayerFault.Http())))
        assertNull(failover.report(5.minutes))
        assertNull(failover.lookupChanged(lookup(movieNight to listOf(nightly1080, nightly720))))
    }

    @Test
    fun `giving up says what happened at each Source`() {
        val emptyShelf = TestSource("empty-shelf", "Empty Shelf")
        val offline = TestSource("offline", "Offline Server")
        val slow = TestSource("slow", "Slow Add-on")
        val failover = playing(
            nightly1080,
            lookup(
                movieNight to listOf(nightly1080, nightly720),
                friends to listOf(friendsHevc),
                emptyShelf to LookupOutcome.NotAvailable,
                offline to LookupOutcome.Failure(LookupOutcome.Failure.Reason.AuthRequired),
                slow to null,
                archive to listOf(archiveMp4, archiveOgv),
            ),
            decoders = Decoders { it != "video/hevc" },
            started = false,
        )

        failover.failUnstarted(PlayerFault.Decoder)
        failover.report(15.seconds, started = false)
        failover.failUnstarted(PlayerFault.Network)
        assertIs<FailoverStep.Waiting>(failover.failUnstarted(PlayerFault.Network))
        val step = failover.report(75.seconds, started = false)

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(
            listOf(
                SourceSummary("Movie Night Jellyfin", SourceSummary.State.StreamsFailed(listOf(FailoverReason.Fault(PlayerFault.Decoder), FailoverReason.NoFirstFrame), notTried = 0)),
                SourceSummary("Friends' Emby", SourceSummary.State.NothingThisTvCanDecode),
                SourceSummary("Empty Shelf", SourceSummary.State.NotAvailable),
                SourceSummary("Offline Server", SourceSummary.State.LookupFailed(LookupOutcome.Failure.Reason.AuthRequired)),
                SourceSummary("Slow Add-on", SourceSummary.State.StillLooking),
                SourceSummary(
                    "Internet Archive",
                    SourceSummary.State.StreamsFailed(listOf(FailoverReason.Fault(PlayerFault.Network), FailoverReason.Fault(PlayerFault.Network)), notTried = 0),
                ),
            ),
            step.sources,
        )
    }

    @Test
    fun `giving up lists passing and permanent failures under their Sources and counts skipped Streams as untried`() {
        val sameHostOtherSource = archiveOgv.copy(url = nightly720.url)
        val lookup = lookup(movieNight to listOf(nightly1080, nightly720), archive to listOf(archiveMp4, sameHostOtherSource))
        val failover = playing(nightly1080, lookup)

        assertSwitchedTo(archiveMp4, failover.failUnstarted(PlayerFault.Http(503)))
        val step = assertIs<FailoverStep.GaveUp>(failover.failUnstarted(PlayerFault.Http(410)))

        assertEquals(
            listOf(
                SourceSummary("Movie Night Jellyfin", SourceSummary.State.StreamsFailed(listOf(FailoverReason.Fault(PlayerFault.Http(503))), notTried = 1)),
                SourceSummary("Internet Archive", SourceSummary.State.StreamsFailed(listOf(FailoverReason.Fault(PlayerFault.Http(410))), notTried = 1)),
            ),
            step.sources,
        )
    }

    @Test
    fun `giving up counts the Streams not tried`() {
        val streams = (1..7).map { archive.stream("s$it") }
        val failover = playing(streams[0], lookup(movieNight to listOf(nightly1080), archive to streams), started = false)

        repeat(5) { assertIs<FailoverStep.Switch>(failover.report(15.seconds, started = false)) }
        val step = failover.report(15.seconds, started = false)

        assertIs<FailoverStep.GaveUp>(step)
        assertEquals(
            listOf(
                SourceSummary("Movie Night Jellyfin", SourceSummary.State.StreamsFailed(listOf(FailoverReason.NoFirstFrame), notTried = 0)),
                SourceSummary("Internet Archive", SourceSummary.State.StreamsFailed(List(5) { FailoverReason.NoFirstFrame }, notTried = 2)),
            ),
            step.sources,
        )
    }

    @Test
    fun `giving up is logged with why`() {
        val failover = playing(nightly1080, lookup(movieNight to listOf(nightly1080)))

        failover.failAndGetStep()

        assertTrue(logger.messages.any { "gave up" in it.lowercase() && "NothingLeftToTry" in it }, "${logger.messages}")
    }

    // Scripting the player

    private fun playing(
        stream: Stream,
        lookup: Lookup,
        handPicked: Boolean = false,
        decoders: Decoders = everythingDecodes,
        started: Boolean = true,
    ): Failover = Failover(lookup, stream, handPicked, decoders, memory, clock, logger, askSourcesAgain = { sourcesAskedAgain++ }).also {
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

    /** The current Stream fails at once, and the next one shows its first frame. */
    private fun Failover.fail(): FailoverStep.Switch = failAndGetStep().also { assertIs<FailoverStep.Switch>(it) } as FailoverStep.Switch

    /** The current Stream fails at once, and the next one, if any, shows its first frame. */
    private fun Failover.failAndGetStep(): FailoverStep? = on(PlayerEvent.Failed(PlayerFault.Http())).also {
        if (it is FailoverStep.Switch) {
            position = it.startAtMillis
            on(PlayerEvent.FirstFrame)
        }
    }

    /** The current Stream fails at once, and the next one, if any, never shows a picture. */
    private fun Failover.failUnstarted(error: PlayerFault = PlayerFault.Http()): FailoverStep? = on(PlayerEvent.Failed(error))

    /** A Lookup asked now. Each Source's answer is a list of Streams, a [LookupOutcome], [AskingAgain], or null while it is still being asked. */
    private fun lookup(vararg sources: Pair<Source, Any?>) = Lookup(
        sources.map { (source, answer) ->
            when (answer) {
                null -> SourceLookup(source, null)
                is LookupOutcome -> SourceLookup(source, answer)
                is List<*> -> SourceLookup(source, LookupOutcome.Streams(answer.filterIsInstance<Stream>()))
                is AskingAgain -> SourceLookup(source, LookupOutcome.Streams(answer.streams.toList()), askingAgain = true)
                else -> error("Not an answer: $answer")
            }
        },
        askedAtMillis = clock.nowMillis(),
    )

    /** A Source being asked again, which still has the Streams it found last time. */
    private class AskingAgain(vararg val streams: Stream)

    private class TestSource(id: String, override val name: String) : Source {
        override val id = SourceId(id)
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
