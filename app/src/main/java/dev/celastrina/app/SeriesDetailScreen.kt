package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.Episode
import dev.celastrina.core.catalogue.Season
import dev.celastrina.core.catalogue.SeriesDetails
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watched
import dev.celastrina.core.household.Watching
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream

private sealed interface SeriesState {
    data object Loading : SeriesState
    data class Loaded(val details: SeriesDetails) : SeriesState
    data class Failed(val reason: CatalogueUnavailable.Reason) : SeriesState
}

private sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Loaded(val episodes: List<Episode>) : EpisodesState
    data class Failed(val reason: CatalogueUnavailable.Reason) : EpisodesState
}

/**
 * A Series' detail screen: season tabs (Specials last) over a row of episode cards. Picking an episode asks
 * the Sources for it. It opens on [seasonNumber] when that season exists, otherwise on the first tab.
 * [onPlay] gets the season the episode is in, so Back from the player can return to it. Play resumes an episode where
 * [continueWatching] says playback got to, and + Watchlist adds the Series to [watchlist] or takes it off.
 */
@Composable
fun SeriesDetailScreen(
    tmdbId: Int,
    seasonNumber: Int?,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    onPlay: (season: Int, request: PlayRequest) -> Unit,
) {
    var state by remember(tmdbId) { mutableStateOf<SeriesState>(SeriesState.Loading) }
    LaunchedEffect(tmdbId) {
        state = try {
            SeriesState.Loaded(catalogue.seriesDetails(tmdbId))
        } catch (e: CatalogueUnavailable) {
            SeriesState.Failed(e.reason)
        }
    }
    when (val s = state) {
        SeriesState.Loading -> Message("Loading…")
        is SeriesState.Failed -> Message(s.reason.message())
        is SeriesState.Loaded -> SeriesDetail(s.details, seasonNumber, catalogue, lookUp, failoverMemory, continueWatching, watchlist, onPlay)
    }
}

@Composable
private fun SeriesDetail(
    details: SeriesDetails,
    seasonNumber: Int?,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    onPlay: (season: Int, request: PlayRequest) -> Unit,
) {
    var seasonIndex by remember(details) { mutableStateOf(details.seasons.indexOfFirst { it.number == seasonNumber }.coerceAtLeast(0)) }
    var picked by remember(details) { mutableStateOf<Episode?>(null) }
    val titleLookup = remember(picked) { picked?.let { lookUp(it.reference) } }
    val lookup = titleLookup?.lookups?.collectAsState()?.value
    var choosing by remember(picked) { mutableStateOf(false) }
    val watched = remember(picked) { picked?.let { continueWatching.of(it.reference) } }
    val season = details.seasons.getOrNull(seasonIndex)
    fun play(stream: Stream, handPicked: Boolean) {
        val episode = picked ?: return
        val request = PlayRequest(
            stream, handPicked, titleLookup ?: return, "${details.title.name} · ${episode.label()}",
            startAtMillis = watched?.resumeAtMillis ?: 0,
            watching = Watching(details.title, episode.reference, episode.label(), imageUrl = episode.stillUrl ?: details.backdropUrl),
        )
        onPlay(episode.reference.episode!!.season, request)
    }

    if (choosing) {
        BackHandler { choosing = false }
        ChooseStreamScreen("${details.title.name} · ${picked?.label()}", lookup) { play(it, handPicked = true) }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(details.backdropUrl).size(960, 540).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Black.copy(alpha = 0.75f), 1f to Color.Black),
            ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            Column(Modifier.fillMaxWidth(0.6f).padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(details.title.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(details.metadataLine(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
                Text(details.synopsis, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                WatchlistButton(details.title, watchlist)
            }
            if (details.seasons.isEmpty()) {
                Text("TMDB lists no episodes for this Series.", modifier = Modifier.padding(start = 48.dp))
            } else {
                SeasonTabs(details.seasons, seasonIndex) { seasonIndex = it }
            }
            if (season != null) {
                EpisodeRow(details, season, catalogue) { picked = it }
            }
            PickedEpisode(picked, lookup, failoverMemory, watched, onPlay = { play(it, handPicked = false) }, onChoose = { choosing = true })
        }
    }
}

@Composable
private fun SeasonTabs(seasons: List<Season>, selected: Int, onSelect: (Int) -> Unit) {
    // Focus starts on the season the screen opened on.
    val openedOn = remember { selected }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // As on other TV apps, moving focus onto a tab shows that season.
    TabRow(selectedTabIndex = selected, modifier = Modifier.padding(start = 40.dp)) {
        seasons.forEachIndexed { index, season ->
            Tab(
                selected = index == selected,
                onFocus = { onSelect(index) },
                modifier = if (index == openedOn) Modifier.focusRequester(focus) else Modifier,
            ) {
                Text(season.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun EpisodeRow(details: SeriesDetails, season: Season, catalogue: Catalogue, onPick: (Episode) -> Unit) {
    var state by remember(details, season) { mutableStateOf<EpisodesState>(EpisodesState.Loading) }
    LaunchedEffect(details, season) {
        state = try {
            EpisodesState.Loaded(catalogue.episodes(details, season))
        } catch (e: CatalogueUnavailable) {
            EpisodesState.Failed(e.reason)
        }
    }
    Box(Modifier.fillMaxWidth().height(EPISODE_ROW_HEIGHT)) {
        when (val s = state) {
            EpisodesState.Loading -> Text("Loading episodes…", modifier = Modifier.padding(start = 48.dp))
            is EpisodesState.Failed -> Text(s.reason.message(), modifier = Modifier.padding(start = 48.dp))
            is EpisodesState.Loaded -> LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(s.episodes, key = { _, episode -> episode.number }) { _, episode ->
                    EpisodeCard(episode) { onPick(episode) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, onClick: () -> Unit) {
    Column(Modifier.width(EPISODE_CARD_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    episode.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(episode.stillUrl).crossfade(true).build(),
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text("${episode.number}. ${episode.name}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        episode.runtimeMinutes?.let {
            Text(runtimeText(it), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** The picked episode, with Play, Choose Stream and its Lookup's status line. Picking an episode is what asks the Sources for it. */
@Composable
private fun PickedEpisode(episode: Episode?, lookup: Lookup?, failoverMemory: FailoverMemory, watched: Watched?, onPlay: (Stream) -> Unit, onChoose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(start = 48.dp), contentAlignment = Alignment.CenterStart) {
        if (episode == null) {
            Text("Pick an episode to find Streams for it.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        } else {
            PlayRow(lookup, failoverMemory, onPlay, onChoose, label = episode.label(), watched = watched)
        }
    }
}

private fun Episode.label(): String {
    val numbering = reference.episode!!
    val where = if (numbering.season == 0) "Special ${numbering.episode}" else "S${numbering.season} E${numbering.episode}"
    return "$where · $name"
}

private fun SeriesDetails.metadataLine(): String = listOfNotNull(
    title.year?.toString(),
    seasons.count { !it.isSpecials }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
    genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString("  ·  ")

fun runtimeText(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private val EPISODE_CARD_WIDTH = 240.dp
private val EPISODE_ROW_HEIGHT = 210.dp
