package dev.celastrina.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.configuration.ConfigurationChange
import dev.celastrina.core.source.StreamLookup
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val app get() = application as CelastrinaApplication
    private val menuPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds can be started on a Title's detail screen, for checking it on the TV
        // (scripts/tv.sh open-movie and open-series).
        val openMovie = intent.getIntExtra(EXTRA_OPEN_MOVIE, 0).takeIf { BuildConfig.DEBUG && it > 0 }
        val openSeries = intent.getIntExtra(EXTRA_OPEN_SERIES, 0).takeIf { BuildConfig.DEBUG && it > 0 }
        setContent {
            // A TMDB key set or cleared from a computer takes effect at once: a new Catalogue reloads what's on screen.
            var tmdb by remember { mutableStateOf(app.configuration.tmdbSettings()) }
            LaunchedEffect(Unit) {
                app.configuration.changes.collect { if (it.action in TMDB_KEY_CHANGES) tmdb = app.configuration.tmdbSettings() }
            }
            val catalogue = remember(tmdb) { Catalogue(app.http, tmdb) }
            val tmdbKey = remember(tmdb) { app.configuration.status().tmdbKey }
            CelastrinaApp(
                catalogue,
                newStreamLookup = { StreamLookup(app.configuration.sources(), app.logger) },
                logger = app.logger,
                configurationChanges = app.configuration.changes,
                configuration = app.configuration,
                tmdbKey = tmdbKey,
                appVersion = BuildConfig.VERSION_NAME,
                menuPresses = menuPresses,
                openMovie = openMovie,
                openSeries = openSeries,
            )
        }
    }

    // Nothing on screen takes the Menu button, so it reaches the activity and opens Settings.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) menuPresses.tryEmit(Unit)
        return true
    }

    private companion object {
        const val EXTRA_OPEN_MOVIE = "dev.celastrina.OPEN_MOVIE"
        const val EXTRA_OPEN_SERIES = "dev.celastrina.OPEN_SERIES"
        val TMDB_KEY_CHANGES = setOf(ConfigurationChange.Action.TmdbKeySet, ConfigurationChange.Action.TmdbKeyCleared)
    }
}
