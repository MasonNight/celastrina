package dev.celastrina.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.FailoverNotice
import dev.celastrina.core.failover.FailoverStep
import dev.celastrina.core.failover.PlayerEvent
import dev.celastrina.core.failover.StreamChoice
import dev.celastrina.core.failover.oneClickChoice
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watching
import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

/**
 * What Play, Choose Stream or an episode card asks the player for: [stream] of [title], from [lookup], which may still
 * be running so Streams that arrive late can still be failed over to, and which Failover can ask again when it gets
 * old. Playback starts [startAtMillis] into the Title. Continue watching remembers how far playback gets in [watching],
 * and on which Stream.
 */
data class PlayRequest(
    val stream: StreamToPlay,
    val lookup: TitleLookup,
    val title: String,
    val startAtMillis: Long = 0,
    val watching: Watching? = null,
)

/** Which Stream a [PlayRequest] plays. */
sealed interface StreamToPlay {
    /** [stream], chosen by the Household in Choose Stream when [handPicked], otherwise by Play or resuming. */
    data class Chosen(val stream: Stream, val handPicked: Boolean) : StreamToPlay

    /**
     * None yet, as when an episode card is clicked: the player chooses one itself from the Lookup as it arrives (see
     * [oneClickChoice]), putting the Stream that [lastWorked] first.
     */
    data class ToBeChosen(val lastWorked: WorkedStream?) : StreamToPlay
}

/**
 * Plays the requested Stream full screen, with Media3's own controls on the remote's D-pad. When a Stream stops
 * working, Failover moves playback to the next one and a notice says so; Back leaves the player, which cancels it.
 * When Failover gives up, the error screen's Retry and Choose Stream start a new playback session here, from where
 * playback had got to. Every 10 s, and on leaving, [continueWatching] saves the position and the Stream that worked.
 *
 * A request for a Stream [StreamToPlay.ToBeChosen] first shows "Finding a Stream…" over its picture until one is chosen. Leaving before then
 * cancels the Lookup. When no Source has anything to play, it says what happened at each, with [onBack] and Choose Stream.
 */
@Composable
fun PlayerScreen(request: PlayRequest, failoverMemory: FailoverMemory, continueWatching: ContinueWatching, logger: Logger, onBack: () -> Unit) {
    var session by remember(request) { mutableStateOf<Session?>(null) }
    val current = session ?: when (val stream = request.stream) {
        is StreamToPlay.Chosen -> Session(request, stream, number = 0)
        is StreamToPlay.ToBeChosen -> {
            FindingStream(request, stream.lastWorked, failoverMemory, onBack) { session = Session(request, it, number = 0) }
            return
        }
    }
    key(current) {
        PlaybackSession(current.request, current.chosen, failoverMemory, continueWatching, logger) { chosen, startAtMillis ->
            session = Session(current.request.copy(startAtMillis = startAtMillis), chosen, current.number + 1)
        }
    }
}

/**
 * One playback session: [request] playing the [chosen] Stream, with its own Failover. [number] tells a retry of the
 * same request apart.
 */
private data class Session(val request: PlayRequest, val chosen: StreamToPlay.Chosen, val number: Int)

/**
 * The episode's picture with "Finding a Stream…" while [oneClickChoice] waits, then [play] with its choice. When nothing
 * can play, the same kind of screen as Give up. Leaving before a Stream is chosen cancels the Lookup.
 */
@Composable
private fun FindingStream(
    request: PlayRequest,
    lastWorked: WorkedStream?,
    failoverMemory: FailoverMemory,
    onBack: () -> Unit,
    play: (StreamToPlay.Chosen) -> Unit,
) {
    val lookup by request.lookup.lookups.collectAsState()
    var chosen by remember { mutableStateOf(false) }
    fun choose(stream: Stream, handPicked: Boolean) {
        chosen = true
        play(StreamToPlay.Chosen(stream, handPicked))
    }
    DisposableEffect(request) { onDispose { if (!chosen) request.lookup.cancel() } }
    // The choice stops waiting once enough time has passed, so the time is checked again as it passes, on the clock
    // the Lookup was asked by.
    var now by remember { mutableLongStateOf(Clock.System.nowMillis()) }
    LaunchedEffect(request) {
        while (true) {
            delay(250)
            now = Clock.System.nowMillis()
        }
    }
    val choice = lookup?.oneClickChoice(MediaCodecDecoders, failoverMemory, lastWorked, now) ?: StreamChoice.Waiting
    LaunchedEffect(choice) { if (choice is StreamChoice.Play) choose(choice.stream, handPicked = false) }
    if (choice is StreamChoice.NothingToPlay) {
        NothingToPlayScreen(request.title, choice.sources, request.lookup, onBack) { choose(it, handPicked = true) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(request.watching?.imageUrl).size(960, 540).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(Modifier.align(Alignment.Center).padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(request.title, style = MaterialTheme.typography.headlineSmall)
            Text("Finding a Stream…", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlaybackSession(
    request: PlayRequest,
    chosen: StreamToPlay.Chosen,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    logger: Logger,
    playAgain: (StreamToPlay.Chosen, startAtMillis: Long) -> Unit,
) {
    val stream = chosen.stream
    val context = LocalContext.current
    val failover = remember {
        Failover(
            checkNotNull(request.lookup.lookups.value),
            stream,
            chosen.handPicked,
            MediaCodecDecoders,
            failoverMemory,
            Clock.System,
            logger,
            askSourcesAgain = request.lookup.askAgain,
            startAtMillis = request.startAtMillis,
        )
    }
    var playback by remember {
        mutableStateOf<Playback?>(Playback(stream, request.startAtMillis, checkAddress = failover.addressCheck != null))
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
        notice = failover.notice?.message()
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
            playAgain(StreamToPlay.Chosen(stream, handPicked), failover.positionMillis)
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
        .setDefaultRequestProperties(stream.playbackHeaders())
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

private fun FailoverNotice.message(): String {
    val failed = if (awayFromHouseholdsChoice) "The Stream you chose from $failedSourceName" else failedSourceName
    return when (val attempt = next) {
        is FailoverNotice.Next.Trying -> {
            val progress = "${attempt.sourceName}: trying another Stream (${attempt.tried} of ${attempt.known})…"
            if (awayFromHouseholdsChoice) "$failed isn't working. $progress" else progress
        }
        FailoverNotice.Next.WaitingForSources -> "$failed isn't working, waiting for your other Sources…"
    }
}
