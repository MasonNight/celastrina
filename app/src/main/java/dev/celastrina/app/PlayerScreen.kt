package dev.celastrina.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.FailoverStep
import dev.celastrina.core.failover.PlayerEvent
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watching
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

/**
 * What Play or Choose Stream asks the player for: [stream] of [title], from [lookup], which may still be running so
 * Streams that arrive late can still be failed over to, and which Failover can ask again when it gets old.
 * [handPicked] when the Household chose it in Choose Stream. Playback starts [startAtMillis] into the Title. Continue
 * watching remembers how far playback gets in [watching], and on which Stream.
 */
data class PlayRequest(
    val stream: Stream,
    val handPicked: Boolean,
    val lookup: TitleLookup,
    val title: String,
    val startAtMillis: Long = 0,
    val watching: Watching? = null,
)

/**
 * Plays the requested Stream full screen, with Media3's own controls on the remote's D-pad. When a Stream stops
 * working, Failover moves playback to the next one and a notice says so; Back leaves the player, which cancels it.
 * When Failover gives up, the error screen's Retry and Choose Stream start a new playback session here, from where
 * playback had got to. Every 10 s, and on leaving, [continueWatching] saves the position and the Stream that worked.
 */
@Composable
fun PlayerScreen(request: PlayRequest, failoverMemory: FailoverMemory, continueWatching: ContinueWatching, logger: Logger) {
    var session by remember(request) { mutableStateOf(Session(request, number = 0)) }
    key(session) {
        PlaybackSession(session.request, failoverMemory, continueWatching, logger) { session = Session(it, session.number + 1) }
    }
}

/** One playback session: a [request] and its own Failover. [number] tells a retry of the same request apart. */
private data class Session(val request: PlayRequest, val number: Int)

@OptIn(UnstableApi::class)
@Composable
private fun PlaybackSession(
    request: PlayRequest,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    logger: Logger,
    playAgain: (PlayRequest) -> Unit,
) {
    val context = LocalContext.current
    val failover = remember {
        Failover(
            checkNotNull(request.lookup.lookups.value),
            request.stream,
            request.handPicked,
            MediaCodecDecoders,
            failoverMemory,
            Clock.System,
            logger,
            askSourcesAgain = request.lookup.askAgain,
            startAtMillis = request.startAtMillis,
        )
    }
    var playback by remember {
        mutableStateOf<Playback?>(Playback(request.stream, request.startAtMillis, checkAddress = failover.addressCheck != null))
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var gaveUp by remember { mutableStateOf<FailoverStep.GaveUp?>(null) }
    var durationMillis by remember { mutableStateOf<Long?>(null) }
    fun saveProgress() {
        val watching = request.watching ?: return
        val worked = failover.worked ?: return
        continueWatching.save(watching, worked, failover.positionMillis, durationMillis)
    }
    val scope = rememberCoroutineScope()
    val addressChecks = remember(scope) { AddressChecks(scope) }
    fun take(next: FailoverStep?) {
        notice = failover.notice?.text
        when (next) {
            null -> Unit
            is FailoverStep.Play -> playback = Playback(next.stream, next.startAtMillis, checkAddress = false)
            is FailoverStep.Switch ->
                playback = Playback(next.stream, next.startAtMillis, checkAddress = failover.addressCheck != null)
            is FailoverStep.Waiting -> playback = null
            is FailoverStep.GaveUp -> {
                gaveUp = next
                playback = null
            }
        }
        addressChecks.update(failover) { take(failover.on(it)) }
    }

    LaunchedEffect(failover) { request.lookup.lookups.filterNotNull().collect { take(failover.lookupChanged(it)) } }

    val failure = gaveUp
    if (failure != null) {
        // Leaving the player here releases it.
        GiveUpScreen(request.title, failure, request.lookup, failoverMemory) { stream, handPicked ->
            playAgain(request.copy(stream = stream, handPicked = handPicked, startAtMillis = failover.positionMillis))
        }
        return
    }

    // Each attempt either checks the address or owns a player with its Stream's headers.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    val attempt = playback
    LaunchedEffect(attempt) {
        if (attempt == null || attempt.checkAddress) return@LaunchedEffect
        val made = newPlayer(context, attempt.stream, attempt.startAtMillis)
        val events = Media3FailoverEvents(made) { if (playback === attempt) take(failover.on(it)) }
        made.addListener(events)
        player = made
        try {
            awaitCancellation()
        } finally {
            made.removeListener(events)
            if (player === made) player = null
            made.release()
            saveProgress()
        }
    }
    // Ticks also reach Failover during the Address check and while waiting for Sources.
    LaunchedEffect(attempt) {
        var seconds = 0
        while (true) {
            delay(1_000)
            if (playback !== attempt) return@LaunchedEffect
            val current = player
            current?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMillis = it }
            take(failover.on(PlayerEvent.Position(current?.currentPosition ?: failover.startAtMillis)))
            if (++seconds % SAVE_EVERY_SECONDS == 0) saveProgress()
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
        notice?.let { CornerNotice(it) }
    }
}

private data class Playback(val stream: Stream, val startAtMillis: Long, val checkAddress: Boolean)

/** How often Continue watching saves where playback has got to. */
private const val SAVE_EVERY_SECONDS = 10

/**
 * A player already playing [stream] from [startAtMillis]. Create it on the main thread, which owns the player.
 */
@OptIn(UnstableApi::class)
internal fun newPlayer(context: android.content.Context, stream: Stream, startAtMillis: Long): ExoPlayer {
    // The Stream's headers (a token, a Referer) go with every request, its subtitles' included.
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(stream.headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
        .apply {
            setMediaItem(stream.toMediaItem(), startAtMillis)
            prepare()
            playWhenReady = true
        }
}

private fun Stream.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMimeType(when (delivery) {
        Stream.Delivery.Hls -> MimeTypes.APPLICATION_M3U8
        Stream.Delivery.Dash -> MimeTypes.APPLICATION_MPD
        // An explicit MIME type makes Media3 use the settled delivery even if the address suggests an adaptive format.
        Stream.Delivery.Progressive -> "application/octet-stream"
        else -> null
    })
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
