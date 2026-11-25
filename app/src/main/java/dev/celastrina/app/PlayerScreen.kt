package dev.celastrina.app

import android.net.Uri
import android.util.Pair
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.celastrina.core.source.Stream

/** Plays one Stream full screen, with Media3's own controls on the remote's D-pad. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(stream: Stream) {
    val context = LocalContext.current
    val player = remember(stream) {
        // The Stream's headers (a token, a Referer) go with every request, its subtitles' included.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
            .apply {
                setMediaItem(stream.toMediaItem())
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                keepScreenOn = true
                // Stopgap until Failover (#29): say the Stream failed rather than sit on a black screen.
                setErrorMessageProvider { error -> Pair.create(0, "This Stream won't play (${error.errorCodeName}).") }
                requestFocus()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun Stream.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMimeType(
        when (delivery) {
            Stream.Delivery.Hls -> MimeTypes.APPLICATION_M3U8
            Stream.Delivery.Dash -> MimeTypes.APPLICATION_MPD
            else -> null
        },
    )
    .setSubtitleConfigurations(subtitles.map { it.toConfiguration() })
    .build()

private fun Stream.Subtitle.toConfiguration(): MediaItem.SubtitleConfiguration =
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
        .setMimeType(subtitleMimeType(url))
        .setLanguage(language)
        .setLabel(label)
        .build()

/** Sidecar subtitles are known by their file extension; WebVTT when there is none to go by. */
private fun subtitleMimeType(url: String): String = when (Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.TEXT_VTT
}
