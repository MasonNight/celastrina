package dev.celastrina.app

import dev.celastrina.core.source.SourceId
import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.app
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.extension.ExtensionWarning
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.LookupOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The release gate for Extensions: the agent installs the test Extension, and Lookups run its DEX against the CloudStream
 * `library` and shims bundled in this build. Run against the minified release APK (`-PtestBuildType=release`), it fails
 * when R8 has renamed or removed something an Extension links against.
 *
 * It needs no network: the test Extension Repository and the sites the test Extension reaches are answered from this
 * test's assets, the test Extension Repository Gradle builds and the Core's recordings.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionLoadingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets: AssetManager = InstrumentationRegistry.getInstrumentation().context.assets
    private val storage = File(context.cacheDir, "extension-loading-test-${UUID.randomUUID()}")
    private val logger = Logger { Log.i("ExtensionLoadingTest", it) }
    private lateinit var previousClient: OkHttpClient

    @Before
    fun routeExtensionsToRecordings() {
        setContext(WeakReference(context.applicationContext))
        previousClient = app.baseClient
        app.baseClient = OkHttpClient.Builder().addInterceptor(RecordedSites()).build()
    }

    @After
    fun restore() {
        app.baseClient = previousClient
        storage.deleteRecursively()
    }

    @Test
    fun the_test_extension_opens_a_movie_by_imdb_id() = runBlocking {
        val streams = lookUp(movie("Night of the Living Dead", 1968, imdbId = "tt0063350"))

        assertEquals("https://archive.org/download/Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4", streams.first().url)
        assertEquals(1080, streams.first().resolution)
    }

    @Test
    fun the_test_extension_finds_a_movie_by_searching_its_title() = runBlocking {
        val streams = lookUp(movie("Murder at Midnight", 1931, imdbId = null))

        assertEquals("https://archive.org/download/Murder_at_Midnight_1931/Murder_at_Midnight_1931.mp4", streams.first().url)
    }

    private suspend fun lookUp(title: TitleReference) =
        assertIs<LookupOutcome.Streams>(installedExtensionSource().lookUp(title), "Lookup of ${title.title}").streams

    private suspend fun installedExtensionSource() = ConfigurationService(
        FileStorage(storage),
        RepositoryFromAssets(),
        appVersion = BuildConfig.VERSION_NAME,
        projectTmdbKey = "",
        extensionLoader = DexExtensionLoader(context),
        logger = logger,
    ).run {
        assertIs<ImportOutcome.Imported>(importRepository(REPOSITORY, ExtensionWarning.VERSION, ChangedFrom.Computer))
        assertIs<InstallOutcome.Installed>(installExtension(REPOSITORY, "InternetArchive", ChangedFrom.Computer))
        sources().single { it.id == SourceId("internetarchive-internet-archive-test") }
    }

    private fun movie(title: String, year: Int, imdbId: String?) = TitleReference(
        kind = Title.Kind.Movie,
        tmdbId = 1,
        imdbId = imdbId,
        wikidataId = null,
        title = title,
        originalTitle = title,
        alternativeTitles = emptyList(),
        year = year,
    )

    private fun asset(path: String): ByteArray? = try {
        assets.open(path).use { it.readBytes() }
    } catch (e: FileNotFoundException) {
        null
    }

    /** Serves the test Extension Repository, which Gradle builds into this test's assets, at [REPOSITORY]. */
    private inner class RepositoryFromAssets : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
            asset("test-repository/" + url.removePrefix(REPOSITORY.substringBeforeLast('/') + "/"))
                ?.let { HttpResponse(200, it) } ?: HttpResponse(404, ByteArray(0))

        override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(404, ByteArray(0))
    }

    /**
     * Answers the test Extension's requests to the Internet Archive and the Wikidata Query Service with the Core's
     * recordings, picked as `RecordedExtensionSites` picks them; anything unrecorded gets HTTP 500.
     */
    private inner class RecordedSites : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val recording = when {
                url.host == "query.wikidata.org" ->
                    IMDB_ID.find(url.queryParameter("query").orEmpty())?.groupValues?.get(1)?.let { asset("recorded/wikidata/imdb-$it.json") }
                        ?: asset("recorded/wikidata/no-match.json")
                url.host == "archive.org" && url.encodedPath == "/advancedsearch.php" ->
                    SEARCHED_TITLE.find(url.queryParameter("q").orEmpty())?.groupValues?.get(1)?.let { asset("recorded/internet-archive-search/${slug(it)}.json") }
                url.host == "archive.org" && url.encodedPath.startsWith("/metadata/") ->
                    asset("recorded/internet-archive/${URLDecoder.decode(url.encodedPath.removePrefix("/metadata/"), "UTF-8")}.json")
                else -> null
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (recording != null) 200 else 500)
                .message(if (recording != null) "OK" else "Unrecorded")
                .body((recording ?: "Unrecorded request $url".toByteArray()).toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private companion object {
        const val REPOSITORY = "https://extensions.test/repo/repo.json"
        val SEARCHED_TITLE = Regex("""title:\("(.*)"\)""")
        val IMDB_ID = Regex("""wdt:P345 "(tt\d+)"""")

        fun slug(title: String) = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-")
    }
}
