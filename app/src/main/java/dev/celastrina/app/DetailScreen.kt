package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnreadable
import dev.celastrina.core.catalogue.MovieDetails
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.candidates
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watched
import dev.celastrina.core.household.Watching
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream

private sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val details: MovieDetails) : DetailState
    data class Failed(val reason: CatalogueUnreadable.Reason) : DetailState
}

/**
 * A Movie's detail screen. Its Sources are asked as it opens: Play is enabled by the first Stream to arrive, and Choose
 * Stream lists them all as they come. Play resumes where [continueWatching] says playback got to, and + Watchlist adds
 * the Movie to [watchlist] or takes it off.
 */
@Composable
fun DetailScreen(
    tmdbId: Int,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    autoPlay: Boolean,
    onPlay: (PlayRequest) -> Unit,
) {
    var state by remember(tmdbId) { mutableStateOf<DetailState>(DetailState.Loading) }
    LaunchedEffect(tmdbId) {
        state = try {
            DetailState.Loaded(catalogue.movieDetails(tmdbId))
        } catch (e: CatalogueUnreadable) {
            DetailState.Failed(e.reason)
        }
    }
    when (val s = state) {
        DetailState.Loading -> Message("Loading…")
        is DetailState.Failed -> Message(s.reason.message())
        is DetailState.Loaded -> MovieDetail(s.details, lookUp, failoverMemory, continueWatching, watchlist, autoPlay, onPlay)
    }
}

@Composable
private fun MovieDetail(
    details: MovieDetails,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    autoPlay: Boolean,
    onPlay: (PlayRequest) -> Unit,
) {
    val titleLookup = remember(details) { lookUp(details.reference) }
    val lookup by titleLookup.lookups.collectAsState()
    var choosing by remember(details) { mutableStateOf(false) }
    val watched = remember(details) { continueWatching.of(details.reference) }
    fun play(stream: Stream, handPicked: Boolean) = onPlay(
        PlayRequest(
            stream, handPicked, titleLookup, details.title.name,
            startAtMillis = watched?.resumeAtMillis ?: 0,
            watching = Watching(details.title, details.reference, episodeLabel = null, imageUrl = details.backdropUrl),
        ),
    )

    if (choosing) {
        BackHandler { choosing = false }
        ChooseStreamScreen(details.title.name, lookup) { play(it, handPicked = true) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            // The backdrop is decoded at about 960×540, which is plenty on a TV and keeps memory down.
            model = ImageRequest.Builder(LocalContext.current).data(details.backdropUrl).size(960, 540).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.6f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent),
            ),
        )
        Column(
            modifier = Modifier.fillMaxWidth(0.55f).fillMaxHeight().padding(start = 48.dp, top = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
        ) {
            TitleLogo(details.title.name, details.logoUrl, MaterialTheme.typography.displaySmall, maxHeight = 120.dp, maxWidth = 420.dp)
            Text(details.metadataLine(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
            NotPlayableBadge(details.title)
            Text(details.synopsis, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            PlayRow(lookup, failoverMemory, onPlay = { play(it, handPicked = false) }, onChoose = { choosing = true }, watched = watched, autoPlay = autoPlay) {
                WatchlistButton(details.title, watchlist)
            }
        }
    }
}

/**
 * Play, Choose Stream, [more] buttons, and the one-line summary of the Lookup. Play takes Failover's first candidate
 * (see [firstChoice]), with the Stream that last worked first, and says Resume when playback got partway through.
 */
@Composable
fun PlayRow(
    lookup: Lookup?,
    failoverMemory: FailoverMemory,
    onPlay: (Stream) -> Unit,
    onChoose: () -> Unit,
    watched: Watched? = null,
    autoPlay: Boolean = false,
    more: @Composable () -> Unit = {},
) {
    val play = remember { FocusRequester() }
    val choose = remember { FocusRequester() }
    val best = lookup?.firstChoice(failoverMemory, watched?.lastWorked)
    val resumes = watched != null && watched.resumeAtMillis > 0
    LaunchedEffect(best != null) {
        if (autoPlay && best != null) onPlay(best)
        else (if (best != null) play else choose).requestFocusOnceShown()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { best?.let(onPlay) }, enabled = best != null, modifier = Modifier.focusRequester(play)) { Text(if (resumes) "Resume" else "Play") }
        Button(onClick = onChoose, modifier = Modifier.focusRequester(choose)) { Text("Choose Stream") }
        more()
        Text(lookup.statusLine(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

/**
 * The Stream Play starts with: the one that [lastWorked] for this Title or episode, else the first in the Household's
 * order that this TV can decode, with Streams that failed here in the last 24 hours last. A Stream this TV can't decode
 * is never Play's choice (story 35); Choose Stream still lists it.
 */
fun Lookup.firstChoice(failoverMemory: FailoverMemory, lastWorked: WorkedStream? = null): Stream? =
    candidates(MediaCodecDecoders, failoverMemory, lastWorked).firstOrNull()

/** + Watchlist, which adds [title] to the Watchlist, or ✓ Watchlist, which takes it off. */
@Composable
fun WatchlistButton(title: Title, watchlist: Watchlist, modifier: Modifier = Modifier) {
    val titles by watchlist.titles.collectAsState()
    val listed = titles.any { it.kind == title.kind && it.tmdbId == title.tmdbId }
    Button(onClick = { if (listed) watchlist.remove(title) else watchlist.add(title) }, modifier = modifier) {
        Text(if (listed) "✓ Watchlist" else "+ Watchlist")
    }
}

private fun MovieDetails.metadataLine(): String = listOfNotNull(
    title.year?.toString(),
    runtimeMinutes?.let(::runtimeText),
    genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")
