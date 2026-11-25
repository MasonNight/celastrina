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
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup

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
    var lookup by remember(details) { mutableStateOf<Lookup?>(null) }
    var choosing by remember(details) { mutableStateOf(false) }
    LaunchedEffect(details) {
        streamLookup.watch(details.reference).collect { lookup = it }
    }

    if (choosing) {
        BackHandler { choosing = false }
        ChooseStreamScreen(details.title.name, lookup, onPick = onPlay)
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
            Text(details.synopsis, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            PlayRow(lookup, onPlay, onChoose = { choosing = true })
        }
    }
}

/** Play, Choose Stream, and the one-line summary of the Lookup. Play takes the first Stream in the Household's order. */
@Composable
fun PlayRow(lookup: Lookup?, onPlay: (Stream) -> Unit, onChoose: () -> Unit, label: String? = null) {
    val play = remember { FocusRequester() }
    val choose = remember { FocusRequester() }
    val best = lookup?.streams?.firstOrNull()
    LaunchedEffect(best != null) { (if (best != null) play else choose).requestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Button(onClick = { best?.let(onPlay) }, enabled = best != null, modifier = Modifier.focusRequester(play)) { Text("Play") }
        Button(onClick = onChoose, modifier = Modifier.focusRequester(choose)) { Text("Choose Stream") }
        Text(lookup?.statusLine ?: "Asking your Sources…", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

private fun MovieDetails.metadataLine(): String = listOfNotNull(
    title.year?.toString(),
    runtimeMinutes?.let(::runtimeText),
    genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")
