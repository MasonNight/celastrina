package dev.celastrina.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.port.JdkHttpClient

class MainActivity : ComponentActivity() {

    private val catalogue by lazy {
        Catalogue(JdkHttpClient(), TmdbSettings(apiKey = BuildConfig.TMDB_API_KEY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(catalogue) }
    }
}
