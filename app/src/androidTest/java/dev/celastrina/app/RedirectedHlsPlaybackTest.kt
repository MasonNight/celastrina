package dev.celastrina.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Stremio-compatible add-on that offers one Stream per host gives each as an address with no file extension, such
 * as `https://host/redirect?…`, which redirects to an HLS playlist. Nothing in the address says it is HLS, so the
 * add-on's Stream arrives as a progressive one. Play must still play it.
 *
 * The add-on and the host it redirects to are a server in this test, answering from this test's assets: a few seconds
 * of synthetic video made with ffmpeg's test pattern. Run on the emulator.
 *
 * The assets are hand-made, since the live add-on's Streams carry tokens and real video: in `assets/hls`,
 * `ffmpeg -f lavfi -i testsrc=size=320x180:rate=25 -t 6 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 25
 * -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename 'segment%d.ts' playlist.m3u8`.
 */
@RunWith(AndroidJUnit4::class)
class RedirectedHlsPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host = AssetHost()
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @After
    fun stop() {
        onMain { player?.release() }
        host.close()
    }

    @Test
    fun an_add_on_stream_that_redirects_to_hls_plays() {
        // As the add-on's Stream arrives: its address says nothing of HLS.
        val stream = Stream(SourceId("notorrent"), "redirect-1", "${host.url}/redirect?id=1", Stream.Delivery.Progressive)
        val error = arrayOfNulls<PlaybackException>(1)
        val failed = CountDownLatch(1)

        runBlocking(Dispatchers.Main) {
            player = newPlayer(instrumentation.targetContext, stream, startAtMillis = 0).apply {
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

    /** The add-on's redirector and the HLS host it sends playback to, on the emulator's loopback. */
    private inner class AssetHost : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
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
            while (!reader.readLine().isNullOrEmpty()) Unit
            val out = socket.getOutputStream()
            when {
                path.startsWith("/redirect") ->
                    out.write("HTTP/1.1 302 Found\r\nLocation: $url/hls/playlist?token=synthetic\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                path.startsWith("/hls/") -> {
                    val name = path.removePrefix("/hls/").substringBefore("?").let { if (it == "playlist") "playlist.m3u8" else it }
                    val body = assets.open("hls/$name").use { it.readBytes() }
                    val type = if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                }
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
        }

        override fun close() = server.close()
    }

    private companion object {
        const val PLAYBACK_WAIT_MILLIS = 15_000L
    }
}
