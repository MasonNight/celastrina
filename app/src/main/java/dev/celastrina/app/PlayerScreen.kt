package dev.celastrina.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.FailoverStep
import dev.celastrina.core.failover.PlayerEvent
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * What Play or Choose Stream asks the player for: [stream], from a [lookup] that may still be running, so Streams that
 * arrive late can still be failed over to. [handPicked] when the Household chose it in Choose Stream.
 */
data class PlayRequest(val stream: Stream, val handPicked: Boolean, val lookup: StateFlow<Lookup?>)

/**
 * Plays the requested Stream full screen, with Media3's own controls on the remote's D-pad. When a Stream stops
 * working, Failover moves playback to the next one and a notice says so; Back leaves the player, which cancels it.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, logger: Logger) {
    val context = LocalContext.current
    val failover = remember(request) {
        Failover(checkNotNull(request.lookup.value), request.stream, request.handPicked, MediaCodecDecoders, Clock.System, logger)
    }
    var playback by remember(request) { mutableStateOf(Playback(request.stream, startAtMillis = 0)) }
    var step by remember(request) { mutableStateOf<FailoverStep?>(null) }
    fun take(next: FailoverStep?) {
        next ?: return
        step = next
        if (next is FailoverStep.Switch) playback = Playback(next.stream, next.startAtMillis)
    }

    LaunchedEffect(failover) { request.lookup.filterNotNull().collect { take(failover.lookupChanged(it)) } }

    // A new player for each Stream tried, since each sends its own headers.
    val player = remember(playback) { newPlayer(context, playback) }
    DisposableEffect(player) {
        val events = Media3FailoverEvents(player) { take(failover.on(it)) }
        player.addListener(events)
        onDispose {
            player.removeListener(events)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            take(failover.on(PlayerEvent.Position(player.currentPosition)))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    requestFocus()
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        step?.let { FailoverNotice(it) { step = null } }
    }
}

/**
 * The notice for a Failover step. A switch's notice goes after about 6 s; one saying Failover is waiting for Sources,
 * or has nothing left to try, stays until the next step. (Giving up properly, with its own screen, is #30.)
 */
@Composable
private fun FailoverNotice(step: FailoverStep, onGone: () -> Unit) {
    if (step is FailoverStep.Switch) {
        LaunchedEffect(step) {
            delay(NOTICE_MILLIS)
            onGone()
        }
    }
    CornerNotice(step.notice.text)
}

private data class Playback(val stream: Stream, val startAtMillis: Long)

@OptIn(UnstableApi::class)
private fun newPlayer(context: android.content.Context, playback: Playback): ExoPlayer {
    // The Stream's headers (a token, a Referer) go with every request, its subtitles' included.
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(playback.stream.headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
        .apply {
            setMediaItem(playback.stream.toMediaItem(), playback.startAtMillis)
            prepare()
            playWhenReady = true
        }
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
