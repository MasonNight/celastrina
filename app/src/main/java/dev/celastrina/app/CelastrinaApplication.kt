package dev.celastrina.app

import android.app.Application
import android.util.Log
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.port.Logger
import java.io.File

/** Holds what the screens and the agent's ContentProvider share, so a change from either is seen by both. */
class CelastrinaApplication : Application() {

    val http by lazy { JdkHttpClient() }
    val logger = Logger { Log.i("Celastrina", it) }

    // ContentProvider.onCreate runs before Application.onCreate, so this is lazy rather than set in onCreate.
    val configuration by lazy {
        ConfigurationService(
            FileStorage(File(filesDir, "core")),
            http,
            appVersion = BuildConfig.VERSION_NAME,
            projectTmdbKey = BuildConfig.TMDB_API_KEY,
        )
    }
}
