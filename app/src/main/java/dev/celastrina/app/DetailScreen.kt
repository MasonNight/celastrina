package dev.celastrina.app

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
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup

private sealed interface DetailState {
    data object Loading : DetailState
    data class Loaded(val details: MovieDetails) : DetailState
    data class Failed(val reason: CatalogueUnavailable.Reason) : DetailState
}

/** A Movie's detail screen. Its Sources are asked as it opens, and Play is enabled by the first Stream to arrive. */
@Composable
fun DetailScreen(tmdbId: Int, catalogue: Catalogue, streamLookup: StreamLookup, onPlay: (Stream) -> Unit) {
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
        is DetailState.Loaded -> MovieDetail(s.details, streamLookup, onPlay)
    }
}

@Composable
private fun MovieDetail(details: MovieDetails, streamLookup: StreamLookup, onPlay: (Stream) -> Unit) {
    var streams by remember(details) { mutableStateOf(emptyList<Stream>()) }
    var lookupFinished by remember(details) { mutableStateOf(false) }
    LaunchedEffect(details) {
        streamLookup.lookUp(details.reference).collect { answer ->
            (answer.outcome as? LookupOutcome.Streams)?.let { streams = streams + it.streams }
        }
        lookupFinished = true
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
            Text(details.synopsis, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            PlayRow(streams, lookupFinished, onPlay)
        }
    }
}

@Composable
private fun PlayRow(streams: List<Stream>, lookupFinished: Boolean, onPlay: (Stream) -> Unit) {
    val play = remember { FocusRequester() }
    val playable = streams.isNotEmpty()
    LaunchedEffect(playable) { if (playable) play.requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onPlay(streams.first()) }, enabled = playable, modifier = Modifier.focusRequester(play)) {
            Text("Play")
        }
        // Stopgap until the lookup status line (#27): just enough to explain a disabled Play.
        when {
            playable -> Unit
            !lookupFinished -> Text("Looking for Streams…", style = MaterialTheme.typography.bodyMedium)
            else -> Text("None of your Sources can play this.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun MovieDetails.metadataLine(): String = listOfNotNull(
    title.year?.toString(),
    runtimeMinutes?.let(::runtimeText),
    genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")
