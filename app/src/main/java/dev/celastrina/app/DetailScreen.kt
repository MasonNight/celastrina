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
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.MovieDetails
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.candidates
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream

private sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val details: MovieDetails) : DetailState
    data class Failed(val reason: CatalogueUnavailable.Reason) : DetailState
}

/**
 * A Movie's detail screen. Its Sources are asked as it opens: Play is enabled by the first Stream to arrive, and Choose
 * Stream lists them all as they come.
 */
@Composable
fun DetailScreen(
    tmdbId: Int,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    onPlay: (PlayRequest) -> Unit,
) {
    var state by remember(tmdbId) { mutableStateOf<DetailState>(DetailState.Loading) }
    LaunchedEffect(tmdbId) {
        state = try {
            DetailState.Loaded(catalogue.movieDetails(tmdbId))
        } catch (e: CatalogueUnavailable) {
            DetailState.Failed(e.reason)
        }
    }
    when (val s = state) {
        DetailState.Loading -> Message("Loading…")
        is DetailState.Failed -> Message(s.reason.message())
        is DetailState.Loaded -> MovieDetail(s.details, lookUp, failoverMemory, onPlay)
    }
}

@Composable
private fun MovieDetail(details: MovieDetails, lookUp: (TitleReference) -> TitleLookup, failoverMemory: FailoverMemory, onPlay: (PlayRequest) -> Unit) {
    val titleLookup = remember(details) { lookUp(details.reference) }
    val lookup by titleLookup.lookups.collectAsState()
    var choosing by remember(details) { mutableStateOf(false) }
    fun play(stream: Stream, handPicked: Boolean) = onPlay(PlayRequest(stream, handPicked, titleLookup, details.title.name))

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
            Text(details.title.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(details.metadataLine(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
            NotPlayableBadge(details.title)
            Text(details.synopsis, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            PlayRow(lookup, failoverMemory, onPlay = { play(it, handPicked = false) }, onChoose = { choosing = true })
        }
    }
}

/**
 * Play, Choose Stream, and the one-line summary of the Lookup. Play takes Failover's first candidate (see [firstChoice]).
 */
@Composable
fun PlayRow(lookup: Lookup?, failoverMemory: FailoverMemory, onPlay: (Stream) -> Unit, onChoose: () -> Unit, label: String? = null) {
    val play = remember { FocusRequester() }
    val choose = remember { FocusRequester() }
    val best = lookup?.firstChoice(failoverMemory)
    LaunchedEffect(best != null) { (if (best != null) play else choose).requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Button(onClick = { best?.let(onPlay) }, enabled = best != null, modifier = Modifier.focusRequester(play)) { Text("Play") }
        Button(onClick = onChoose, modifier = Modifier.focusRequester(choose)) { Text("Choose Stream") }
        Text(lookup?.statusLine ?: "Asking your Sources…", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

/**
 * The Stream Play starts with: the first in the Household's order that this TV can decode, with Streams that failed here
 * in the last 24 hours last, or the first Stream at all when the TV seems to decode none.
 */
fun Lookup.firstChoice(failoverMemory: FailoverMemory): Stream? = candidates(MediaCodecDecoders, failoverMemory).firstOrNull() ?: streams.firstOrNull()

private fun MovieDetails.metadataLine(): String = listOfNotNull(
    title.year?.toString(),
    runtimeMinutes?.let(::runtimeText),
    genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")
