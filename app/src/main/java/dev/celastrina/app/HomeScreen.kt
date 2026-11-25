package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.Hero
import dev.celastrina.core.catalogue.HomeRow
import dev.celastrina.core.catalogue.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

private val Background = Color(0xFF0E0F13)
private val Dim = Color(0xFF9AA0AA)

/** How long focus must rest on a card before the hero follows it, so flicking along a row stays smooth. */
private const val HERO_REST_MILLIS = 300L

/** How often rows that couldn't reach TMDB try again. */
private const val OFFLINE_RETRY_MILLIS = 15_000L

/** How close to a row's end focus may get before the row loads more. */
private const val LOAD_MORE_WITHIN = 8

/**
 * Home, Variant A "Hero + stacked rows": the focused Title's backdrop, logo, metadata and blurb fill the top, above
 * the Movies, Series, Anime and Cartoons rows.
 */
@Composable
fun HomeScreen(model: HomeModel, onOpen: (HomeRow, Title) -> Unit) {
    val allFailed by remember { derivedStateOf { model.rows.values.all { it is RowState.Failed } } }
    val offline by remember {
        derivedStateOf { model.rows.values.any { it == RowState.Failed(CatalogueUnavailable.Reason.Unreachable) } }
    }
    // Rows that couldn't reach TMDB, and had nothing cached, load once the TV is back online.
    LaunchedEffect(offline) {
        while (offline) {
            delay(OFFLINE_RETRY_MILLIS)
            model.retryFailed()
        }
    }
    if (allFailed) {
        val reason = (model.rows.values.first() as RowState.Failed).reason
        if (reason == CatalogueUnavailable.Reason.Unreachable) {
            Message("You're offline", "Nothing is saved on this TV to show yet. Home fills in when the TV is back online.")
        } else {
            Message(reason.message())
        }
        return
    }
    // A flow rather than state, so moving focus doesn't recompose home; only a rested focus reaches the hero.
    val focused = remember { MutableStateFlow<Title?>(null) }
    LaunchedEffect(focused) {
        focused.collectLatest { title ->
            if (title == null) return@collectLatest
            delay(HERO_REST_MILLIS)
            model.showHero(title)
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        HeroBackdrop(model.hero?.second, Modifier.align(Alignment.TopEnd).fillMaxWidth().fillMaxHeight(0.62f))
        Column(Modifier.fillMaxSize()) {
            HeroText(model.hero, Modifier.padding(start = 48.dp, top = 32.dp, end = 48.dp).fillMaxWidth(0.45f).height(200.dp))
            LazyColumn(
                state = model.columnState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(HomeRow.entries, key = { it }) { homeRow ->
                    TitleRow(
                        homeRow = homeRow,
                        state = model.rows[homeRow] ?: RowState.Loading,
                        listState = model.rowStates.getValue(homeRow),
                        focusTarget = model.focusTarget,
                        onLoadMore = { model.loadMore(homeRow) },
                        onFocus = { focused.value = it },
                        onOpen = { onOpen(homeRow, it) },
                        onFocusTargetMissing = { title -> if (model.focusTarget == null) model.focusTarget = homeRow to title },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBackdrop(hero: Hero?, modifier: Modifier) {
    Box(modifier) {
        // Decoded at about 960×540, not the screen's full size: on the AFTTI43 that halves the jank of a hero change.
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(hero?.backdropUrl).size(960, 540).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Background, 0.55f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Background)))
    }
}

@Composable
private fun HeroText(shown: Pair<Title, Hero>?, modifier: Modifier) {
    val (title, hero) = shown ?: return
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        HeroLogo(hero)
        Spacer(Modifier.height(10.dp))
        Text(metadataLine(title, hero), style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(
            hero.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFD0D3D9),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Title's logo, or its name as text when there is no logo or it won't load. */
@Composable
private fun HeroLogo(hero: Hero) {
    var logoFailed by remember(hero.logoUrl) { mutableStateOf(false) }
    if (hero.logoUrl == null || logoFailed) {
        Text(hero.name, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else {
        AsyncImage(
            model = hero.logoUrl,
            contentDescription = hero.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onState = { if (it is AsyncImagePainter.State.Error) logoFailed = true },
            modifier = Modifier.heightIn(max = 90.dp).widthIn(max = 360.dp),
        )
    }
}

private fun metadataLine(title: Title, hero: Hero): String = listOfNotNull(
    hero.year?.toString(),
    when (title.kind) {
        Title.Kind.Movie -> hero.runtimeMinutes?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
        Title.Kind.Series -> hero.seasonCount?.let { if (it == 1) "1 season" else "$it seasons" } ?: "Series"
    },
    hero.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TitleRow(
    homeRow: HomeRow,
    state: RowState,
    listState: LazyListState,
    focusTarget: Pair<HomeRow, Title>?,
    onLoadMore: () -> Unit,
    onFocus: (Title) -> Unit,
    onOpen: (Title) -> Unit,
    onFocusTargetMissing: (Title) -> Unit,
) {
    Column {
        Text(homeRow.label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        when (state) {
            RowState.Loading -> RowMessage("Loading…")
            is RowState.Failed -> RowMessage(state.reason.message())
            is RowState.Loaded -> {
                val titles = state.row.titles
                if (titles.isEmpty()) {
                    RowMessage("Nothing here right now.")
                    return@Column
                }
                LaunchedEffect(Unit) { onFocusTargetMissing(titles.first()) }
                LaunchedEffect(listState, titles.size) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { lastVisible -> if (lastVisible >= titles.size - LOAD_MORE_WITHIN) onLoadMore() }
                }
                val target = focusTarget?.takeIf { it.first == homeRow }?.second
                val targetRequester = remember { FocusRequester() }
                LaunchedEffect(target) { if (target != null) runCatching { targetRequester.requestFocus() } }
                LazyRow(
                    state = listState,
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(titles, key = { "${it.kind}-${it.tmdbId}" }) { title ->
                        PosterCard(
                            title,
                            modifier = if (title == target) Modifier.focusRequester(targetRequester) else Modifier,
                            onFocus = { onFocus(title) },
                            onClick = { onOpen(title) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.padding(start = 48.dp).height(195.dp))
}

@Composable
private fun PosterCard(title: Title, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.width(130.dp).aspectRatio(2f / 3f).onFocusChanged { if (it.isFocused) onFocus() }) {
        Box(Modifier.fillMaxSize()) {
            Text(
                title.name,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
            AsyncImage(
                model = title.posterUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Message(text: String, detail: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
