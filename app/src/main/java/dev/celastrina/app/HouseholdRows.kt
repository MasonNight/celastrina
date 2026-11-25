package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.household.Watched

/** Home's rows that come from this TV rather than the Catalogue, stacked above the Catalogue's rows. */
enum class HouseholdRow(val label: String) {
    ContinueWatching("Continue watching"),
    Watchlist("Watchlist"),
}

/**
 * The Continue watching and Watchlist rows, each shown only when it has something in it. A Continue watching card
 * resumes playback ([onResume]); a Watchlist card opens the Title ([onOpen]).
 */
fun LazyListScope.householdRows(
    watched: List<Watched>,
    watchlist: List<Title>,
    model: HomeModel,
    onFocus: (Title) -> Unit,
    onOpen: (HouseholdRow, Title) -> Unit,
    onResume: (Watched) -> Unit,
) {
    if (watched.isNotEmpty()) {
        item(key = HouseholdRow.ContinueWatching) {
            HouseholdRowOf(
                HouseholdRow.ContinueWatching, watched, model,
                key = { "${it.watching.title.kind}-${it.watching.title.tmdbId}" },
                titleOf = { it.watching.title },
            ) { item, modifier ->
                ContinueWatchingCard(item, modifier, onFocus = { onFocus(item.watching.title) }, onClick = { onResume(item) })
            }
        }
    }
    if (watchlist.isNotEmpty()) {
        item(key = HouseholdRow.Watchlist) {
            HouseholdRowOf(HouseholdRow.Watchlist, watchlist, model, key = { "${it.kind}-${it.tmdbId}" }, titleOf = { it }) { title, modifier ->
                PosterCard(title, modifier, onFocus = { onFocus(title) }, onClick = { onOpen(HouseholdRow.Watchlist, title) })
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T : Any> HouseholdRowOf(
    row: HouseholdRow,
    items: List<T>,
    model: HomeModel,
    key: (T) -> Any,
    titleOf: (T) -> Title,
    card: @Composable (T, Modifier) -> Unit,
) {
    val target = model.focusTarget?.takeIf { it.first == row && !model.focusSearch }?.second
    val targetRequester = remember { FocusRequester() }
    var targetFocused by remember { mutableStateOf(false) }
    LaunchedEffect(target) { if (target != null) targetRequester.requestFocusUntil { targetFocused } }
    Column {
        Text(row.label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        LazyRow(
            state = model.householdRowStates.getValue(row),
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = key) { item ->
                val title = titleOf(item)
                card(item, if (title == target) Modifier.focusRequester(targetRequester).onFocusChanged { targetFocused = it.isFocused } else Modifier)
            }
        }
    }
}

/** A landscape card: the episode's still or the Title's backdrop, its name, and a bar showing how far playback got. */
@Composable
private fun ContinueWatchingCard(watched: Watched, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val watching = watched.watching
    Card(onClick = onClick, modifier = modifier.width(240.dp).aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus() }) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(watching.imageUrl).size(480, 270).crossfade(true).build(),
                contentDescription = watching.title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xDD000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(watching.title.name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                watching.episodeLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD0D3D9), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                watched.progress?.let { ProgressBar(it, Modifier.padding(top = 6.dp)) }
            }
            NotPlayableBadge(watching.title, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFF7FB8FF)))
    }
}
