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
    data class Detail(val tmdbId: Int) : Screen
    data class Player(val tmdbId: Int, val stream: Stream) : Screen
}

/**
 * The whole app: home, a Movie's detail screen, and the player, with Back going one step up.
 * [openMovie] starts on that Movie's detail screen instead of home.
 */
@Composable
fun CelastrinaApp(catalogue: Catalogue, streamLookup: StreamLookup, openMovie: Int? = null) {
    // Home's row and the last Title opened live here, so coming back from a detail screen keeps both.
    var home by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    var lastOpened by remember { mutableStateOf<Title?>(null) }
    var screen by remember { mutableStateOf<Screen>(openMovie?.let(Screen::Detail) ?: Screen.Home) }

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
                    screen = Screen.Detail(title.tmdbId)
                }
                is Screen.Detail -> {
                    BackHandler { screen = Screen.Home }
                    DetailScreen(s.tmdbId, catalogue, streamLookup) { stream -> screen = Screen.Player(s.tmdbId, stream) }
                }
                is Screen.Player -> {
                    BackHandler { screen = Screen.Detail(s.tmdbId) }
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
