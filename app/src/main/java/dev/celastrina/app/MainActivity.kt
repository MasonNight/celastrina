package dev.celastrina.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.source.StreamLookup

class MainActivity : ComponentActivity() {

    private val app get() = application as CelastrinaApplication
    private val catalogue by lazy { Catalogue(app.http, TmdbSettings(apiKey = BuildConfig.TMDB_API_KEY)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds can be started on a Movie's detail screen, for checking playback on the TV (scripts/tv.sh open-movie).
        val openMovie = intent.getIntExtra(EXTRA_OPEN_MOVIE, 0).takeIf { BuildConfig.DEBUG && it > 0 }
        setContent {
            CelastrinaApp(
                catalogue,
                newStreamLookup = { StreamLookup(app.configuration.sources(), app.logger) },
                configurationChanges = app.configuration.changes,
                openMovie = openMovie,
            )
        }
    }

    private companion object {
        const val EXTRA_OPEN_MOVIE = "dev.celastrina.OPEN_MOVIE"
    }
}
