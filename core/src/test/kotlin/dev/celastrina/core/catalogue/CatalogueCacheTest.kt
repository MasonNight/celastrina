package dev.celastrina.core.catalogue

import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class CatalogueCacheTest {

    @TempDir
    lateinit var dir: File

    private val clock = VirtualClock()
    private val network = Network(JdkHttpClient())

    /** A Catalogue as a fresh app start makes one: a new cache over the same files, so nothing is held in memory. */
    private fun catalogue(tmdb: RecordedTmdb) =
        Catalogue(network, tmdb.settings, Locale.US, CatalogueCache(FileStorage(dir), clock))

    @Test
    fun `a Title opened again within 7 days comes from the cache, and is asked for again after`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            catalogue(tmdb).movieDetails(10331)

            clock.advanceBy(7.days - 1.hours)
            assertEquals("Night of the Living Dead", catalogue(tmdb).movieDetails(10331).title.name)
            assertEquals(1, network.requests)

            clock.advanceBy(2.hours)
            assertEquals("Night of the Living Dead", catalogue(tmdb).movieDetails(10331).title.name)
            assertEquals(2, network.requests)
        }
    }

    @Test
    fun `home's rows come from the cache for a day, and are asked for again after`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            catalogue(tmdb).row(HomeRow.Movies)
            val firstLoad = network.requests

            clock.advanceBy(1.days - 1.hours)
            assertEquals("Avengers: Doomsday", catalogue(tmdb).row(HomeRow.Movies).titles.first().name)
            assertEquals(firstLoad, network.requests)

            clock.advanceBy(2.hours)
            assertEquals("Avengers: Doomsday", catalogue(tmdb).row(HomeRow.Movies).titles.first().name)
            assertEquals(2 * firstLoad, network.requests)
        }
    }

    @Test
    fun `with the network down, rows and Titles cached weeks ago still show`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            catalogue(tmdb).row(HomeRow.Movies)
            catalogue(tmdb).movieDetails(10331)

            clock.advanceBy(30.days)
            network.up = false

            assertEquals("Avengers: Doomsday", catalogue(tmdb).row(HomeRow.Movies).titles.first().name)
            assertEquals("Night of the Living Dead", catalogue(tmdb).movieDetails(10331).title.name)
        }
    }

    @Test
    fun `a row shown from what was cached while offline says so, so home can load it afresh once the network is back`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            assertFalse(catalogue(tmdb).row(HomeRow.Movies).fromOfflineCache)
            clock.advanceBy(30.days)
            network.up = false

            assertTrue(catalogue(tmdb).row(HomeRow.Movies).fromOfflineCache)

            network.up = true
            assertFalse(catalogue(tmdb).row(HomeRow.Movies).fromOfflineCache)
        }
    }

    @Test
    fun `with the network down, what was never cached is unavailable because TMDB is unreachable`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            catalogue(tmdb).movieDetails(10331)
            network.up = false

            assertEquals(
                CatalogueUnreadable.Reason.Unreachable,
                assertFailsWith<CatalogueUnreadable> { catalogue(tmdb).row(HomeRow.Movies) }.reason,
            )
            assertEquals(
                CatalogueUnreadable.Reason.Unreachable,
                assertFailsWith<CatalogueUnreadable> { catalogue(tmdb).seriesDetails(19885) }.reason,
            )
        }
    }

    @Test
    fun `everything cached is purged once it is 6 months old`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            catalogue(tmdb).movieDetails(10331)
            clock.advanceBy(150.days)
            catalogue(tmdb).seriesDetails(19885)
            val filesWithBoth = dir.list()!!.size

            clock.advanceBy(35.days)
            network.up = false
            val catalogue = catalogue(tmdb)

            assertEquals("Sherlock", catalogue.seriesDetails(19885).title.name)
            assertEquals(
                CatalogueUnreadable.Reason.Unreachable,
                assertFailsWith<CatalogueUnreadable> { catalogue.movieDetails(10331) }.reason,
            )
            assertEquals(filesWithBoth - 1, dir.list()!!.size, "The Movie's details are deleted from the TV")
        }
    }
}

/** The TV's network: every request goes through to [http] while it's up, and none arrives while it's down. */
private class Network(private val http: HttpClient) : HttpClient {
    var up = true
    var requests = 0
        private set

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        if (!up) throw IOException("The network is down")
        requests++
        return http.get(url, headers)
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse {
        if (!up) throw IOException("The network is down")
        requests++
        return http.postJson(url, json, headers)
    }
}
