package dev.celastrina.app

import android.app.Application
import android.util.Log
import com.lagradost.api.setContext
import dev.celastrina.core.catalogue.NotPlayableMemory
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.port.Logger
import java.io.File
import java.lang.ref.WeakReference

/** Holds what the screens and the agent's ContentProvider share, so a change from either is seen by both. */
class CelastrinaApplication : Application() {

    val http by lazy { JdkHttpClient() }
    val logger = Logger { Log.i("Celastrina", it) }
    private val storage by lazy { FileStorage(File(filesDir, "core")) }

    // ContentProvider.onCreate runs before Application.onCreate, so this is lazy rather than set in onCreate.
    val configuration by lazy {
        // Extensions' CloudStream library reaches Android (its WebView, for one) through this context.
        setContext(WeakReference(applicationContext))
        ConfigurationService(
            storage,
            http,
            appVersion = BuildConfig.VERSION_NAME,
            projectTmdbKey = BuildConfig.TMDB_API_KEY,
            extensionLoader = DexExtensionLoader(applicationContext),
            logger = logger,
            failoverMemory = failoverMemory,
            notPlayable = notPlayable,
        )
    }

    /** The Streams that failed on this TV in the last 24 hours, shared by Play's choice and every playback session. */
    val failoverMemory by lazy { FailoverMemory(storage, Clock.System) }

    /** The Titles whose latest Lookup found every Source Not available, badged wherever they appear. */
    val notPlayable by lazy { NotPlayableMemory(storage, Clock.System) }

    /** Where playback got to in each Title and episode, and the Stream that last worked for it. */
    val continueWatching by lazy { ContinueWatching(storage, Clock.System) }

    val watchlist by lazy { Watchlist(storage) }
}
