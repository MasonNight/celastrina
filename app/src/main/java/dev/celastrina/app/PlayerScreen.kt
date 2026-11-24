package dev.celastrina.app

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
        // Sending a Stream's headers and loading its sidecar subtitles come with #27.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
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
    .build()
