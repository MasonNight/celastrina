package dev.celastrina.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.source.preinstalledSources
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val http by lazy { JdkHttpClient() }
    private val catalogue by lazy { Catalogue(http, TmdbSettings(apiKey = BuildConfig.TMDB_API_KEY), Locale.getDefault()) }

    // Stopgap until the configuration service (#26) stores the Household's Sources: the preinstalled ones only.
    private val streamLookup by lazy { StreamLookup(preinstalledSources(http), Logger { Log.i("Celastrina", it) }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds can be started on a Movie's detail screen, for checking playback on the TV (scripts/tv.sh open-movie).
        val openMovie = intent.getIntExtra(EXTRA_OPEN_MOVIE, 0).takeIf { BuildConfig.DEBUG && it > 0 }
        setContent { CelastrinaApp(catalogue, streamLookup, openMovie) }
    }

    private companion object {
        const val EXTRA_OPEN_MOVIE = "dev.celastrina.OPEN_MOVIE"
    }
}
