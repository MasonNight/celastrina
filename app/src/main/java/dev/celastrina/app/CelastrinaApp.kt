package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnavailable
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.configuration.ConfigurationChange
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.TmdbKeyInUse
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watched
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.StreamLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data class MovieDetail(val tmdbId: Int) : Screen

    /** A Continue watching card, resuming. */
    data class Resume(val watched: Watched) : Screen

    /** [season] is the season tab it opens on; null opens on the first. */
    data class SeriesDetail(val tmdbId: Int, val season: Int? = null) : Screen
    data class Player(val request: PlayRequest, val back: Screen) : Screen
    data class Settings(val back: Screen) : Screen
    data class About(val back: Settings) : Screen
    data class Sources(val back: Settings) : Screen
    data class AddSource(val back: Sources) : Screen
    data class Extensions(val back: Screen) : Screen
}

/**
 * The whole app: home, a Movie's or a Series' detail screen, and the player, with Back going one step up.
 * [openMovie] or [openSeries] starts on that Title's detail screen instead of home. Each detail screen asks the
 * Sources [newStreamLookup] has when it opens; the Lookup outlives the detail screen, so Failover in the player still gets
 * Streams that arrive late, and can ask the Sources again when its Stream list may have expired. [failoverMemory] orders
 * Play's choice and Failover's candidates. [continueWatching] and [watchlist] are home's first two rows; a Continue
 * watching card resumes playback in one press. [configurationChanges] made from a computer show as notices over any screen.
 * The remote's Menu button ([menuPresses]) opens Settings from anywhere but the player; Settings shows [tmdbKey] and
 * leads to the Sources (where [configuration] adds and changes them), the Extensions and About.
 */
@Composable
fun CelastrinaApp(
    catalogue: Catalogue,
    newStreamLookup: () -> StreamLookup,
    logger: Logger,
    failoverMemory: FailoverMemory,
    continueWatching: ContinueWatching,
    watchlist: Watchlist,
    configurationChanges: Flow<ConfigurationChange>,
    configuration: ConfigurationService,
    tmdbKey: TmdbKeyInUse,
    appVersion: String,
    menuPresses: Flow<Unit>,
    openMovie: Int? = null,
    openSeries: Int? = null,
) {
    // Home lives here, so coming back from a detail screen finds its rows, hero and focus as they were.
    val scope = rememberCoroutineScope()
    val home = remember(catalogue) { HomeModel(catalogue, scope) }
    val lookupScope = rememberCoroutineScope()
    var screen by remember {
        mutableStateOf(openMovie?.let(Screen::MovieDetail) ?: openSeries?.let { Screen.SeriesDetail(it) } ?: Screen.Home)
    }

    LaunchedEffect(menuPresses) {
        menuPresses.collect {
            when (val s = screen) {
                is Screen.Player, is Screen.Settings, is Screen.Sources, is Screen.AddSource, is Screen.Extensions -> Unit
                is Screen.About -> screen = s.back
                else -> screen = Screen.Settings(back = s)
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Black, contentColor = Color.White)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(home, continueWatching, watchlist, onResume = { watched ->
                    home.focusTarget = HouseholdRow.ContinueWatching to watched.watching.title
                    screen = Screen.Resume(watched)
                }) { homeRow, title ->
                    home.focusTarget = homeRow to title
                    screen = when (title.kind) {
                        Title.Kind.Movie -> Screen.MovieDetail(title.tmdbId)
                        Title.Kind.Series -> Screen.SeriesDetail(title.tmdbId)
                    }
                }
                is Screen.MovieDetail -> {
                    BackHandler { screen = Screen.Home }
                    val lookUp = remember(s) { lookUpIn(lookupScope, newStreamLookup()) }
                    DetailScreen(s.tmdbId, catalogue, lookUp, failoverMemory, continueWatching, watchlist) { request -> screen = Screen.Player(request, back = s) }
                }
                is Screen.Resume -> {
                    BackHandler { screen = Screen.Home }
                    val lookUp = remember(s) { lookUpIn(lookupScope, newStreamLookup()) }
                    ResumeScreen(s.watched, lookUp, failoverMemory, onPlay = { screen = Screen.Player(it, back = Screen.Home) }) {
                        val title = s.watched.watching.title
                        screen = when (title.kind) {
                            Title.Kind.Movie -> Screen.MovieDetail(title.tmdbId)
                            Title.Kind.Series -> Screen.SeriesDetail(title.tmdbId, s.watched.watching.reference.episode?.season)
                        }
                    }
                }
                is Screen.SeriesDetail -> {
                    BackHandler { screen = Screen.Home }
                    val lookUp = remember(s) { lookUpIn(lookupScope, newStreamLookup()) }
                    SeriesDetailScreen(s.tmdbId, s.season, catalogue, lookUp, failoverMemory, continueWatching, watchlist) { season, request ->
                        screen = Screen.Player(request, back = s.copy(season = season))
                    }
                }
                is Screen.Player -> {
                    // Back leaves the player, which also cancels a Failover in progress.
                    BackHandler { screen = s.back }
                    PlayerScreen(s.request, failoverMemory, continueWatching, logger)
                }
                is Screen.Settings -> {
                    BackHandler { screen = s.back }
                    SettingsScreen(
                        tmdbKey,
                        updatesAvailable = remember(s) { configuration.updatesAvailable() },
                        onSources = { screen = Screen.Sources(back = s) },
                        onExtensions = { screen = Screen.Extensions(back = s) },
                    ) { screen = Screen.About(back = s) }
                }
                is Screen.Sources -> {
                    BackHandler { screen = s.back }
                    SourcesScreen(configuration) { screen = Screen.AddSource(back = s) }
                }
                is Screen.AddSource -> {
                    BackHandler { screen = s.back }
                    AddSourceScreen(configuration, onExtensions = { screen = Screen.Extensions(back = s) }) { screen = s.back }
                }
                is Screen.Extensions -> {
                    BackHandler { screen = s.back }
                    ExtensionsScreen(configuration)
                }
                is Screen.About -> {
                    BackHandler { screen = s.back }
                    AboutScreen(appVersion)
                }
            }
            ChangedFromComputerNotices(configurationChanges)
        }
    }
}

/** One Title's Lookup as it goes ([lookups]), which the player can [askAgain] when its Stream list may have expired. */
class TitleLookup(val lookups: StateFlow<Lookup?>, val askAgain: () -> Unit)

/**
 * Starts each Lookup in [scope], which outlives the detail screen that asked, so the player keeps getting Streams that
 * arrive late. A Lookup ends by itself once every Source has answered or timed out. Asking again keeps the Streams
 * already found listed until each Source answers again.
 */
private fun lookUpIn(scope: CoroutineScope, streamLookup: StreamLookup): (TitleReference) -> TitleLookup = { title ->
    val lookups = MutableStateFlow<Lookup?>(null)
    fun ask(): Job {
        val previous = lookups.value
        return scope.launch { streamLookup.watch(title, previous).collect { lookups.value = it } }
    }
    var asking = ask()
    TitleLookup(lookups) {
        asking.cancel()
        asking = ask()
    }
}

fun CatalogueUnavailable.Reason.message() = when (this) {
    CatalogueUnavailable.Reason.NoKey -> "No TMDB key"
    CatalogueUnavailable.Reason.Unreachable -> "You're offline, and this isn't saved on the TV yet."
    CatalogueUnavailable.Reason.KeyRejected -> "TMDB rejected the key."
    CatalogueUnavailable.Reason.ServiceError -> "TMDB isn't answering properly right now."
}
