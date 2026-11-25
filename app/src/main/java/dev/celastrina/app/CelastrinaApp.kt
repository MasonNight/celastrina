package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.celastrina.core.configuration.TmdbKeyInUse
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import kotlinx.coroutines.flow.Flow

private sealed interface Screen {
    data object Home : Screen
    data class MovieDetail(val tmdbId: Int) : Screen

    /** [season] is the season tab it opens on; null opens on the first. */
    data class SeriesDetail(val tmdbId: Int, val season: Int? = null) : Screen
    data class Player(val stream: Stream, val back: Screen) : Screen
    data class Settings(val back: Screen) : Screen
    data class About(val back: Settings) : Screen
}

/**
 * The whole app: home, a Movie's or a Series' detail screen, and the player, with Back going one step up.
 * [openMovie] or [openSeries] starts on that Title's detail screen instead of home. Each detail screen asks the
 * Sources [newStreamLookup] has when it opens. [configurationChanges] made from a computer show as notices over any screen.
 * The remote's Menu button ([menuPresses]) opens Settings from anywhere but the player; Settings shows [tmdbKey] and
 * leads to About.
 */
@Composable
fun CelastrinaApp(
    catalogue: Catalogue,
    newStreamLookup: () -> StreamLookup,
    configurationChanges: Flow<ConfigurationChange>,
    tmdbKey: TmdbKeyInUse,
    appVersion: String,
    menuPresses: Flow<Unit>,
    openMovie: Int? = null,
    openSeries: Int? = null,
) {
    // Home's row and the last Title opened live here, so coming back from a detail screen keeps both.
    var home by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    var lastOpened by remember { mutableStateOf<Title?>(null) }
    var screen by remember {
        mutableStateOf(openMovie?.let(Screen::MovieDetail) ?: openSeries?.let { Screen.SeriesDetail(it) } ?: Screen.Home)
    }

    LaunchedEffect(catalogue) {
        home = try {
            HomeState.Loaded(catalogue.moviesRow())
        } catch (e: CatalogueUnavailable) {
            HomeState.Failed(e.reason)
        }
    }

    LaunchedEffect(menuPresses) {
        menuPresses.collect {
            when (val s = screen) {
                is Screen.Player, is Screen.Settings -> Unit
                is Screen.About -> screen = s.back
                else -> screen = Screen.Settings(back = s)
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Black, contentColor = Color.White)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(home, focusedTitle = lastOpened) { title ->
                    lastOpened = title
                    screen = when (title.kind) {
                        Title.Kind.Movie -> Screen.MovieDetail(title.tmdbId)
                        Title.Kind.Series -> Screen.SeriesDetail(title.tmdbId)
                    }
                }
                is Screen.MovieDetail -> {
                    BackHandler { screen = Screen.Home }
                    val streamLookup = remember(s) { newStreamLookup() }
                    DetailScreen(s.tmdbId, catalogue, streamLookup) { stream -> screen = Screen.Player(stream, back = s) }
                }
                is Screen.SeriesDetail -> {
                    BackHandler { screen = Screen.Home }
                    val streamLookup = remember(s) { newStreamLookup() }
                    SeriesDetailScreen(s.tmdbId, s.season, catalogue, streamLookup) { season, stream ->
                        screen = Screen.Player(stream, back = s.copy(season = season))
                    }
                }
                is Screen.Player -> {
                    BackHandler { screen = s.back }
                    PlayerScreen(s.stream)
                }
                is Screen.Settings -> {
                    BackHandler { screen = s.back }
                    SettingsScreen(tmdbKey) { screen = Screen.About(back = s) }
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

fun CatalogueUnavailable.Reason.message() = when (this) {
    CatalogueUnavailable.Reason.NoKey -> "No TMDB key"
    CatalogueUnavailable.Reason.Unreachable -> "Can't reach TMDB. Check the TV's network connection."
    CatalogueUnavailable.Reason.KeyRejected -> "TMDB rejected the key."
    CatalogueUnavailable.Reason.ServiceError -> "TMDB isn't answering properly right now."
}
