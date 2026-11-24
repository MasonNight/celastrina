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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.Row
import dev.celastrina.core.catalogue.Title

private sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val row: Row) : HomeState
    data class Failed(val reason: CatalogueUnavailable.Reason) : HomeState
}

@Composable
fun HomeScreen(catalogue: Catalogue) {
    var state by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    LaunchedEffect(catalogue) {
        state = try {
            HomeState.Loaded(catalogue.moviesRow())
        } catch (e: CatalogueUnavailable) {
            HomeState.Failed(e.reason)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Black, contentColor = Color.White)) {
            when (val s = state) {
                HomeState.Loading -> Message("Loading…")
                is HomeState.Failed -> Message(s.reason.message())
                is HomeState.Loaded -> TitleRow(s.row)
            }
        }
    }
}

@Composable
private fun TitleRow(row: Row) {
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(row) { if (row.titles.isNotEmpty()) firstCard.requestFocus() }
    Column(modifier = Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.titles, key = { _, title -> title.tmdbId }) { index, title ->
                PosterCard(title, if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
            }
        }
    }
}

@Composable
private fun PosterCard(title: Title, modifier: Modifier) {
    Card(onClick = {}, modifier = modifier.width(140.dp).aspectRatio(2f / 3f)) {
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
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun CatalogueUnavailable.Reason.message() = when (this) {
    CatalogueUnavailable.Reason.Unreachable -> "Can't reach TMDB. Check the TV's network connection."
    CatalogueUnavailable.Reason.KeyRejected -> "TMDB rejected the key."
    CatalogueUnavailable.Reason.ServiceError -> "TMDB isn't answering properly right now."
}
