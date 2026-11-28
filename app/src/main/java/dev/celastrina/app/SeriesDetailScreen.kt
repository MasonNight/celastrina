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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.celastrina.core.catalogue.CatalogueUnreadable
import dev.celastrina.core.catalogue.Episode
import dev.celastrina.core.catalogue.Season
import dev.celastrina.core.catalogue.SeriesDetails
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watching
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.source.Stream

private sealed interface SeriesState {
    data object Loading : SeriesState
    data class Loaded(val details: SeriesDetails) : SeriesState
    data class Failed(val reason: CatalogueUnreadable.Reason) : SeriesState
}

private sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Loaded(val episodes: List<Episode>) : EpisodesState
    data class Failed(val reason: CatalogueUnreadable.Reason) : EpisodesState
}

/**
 * A Series' detail screen: season tabs (Specials last) over a row of episode cards. Given [focusEpisode], it opens on
 * that episode's season with focus on its card. Otherwise it opens on [seasonNumber] when that season exists, else on
 * the first tab, with focus on the tabs.
 *
 * Clicking an episode card asks the Sources for it and goes straight to the player, which chooses the Stream; [onPlay]
 * gets the episode, so Back from the player can return to its card. Holding OK on a card, or pressing the
 * remote's Menu button on it, opens Choose Stream for that episode instead. Playback resumes where [continueWatching]
 * says it got to, and + Watchlist adds the Series to [watchlist] or takes it off.
 */
@Composable
fun SeriesDetailScreen(
    tmdbId: Int,
    seasonNumber: Int?,
    focusEpisode: TitleReference.Episode?,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    autoPlay: Boolean,
    onPlay: (episode: TitleReference.Episode, request: PlayRequest) -> Unit,
) {
    var state by remember(tmdbId) { mutableStateOf<SeriesState>(SeriesState.Loading) }
    LaunchedEffect(tmdbId) {
        state = try {
            SeriesState.Loaded(catalogue.seriesDetails(tmdbId))
        } catch (e: CatalogueUnreadable) {
            SeriesState.Failed(e.reason)
        }
    }
    when (val s = state) {
        SeriesState.Loading -> Message("Loading…")
        is SeriesState.Failed -> Message(s.reason.message())
        is SeriesState.Loaded -> SeriesDetail(s.details, focusEpisode?.season ?: seasonNumber, focusEpisode, catalogue, lookUp, continueWatching, watchlist, autoPlay, onPlay)
    }
}

@Composable
private fun SeriesDetail(
    details: SeriesDetails,
    seasonNumber: Int?,
    focusEpisode: TitleReference.Episode?,
    catalogue: Catalogue,
    lookUp: (TitleReference) -> TitleLookup,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    autoPlay: Boolean,
    onPlay: (episode: TitleReference.Episode, request: PlayRequest) -> Unit,
) {
    var seasonIndex by remember(details) { mutableStateOf(details.seasons.indexOfFirst { it.number == seasonNumber }.coerceAtLeast(0)) }
    // The card that takes focus when the episode row shows: the one played, or the one Choose Stream was opened on.
    var focusCard by remember(details) { mutableStateOf(focusEpisode) }
    var choosing by remember(details) { mutableStateOf<Pair<Episode, TitleLookup>?>(null) }
    val season = details.seasons.getOrNull(seasonIndex)
    /** Plays [episode] on the Stream picked in Choose Stream, or, when none was ([handPicked] null), on the player's choice. */
    fun play(episode: Episode, titleLookup: TitleLookup, handPicked: Stream?) {
        val watched = continueWatching.of(episode.reference)
        val request = PlayRequest(
            handPicked?.let { StreamToPlay.Chosen(it, handPicked = true) } ?: StreamToPlay.ToBeChosen(watched?.lastWorked),
            titleLookup,
            "${details.title.name} · ${episode.label()}",
            startAtMillis = watched?.resumeAtMillis ?: 0,
            watching = Watching(details.title, episode.reference, episode.label(), imageUrl = episode.stillUrl ?: details.backdropUrl),
        )
        onPlay(episode.reference.episode!!, request)
    }

    choosing?.let { (episode, titleLookup) ->
        val lookup by titleLookup.lookups.collectAsState()
        BackHandler {
            titleLookup.cancel()
            focusCard = episode.reference.episode
            choosing = null
        }
        ChooseStreamScreen("${details.title.name} · ${episode.label()}", lookup) { play(episode, titleLookup, it) }
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
            // The season tabs and episode cards are measured first, as that's where the remote is; the header gets the
            // height left over, and its logo gives up height before anything else in it.
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth(0.6f).padding(start = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f, fill = false).clipToBounds(), contentAlignment = Alignment.BottomStart) {
                    TitleLogo(details.title.name, details.logoUrl, MaterialTheme.typography.displaySmall, maxHeight = 120.dp, maxWidth = 420.dp)
                }
                Text(details.metadataLine(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
                Text(details.synopsis, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                WatchlistButton(details.title, watchlist)
            }
            if (details.seasons.isEmpty()) {
                Text("TMDB lists no episodes for this Series.", modifier = Modifier.padding(start = 48.dp))
            } else {
                SeasonTabs(details.seasons, seasonIndex, takeFocus = focusCard == null) { seasonIndex = it }
            }
            if (season != null) {
                EpisodeRow(
                    details,
                    season,
                    catalogue,
                    focusEpisode = focusCard,
                    playFirstEpisode = autoPlay && season.number == 1,
                    onPlay = { play(it, lookUp(it.reference), handPicked = null) },
                    onChoose = { choosing = it to lookUp(it.reference) },
                )
            }
        }
    }
}

@Composable
private fun SeasonTabs(seasons: List<Season>, selected: Int, takeFocus: Boolean, onSelect: (Int) -> Unit) {
    // Focus starts on the season the screen opened on, unless an episode card takes it.
    val openedOn = remember { selected }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocusOnceShown() }
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

/**
 * The season's episode cards. Clicking one plays it ([onPlay]); holding OK or pressing Menu on it opens Choose Stream
 * ([onChoose]). The card of [focusEpisode], when given, is scrolled to and takes focus as the row shows.
 */
@Composable
private fun EpisodeRow(
    details: SeriesDetails,
    season: Season,
    catalogue: Catalogue,
    focusEpisode: TitleReference.Episode?,
    playFirstEpisode: Boolean,
    onPlay: (Episode) -> Unit,
    onChoose: (Episode) -> Unit,
) {
    var state by remember(details, season) { mutableStateOf<EpisodesState>(EpisodesState.Loading) }
    LaunchedEffect(details, season) {
        state = try {
            val episodes = catalogue.episodes(details, season)
            if (playFirstEpisode) episodes.firstOrNull { it.number == 1 }?.let(onPlay)
            EpisodesState.Loaded(episodes)
        } catch (e: CatalogueUnreadable) {
            EpisodesState.Failed(e.reason)
        }
    }
    Box(Modifier.fillMaxWidth().height(EPISODE_ROW_HEIGHT)) {
        when (val s = state) {
            EpisodesState.Loading -> Text("Loading episodes…", modifier = Modifier.padding(start = 48.dp))
            is EpisodesState.Failed -> Text(s.reason.message(), modifier = Modifier.padding(start = 48.dp))
            is EpisodesState.Loaded -> {
                val focusIndex = s.episodes.indexOfFirst { it.reference.episode?.isSameAs(focusEpisode) == true }
                val row = rememberLazyListState(focusIndex.coerceAtLeast(0))
                val focus = remember { FocusRequester() }
                var focused by remember { mutableStateOf(false) }
                // Coming back from the player, its View hands focus back a frame or two late, so this asks again.
                LaunchedEffect(focusIndex) { if (focusIndex >= 0) focus.requestFocusUntil { focused } }
                LazyRow(
                    state = row,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(s.episodes, key = { _, episode -> episode.number }) { index, episode ->
                        val modifier = if (index == focusIndex) Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused } else Modifier
                        EpisodeCard(episode, modifier, onClick = { onPlay(episode) }, onChoose = { onChoose(episode) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, modifier: Modifier, onClick: () -> Unit, onChoose: () -> Unit) {
    Column(Modifier.width(EPISODE_CARD_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onChoose,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).onPreviewKeyEvent {
                // The Menu button on a card opens Choose Stream rather than Settings.
                if (it.key != Key.Menu) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) onChoose()
                true
            },
        ) {
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

/** The same episode: the same season and number, whatever else the Catalogue has learnt about it since. */
private fun TitleReference.Episode.isSameAs(other: TitleReference.Episode?) = other != null && season == other.season && episode == other.episode

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
