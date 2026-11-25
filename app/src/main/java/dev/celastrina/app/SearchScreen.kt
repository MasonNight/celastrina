package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.celastrina.core.catalogue.Search
import dev.celastrina.core.catalogue.SearchResults
import dev.celastrina.core.catalogue.Title

private val SearchBackground = Color(0xFF0E0F13)
private val SearchDim = Color(0xFF9AA0AA)

/** The keyboard's character keys, six to a row: A–Z, then 0–9. */
private val CharacterKeys = (('A'..'Z') + ('0'..'9')).chunked(6)

/**
 * The search screen: the app's own grid keyboard on the left, which edits [search]'s query, and its live results on
 * the right. Selecting a card calls [onOpen]. Coming back from a Title opened here ([opened]) puts focus on its card,
 * with the results scrolled as they were ([gridState]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(search: Search, gridState: LazyGridState, opened: Title?, onOpen: (Title) -> Unit) {
    val query by search.query.collectAsState()
    val results by search.results.collectAsState()
    val firstKey = remember { FocusRequester() }
    val openedCard = remember { FocusRequester() }
    val titles = when (val r = results) {
        is SearchResults.Trending -> r.titles
        is SearchResults.Found -> r.titles
        else -> emptyList()
    }
    val focusOpened = opened != null && opened in titles
    LaunchedEffect(Unit) {
        runCatching { if (focusOpened) openedCard.requestFocus() else firstKey.requestFocus() }
            .onFailure { runCatching { firstKey.requestFocus() } }
    }

    Row(Modifier.fillMaxSize().background(SearchBackground).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(Modifier.width(340.dp).focusRestorer(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (query.isEmpty()) "Search Movies and Series" else query.takeLast(22),
                style = MaterialTheme.typography.headlineSmall,
                color = if (query.isEmpty()) SearchDim else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            CharacterKeys.forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.forEachIndexed { column, key ->
                        Key(
                            key.toString(),
                            Modifier.size(48.dp).then(if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier),
                        ) { search.type(key.lowercaseChar()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("Space", Modifier.width(104.dp).height(48.dp)) { search.type(' ') }
                Key("Delete", Modifier.width(104.dp).height(48.dp)) { search.delete() }
                Key("Clear", Modifier.width(104.dp).height(48.dp)) { search.clear() }
            }
        }
        Column(Modifier.padding(start = 32.dp).fillMaxSize()) {
            Text(
                heading(results),
                style = MaterialTheme.typography.titleMedium,
                color = if (titles.isEmpty()) SearchDim else Color.White,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                state = gridState,
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(titles, key = { "${it.kind}-${it.tmdbId}" }) { title ->
                    ResultCard(
                        title,
                        modifier = if (focusOpened && title == opened) Modifier.focusRequester(openedCard) else Modifier,
                        onClick = { onOpen(title) },
                    )
                }
            }
        }
    }
}

private fun heading(results: SearchResults) = when (results) {
    SearchResults.Loading -> "Loading…"
    is SearchResults.Trending -> "Trending this week"
    is SearchResults.Found -> "Titles matching '${results.query}'"
    is SearchResults.NoMatches -> "No Titles match '${results.query}'"
    // Stopgap until the offline message (#24), the same as home's.
    is SearchResults.Unavailable -> results.reason.message()
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF23252B),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * A result's poster, with its name on it until the poster loads and the Not playable badge where one is remembered,
 * and a Movie or Series label beneath.
 */
@Composable
private fun ResultCard(title: Title, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    title.name,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
                AsyncImage(model = title.posterUrl, contentDescription = title.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                NotPlayableBadge(title, Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
        Text(
            when (title.kind) {
                Title.Kind.Movie -> "Movie"
                Title.Kind.Series -> "Series"
            },
            style = MaterialTheme.typography.labelSmall,
            color = SearchDim,
        )
    }
}
