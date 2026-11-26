package dev.celastrina.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.FailoverStep
import dev.celastrina.core.failover.PlayerEvent
import dev.celastrina.core.failover.PlayerFault
import dev.celastrina.core.failover.SourceSummary
import dev.celastrina.core.failover.StreamFailure
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.SourceLookup
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Stremio-compatible add-on that offers one Stream per host gives each as an address with no file extension, such
 * as `https://host/redirect?…`, which redirects to an HLS playlist. Nothing in the address says it is HLS, so the
 * add-on's Stream arrives with unknown delivery. Play must still play it.
 *
 * The add-on and the host it redirects to are a server in this test, answering from this test's assets: a few seconds
 * of synthetic HLS, DASH and MP4 video made with ffmpeg's test pattern. Run on the emulator.
 *
 * The assets are hand-made, since the live add-on's Streams carry tokens and real video: in `assets/hls`,
 * `ffmpeg -f lavfi -i testsrc=size=320x180:rate=25 -t 6 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 25
 * -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename 'segment%d.ts' playlist.m3u8`.
 * The other fixtures use the same pattern at 160x90 for 3 s with a 75-frame GOP, using the DASH muxer and MP4
 * with faststart, respectively.
 */
@RunWith(AndroidJUnit4::class)
class FailoverPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host = AssetHost()
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    @get:Rule val storage = TemporaryFolder()

    @After
    fun stop() {
        onMain { player?.release() }
        host.close()
    }

    @Test
    fun a_known_single_file_with_an_extensionless_address_skips_the_check() {
        val stream = Stream(SourceId("jellyfin"), "file-1", "${host.url}/Videos/1/stream", Stream.Delivery.Progressive)

        assertNull(failover(stream).addressCheck)
        assertFalse(host.receivedRequest.get(), "Known delivery should skip the Address check")
    }

    @Test
    fun an_add_on_stream_that_redirects_to_hls_plays() {
        // As the add-on's Stream arrives: its address says nothing of HLS.
        val stream = Stream(
            SourceId("notorrent"), "redirect-1", "${host.url}/redirect?id=1", Stream.Delivery.Unknown,
            headers = mapOf("Referer" to host.url),
        )
        assertCheckedPlayback(stream, Stream.Delivery.Hls, "${host.url}/hls/playlist?token=synthetic")
    }

    @Test
    fun dash_is_recognised_by_content_type_and_plays() {
        val stream = unknownStream("dash/manifest")
        assertCheckedPlayback(stream, Stream.Delivery.Dash, stream.url)
    }

    @Test
    fun a_redirect_to_a_single_file_plays_from_the_final_address() {
        val stream = unknownStream("file")
        assertCheckedPlayback(stream, Stream.Delivery.Progressive, "${host.url}/video")
    }

    @Test
    fun an_address_check_reports_the_http_status_to_failover() = runBlocking {
        val failover = failover(unknownStream("busy.mp4"))
        val gaveUp = assertIs<FailoverStep.GaveUp>(failover.on(checkNotNull(failover.addressCheck).checkAddress()))
        val source = assertIs<SourceSummary.State.StreamsFailed>(gaveUp.sources.single().state)
        assertEquals(listOf(StreamFailure.Fault(PlayerFault.Http(429))), source.failures)
    }

    @Test
    fun an_address_check_that_cannot_finish_reading_times_out_after_10_seconds() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val result = unknownStream("slow").checkAddress()
        val elapsed = SystemClock.elapsedRealtime() - start

        assertEquals(PlayerEvent.AddressCheckTimedOut, result)
        assertTrue(elapsed in 9_900..13_000, "Address check took ${elapsed} ms")
    }

    @Test
    fun dead_hosts_are_checked_while_a_stream_loads_and_failover_reaches_the_working_stream() {
        val starting = unknownStream("starting.mp4").copy(delivery = Stream.Delivery.Progressive)
        val dead = unknownStream("dead-one")
        val alsoDead = unknownStream("dead-two").let { it.copy(url = it.url.replace("127.0.0.1", "localhost")) }
        val working = unknownStream("file")
        val failover = failover(starting, dead, alsoDead, working)

        runBlocking(Dispatchers.Main) {
            val checks = AddressChecks(this)
            val checkedDead = mutableSetOf<Stream>()
            val switched = CompletableDeferred<FailoverStep>()
            var workingChecked = false
            fun checkAddresses() {
                checks.update(failover) { event ->
                    failover.on(event)
                    if (event.candidate in listOf(dead, alsoDead) && event.answer is PlayerEvent.AddressCheckHttpError) {
                        checkedDead += event.candidate
                    }
                    if (event.candidate == working && event.answer is PlayerEvent.AddressChecked) workingChecked = true
                    if (checkedDead.size == 2 && workingChecked) host.releaseStartingStream()
                    checkAddresses()
                }
            }
            player = newPlayer(instrumentation.targetContext, starting, startAtMillis = 0).apply {
                addListener(Media3FailoverEvents(this) { event ->
                    failover.on(event)?.let { switched.complete(it) }
                })
            }
            checkAddresses()

            val next = assertIs<FailoverStep.Switch>(withTimeout(PLAYBACK_WAIT_MILLIS) { switched.await() })
            assertEquals(setOf(dead, alsoDead), checkedDead)
            assertEquals(working.copy(url = "${host.url}/video", delivery = Stream.Delivery.Progressive), next.stream)
            assertNull(failover.addressCheck)
            player?.release()
            player = newPlayer(instrumentation.targetContext, next.stream, next.startAtMillis)
        }

        assertTrue(waitFor(PLAYBACK_WAIT_MILLIS, CountDownLatch(1)) { (player?.currentPosition ?: 0) >= 1_000 },
            "The working Stream never got 1 s in")
    }

    private fun unknownStream(path: String) = Stream(SourceId("notorrent"), path, "${host.url}/$path", Stream.Delivery.Unknown)

    private fun assertCheckedPlayback(stream: Stream, delivery: Stream.Delivery, finalUrl: String) {
        val failover = failover(stream)
        val play = runBlocking { assertIs<FailoverStep.Play>(failover.on(checkNotNull(failover.addressCheck).checkAddress())) }
        assertEquals(stream.copy(url = finalUrl, delivery = delivery), play.stream)
        val error = arrayOfNulls<PlaybackException>(1)
        val failed = CountDownLatch(1)

        runBlocking(Dispatchers.Main) {
            player = newPlayer(instrumentation.targetContext, play.stream, play.startAtMillis).apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        error[0] = e
                        failed.countDown()
                    }
                })
            }
        }
        val advanced = waitFor(PLAYBACK_WAIT_MILLIS, failed) { (player?.currentPosition ?: 0) >= 1_000 }

        assertNull(error[0], "Playback failed: ${error[0]?.errorCodeName} ${error[0]?.cause}")
        assertTrue(advanced, "Playback never got 1 s in")
    }

    private fun failover(stream: Stream, vararg candidates: Stream) = Failover(
        Lookup(listOf(SourceLookup(StreamSource(stream.sourceId), LookupOutcome.Streams(listOf(stream) + candidates))), Clock.System.nowMillis()),
        stream,
        handPicked = false,
        Decoders { true },
        FailoverMemory(FileStorage(storage.root), Clock.System),
        Clock.System,
        Logger.StandardOut,
        askSourcesAgain = {},
    )

    private class StreamSource(override val id: SourceId) : Source {
        override val name = id.value
        override val kind = SourceKind("Stremio-compatible add-on", setOf(IdScheme.Tmdb), setOf(Title.Kind.Movie))
        override suspend fun lookUp(title: TitleReference) = LookupOutcome.NotAvailable
    }

    @Test
    fun a_rate_limited_stream_reports_its_http_status_to_failover() {
        val stream = Stream(SourceId("notorrent"), "busy", "${host.url}/busy.mp4", Stream.Delivery.Progressive)
        val failed = CountDownLatch(1)
        var failure: PlayerEvent.Failed? = null

        runBlocking(Dispatchers.Main) {
            player = newPlayer(instrumentation.targetContext, stream, startAtMillis = 0).apply {
                addListener(Media3FailoverEvents(this) { event ->
                    if (event is PlayerEvent.Failed) {
                        failure = event
                        failed.countDown()
                    }
                })
            }
        }

        assertTrue(failed.await(PLAYBACK_WAIT_MILLIS, TimeUnit.MILLISECONDS), "The player did not report a failure")
        assertEquals(PlayerEvent.Failed(PlayerFault.Http(429)), failure)
    }

    private fun waitFor(millis: Long, failed: CountDownLatch, done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (failed.await(200, TimeUnit.MILLISECONDS)) return false
            var reached = false
            onMain { reached = done() }
            if (reached) return true
        }
        return false
    }

    private fun onMain(block: () -> Unit) {
        val ran = CountDownLatch(1)
        main.post {
            block()
            ran.countDown()
        }
        ran.await(5, TimeUnit.SECONDS)
    }

    /** The add-on's redirector and video hosts, on the emulator's loopback. */
    private inner class AssetHost : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val receivedRequest = AtomicBoolean()
        private val closing = CountDownLatch(1)
        private val startingStreamMayFail = CountDownLatch(1)

        fun releaseStartingStream() = startingStreamMayFail.countDown()
        private val assets = instrumentation.context.assets

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: java.io.IOException) { break }
                    thread(isDaemon = true) { socket.use(::answer) }
                }
            }
        }

        private fun answer(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
            receivedRequest.set(true)
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val out = socket.getOutputStream()
            when {
                path == "/starting.mp4" -> {
                    startingStreamMayFail.await(5, TimeUnit.SECONDS)
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                (path.startsWith("/redirect") || path.startsWith("/hls/")) && headers.none { it.equals("Referer: $url", ignoreCase = true) } ->
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                path.startsWith("/redirect") ->
                    out.write("HTTP/1.1 302 Found\r\nLocation: $url/hls/playlist?token=synthetic\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                path.startsWith("/hls/") -> {
                    val name = path.removePrefix("/hls/").substringBefore("?").let { if (it == "playlist") "playlist.m3u8" else it }
                    socket.send(assets.open("hls/$name").use { it.readBytes() })
                }
                path == "/dash/manifest" -> {
                    val manifest = assets.open("dash/manifest.mpd").bufferedReader().use { it.readText() }
                    // Put the MPD element beyond the sniffed bytes so only the Content-Type can identify it.
                    val padded = manifest.replace("<MPD", "<!-- ${" ".repeat(1024)} -->\n<MPD")
                    socket.send(padded.toByteArray(), "application/dash+xml; charset=UTF-8")
                }
                path.startsWith("/dash/") -> socket.send(assets.open(path.removePrefix("/")).use { it.readBytes() }, "video/mp4")
                path == "/file" ->
                    out.write("HTTP/1.1 302 Found\r\nLocation: $url/video\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                path == "/video" -> socket.send(assets.open("video.mp4").use { it.readBytes() }, "video/mp4")
                path == "/slow" -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n#EXT".toByteArray())
                    out.flush()
                    closing.await(30, TimeUnit.SECONDS)
                }
                path == "/busy.mp4" ->
                    out.write("HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        }

        private fun Socket.send(body: ByteArray, contentType: String? = null) {
            val out = getOutputStream()
            val type = contentType?.let { "Content-Type: $it\r\n" }.orEmpty()
            out.write("HTTP/1.1 200 OK\r\n${type}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }

        override fun close() {
            closing.countDown()
            releaseStartingStream()
            server.close()
        }
    }

    private companion object {
        const val PLAYBACK_WAIT_MILLIS = 15_000L
    }
}
