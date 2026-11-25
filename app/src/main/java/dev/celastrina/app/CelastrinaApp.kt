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
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup

private sealed interface Screen {
    data object Home : Screen
    data class MovieDetail(val tmdbId: Int) : Screen

    /** [season] is the season tab it opens on; null opens on the first. */
    data class SeriesDetail(val tmdbId: Int, val season: Int? = null) : Screen
    data class Player(val stream: Stream, val back: Screen) : Screen
}

/**
 * The whole app: home, a Movie's or a Series' detail screen, and the player, with Back going one step up.
 * [openMovie] or [openSeries] starts on that Title's detail screen instead of home.
 */
@Composable
fun CelastrinaApp(catalogue: Catalogue, streamLookup: StreamLookup, openMovie: Int? = null, openSeries: Int? = null) {
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
                    DetailScreen(s.tmdbId, catalogue, streamLookup) { stream -> screen = Screen.Player(stream, back = s) }
                }
                is Screen.SeriesDetail -> {
                    BackHandler { screen = Screen.Home }
                    SeriesDetailScreen(s.tmdbId, s.season, catalogue, streamLookup) { season, stream ->
                        screen = Screen.Player(stream, back = s.copy(season = season))
                    }
                }
                is Screen.Player -> {
                    BackHandler { screen = s.back }
                    PlayerScreen(s.stream)
                }
            }
        }
    }
}

fun CatalogueUnavailable.Reason.message() = when (this) {
    CatalogueUnavailable.Reason.Unreachable -> "Can't reach TMDB. Check the TV's network connection."
    CatalogueUnavailable.Reason.KeyRejected -> "TMDB rejected the key."
    CatalogueUnavailable.Reason.ServiceError -> "TMDB isn't answering properly right now."
}
