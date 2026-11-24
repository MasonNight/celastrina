package dev.celastrina.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.Row
import dev.celastrina.core.catalogue.Title

sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val row: Row) : HomeState
    data class Failed(val reason: CatalogueUnavailable.Reason) : HomeState
}

/** Home: the Movies row. Focus starts on [focusedTitle] when it is in the row, otherwise on the first card. */
@Composable
fun HomeScreen(state: HomeState, focusedTitle: Title?, onOpen: (Title) -> Unit) {
    when (state) {
        HomeState.Loading -> Message("Loading…")
        is HomeState.Failed -> Message(state.reason.message())
        is HomeState.Loaded -> TitleRow(state.row, focusedTitle, onOpen)
    }
}

@Composable
private fun TitleRow(row: Row, focusedTitle: Title?, onOpen: (Title) -> Unit) {
    val focusIndex = row.titles.indexOf(focusedTitle).coerceAtLeast(0)
    val focusedCard = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
    LaunchedEffect(row) { if (row.titles.isNotEmpty()) focusedCard.requestFocus() }
    Column(modifier = Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 48.dp))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.titles, key = { _, title -> title.tmdbId }) { index, title ->
                PosterCard(title, if (index == focusIndex) Modifier.focusRequester(focusedCard) else Modifier) { onOpen(title) }
            }
        }
    }
}

@Composable
private fun PosterCard(title: Title, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.width(140.dp).aspectRatio(2f / 3f)) {
        Box(Modifier.fillMaxSize()) {
            Text(
                title.name,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(title.posterUrl).crossfade(true).build(),
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
