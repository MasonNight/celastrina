package dev.celastrina.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/** Where the player loads a Stream from, and as what: [mimeType] null plays it as a single file. */
internal data class PlaybackAddress(val url: String, val mimeType: String?)

/**
 * Where and as what the player loads this Stream.
 *
 * A Stream's own delivery says HLS or DASH when its Source knows. A Stremio-compatible add-on that offers one Stream
 * per host often doesn't: it gives an address that says nothing of what it serves, such as `https://host/redirect?…`
 * leading to an HLS playlist, so the Stream arrives as a progressive one, and played as a single file it fails as a
 * format this TV can't play. So for a progressive Stream whose address has no file extension, the server is asked
 * first, through [http] with the Stream's headers: the Content-Type it answers with after any redirects, or failing
 * that the first bytes, tell HLS and DASH apart from a file, and an HLS or DASH Stream then plays from where the
 * redirects ended. When asking fails, the Stream plays as it came, so the player meets the same failure and Failover
 * moves on as usual.
 */
@OptIn(UnstableApi::class)
internal suspend fun Stream.playbackAddress(http: DataSource.Factory): PlaybackAddress {
    when (delivery) {
        Stream.Delivery.Hls -> return PlaybackAddress(url, MimeTypes.APPLICATION_M3U8)
        Stream.Delivery.Dash -> return PlaybackAddress(url, MimeTypes.APPLICATION_MPD)
        else -> Unit
    }
    val asItCame = PlaybackAddress(url, null)
    if (Uri.parse(url).lastPathSegment.orEmpty().contains('.')) return asItCame
    return runInterruptible(Dispatchers.IO) { askServer(http) } ?: asItCame
}

@OptIn(UnstableApi::class)
private fun Stream.askServer(http: DataSource.Factory): PlaybackAddress? {
    val source = http.createDataSource()
    return try {
        source.open(DataSpec.Builder().setUri(url).setPosition(0).setLength(FIRST_BYTES.toLong()).build())
        val head = ByteArray(FIRST_BYTES)
        var read = 0
        while (read < head.size) {
            val n = source.read(head, read, head.size - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        mimeTypeOf(contentType, head.copyOf(read))?.let { PlaybackAddress(source.uri?.toString() ?: url, it) }
    } catch (e: IOException) {
        null
    } finally {
        try {
            source.close()
        } catch (e: IOException) {
            // Already finished with it.
        }
    }
}

/** HLS or DASH by what the server said it sent, or by how the body starts; null for anything else, a file. */
private fun mimeTypeOf(contentType: String?, head: ByteArray): String? {
    when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        in HLS_TYPES -> return MimeTypes.APPLICATION_M3U8
        "application/dash+xml" -> return MimeTypes.APPLICATION_MPD
    }
    val text = head.decodeToString().trimStart { it == BYTE_ORDER_MARK || it.isWhitespace() }
    return when {
        text.startsWith("#EXTM3U") -> MimeTypes.APPLICATION_M3U8
        text.startsWith("<") && "<MPD" in text -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
private val BYTE_ORDER_MARK = Char(0xFEFF)

/** Enough of the body to see a playlist's or manifest's opening. */
private const val FIRST_BYTES = 1024
