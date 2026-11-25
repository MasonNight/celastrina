package dev.celastrina.core.catalogue

import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchTest {

    @Test
    fun `searching finds Movies and Series, each in TMDB's relevance order, mixed by popularity, and never people`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val titles = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).search("dune")

            assertEquals(
                Title(
                    tmdbId = 438631,
                    kind = Title.Kind.Movie,
                    name = "Dune",
                    year = 2021,
                    posterUrl = "https://image.tmdb.org/t/p/w342/v1tRXZ4JtD2Iv6fjkPvT4GiwslV.jpg",
                    category = null,
                ),
                titles.first(),
            )
            // TMDB's Movie and TV searches each keep their own order; the more popular of the two next results comes first.
            assertEquals(
                listOf(
                    Title.Kind.Movie to "Dune", Title.Kind.Movie to "Dune: Part Two", Title.Kind.Movie to "Dune: Part Three",
                    Title.Kind.Series to "Dune: Prophecy", Title.Kind.Movie to "Dune", Title.Kind.Movie to "Anatomy of a Fall",
                    Title.Kind.Series to "Le temps d'une paix", Title.Kind.Series to "Frank Herbert's Dune",
                    Title.Kind.Series to "Frank Herbert's Children of Dune",
                ),
                titles.take(9).map { it.kind to it.name },
            )
            assertEquals(40, titles.size, "Each search's first page of 20")
            // The 2020 Hungarian animated short is a Cartoon, worked out as for home's rows.
            assertEquals(Category.Cartoons, titles.single { it.tmdbId == 697620 }.category)
        }
    }

    @Test
    fun `search leaves no history, as nothing it finds is kept on the TV, so searching again asks TMDB again`(@TempDir dir: File) = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val http = WatchedHttp(JdkHttpClient())
            val catalogue = Catalogue(http, tmdb.settings, Locale.US, CatalogueCache(FileStorage(dir), VirtualClock()))

            catalogue.search("dune")
            catalogue.search("dune")

            assertEquals(listOf("dune", "dune"), http.searches)
            assertEquals(emptyList(), dir.listFiles().orEmpty().map { it.name })
        }
    }

    @Test
    fun `before anything is typed, search shows this week's trending Movies and Series`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val search = Search(Catalogue(JdkHttpClient(), tmdb.settings, Locale.US), backgroundScope)

            val trending = search.results.first { it !is SearchResults.Loading }

            assertIs<SearchResults.Trending>(trending)
            assertEquals(
                listOf(
                    Title.Kind.Movie to "The Hunger Games: Sunrise on the Reaping", Title.Kind.Movie to "Avengers: Doomsday",
                    Title.Kind.Movie to "The Odyssey", Title.Kind.Movie to "Spider-Man: Brand New Day",
                    Title.Kind.Movie to "You Can See Everything", Title.Kind.Movie to "Steps", Title.Kind.Movie to "Hexed",
                    Title.Kind.Series to "The Lord of the Rings: The Rings of Power",
                ),
                trending.titles.take(8).map { it.kind to it.name },
            )
            assertEquals(20, trending.titles.size)
        }
    }

    @Test
    fun `search runs 400 ms after the last key press, once 2 or more characters are typed`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val http = WatchedHttp(JdkHttpClient())
            val search = Search(Catalogue(http, tmdb.settings, Locale.US), backgroundScope)
            search.results.first { it is SearchResults.Trending }

            search.type('d')
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(emptyList(), http.searches)

            search.type('u')
            advanceTimeBy(300)
            search.type('n')
            advanceTimeBy(300)
            search.type('e')
            advanceTimeBy(399)
            runCurrent()
            assertEquals(emptyList(), http.searches, "Searched before the keys had rested for 400 ms")
            assertEquals("dune", search.query.value)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("dune"), http.searches)
            val found = assertIs<SearchResults.Found>(search.results.first { it is SearchResults.Found })
            assertEquals("dune", found.query)
            assertEquals("Dune: Prophecy", found.titles.first { it.kind == Title.Kind.Series }.name)
        }
    }

    @Test
    fun `an older query's results never replace a newer query's`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val duAnswer = CompletableDeferred<Unit>()
            val http = WatchedHttp(JdkHttpClient(), holdUntil = mapOf("du" to duAnswer))
            val search = Search(Catalogue(http, tmdb.settings, Locale.US), backgroundScope)
            search.results.first { it is SearchResults.Trending }

            "du".forEach(search::type)
            advanceTimeBy(400)
            runCurrent()
            "ne".forEach(search::type)
            advanceTimeBy(400)
            runCurrent()
            assertEquals("dune", assertIs<SearchResults.Found>(search.results.first { it is SearchResults.Found }).query)

            duAnswer.complete(Unit) // TMDB's slow answer for "du" arrives after the one for "dune".
            runCurrent()

            assertEquals(listOf("du", "dune"), http.searches)
            assertEquals("dune", assertIs<SearchResults.Found>(search.results.value).query)
        }
    }

    @Test
    fun `a query nothing matches says so`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val search = Search(Catalogue(JdkHttpClient(), tmdb.settings, Locale.US), backgroundScope)

            "qqxz".forEach(search::type)
            advanceTimeBy(400)
            runCurrent()

            assertEquals(SearchResults.NoMatches("qqxz"), search.results.first { it is SearchResults.NoMatches })
        }
    }

    @Test
    fun `deleting and clearing edit the query, and fewer than 2 characters bring trending back`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val search = Search(Catalogue(JdkHttpClient(), tmdb.settings, Locale.US), backgroundScope)
            "dune".forEach(search::type)
            advanceTimeBy(400)
            runCurrent()
            search.results.first { it is SearchResults.Found }

            search.delete()
            assertEquals("dun", search.query.value)
            search.clear()
            assertEquals("", search.query.value)
            runCurrent()

            assertIs<SearchResults.Trending>(search.results.first { it !is SearchResults.Found })
        }
    }

    @Test
    fun `with no network, search says the Catalogue can't be reached`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val offline = object : HttpClient {
                override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = throw IOException("No route to host")
                override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse = throw IOException("No route to host")
            }
            val search = Search(Catalogue(offline, tmdb.settings, Locale.US), backgroundScope)

            assertEquals(
                SearchResults.Unavailable(CatalogueUnavailable.Reason.Unreachable),
                search.results.first { it !is SearchResults.Loading },
            )
            "dune".forEach(search::type)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(SearchResults.Unavailable(CatalogueUnavailable.Reason.Unreachable), search.results.value)
        }
    }
}

/**
 * The real HTTP client, noting each TMDB search it's asked for (once, though Movies and Series are searched apart). A search in [holdUntil] has its answer held back
 * until that signal completes, as if TMDB were slow to answer it.
 */
private class WatchedHttp(
    private val http: HttpClient,
    private val holdUntil: Map<String, CompletableDeferred<Unit>> = emptyMap(),
) : HttpClient {
    val searches = mutableListOf<String>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val query = url.takeIf { "/search/" in it }?.substringAfter("query=")?.substringBefore('&')
        if (query != null && "/search/movie" in url) searches += query
        val response = http.get(url, headers)
        holdUntil[query]?.await()
        return response
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = http.postJson(url, json, headers)
}
