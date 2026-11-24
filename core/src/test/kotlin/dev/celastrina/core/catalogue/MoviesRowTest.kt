package dev.celastrina.core.catalogue

import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoviesRowTest {

    @Test
    fun `the Movies row is this week's trending Movies, in TMDB's order, with posters`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings)

            val row = catalogue.moviesRow()

            assertEquals("Movies", row.name)
            assertEquals(20, row.titles.size)
            assertEquals(
                Title(
                    tmdbId = 1300968,
                    kind = Title.Kind.Movie,
                    name = "The Hunger Games: Sunrise on the Reaping",
                    year = 2026,
                    posterUrl = "https://image.tmdb.org/t/p/w342/jNcZH8yplxttRlf0wwqfGGMKHxX.jpg",
                ),
                row.titles.first(),
            )
            assertEquals(
                listOf("The Hunger Games: Sunrise on the Reaping", "The Odyssey", "Avengers: Doomsday"),
                row.titles.take(3).map { it.name },
            )
        }
    }

    @Test
    fun `a v4 read access token works as the TMDB key`() = runTest {
        val v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.c2lnbmF0dXJl"
        RecordedTmdb(apiKey = v4Token).use { tmdb ->
            val row = Catalogue(JdkHttpClient(), tmdb.settings).moviesRow()

            assertEquals(20, row.titles.size)
        }
    }

    @Test
    fun `a rejected TMDB key makes the Catalogue unavailable with a reason`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings.copy(apiKey = "wrong-key"))

            val failure = assertFailsWith<CatalogueUnavailable> { catalogue.moviesRow() }

            assertEquals(CatalogueUnavailable.Reason.KeyRejected, failure.reason)
        }
    }

    @Test
    fun `an unreachable TMDB makes the Catalogue unavailable with a reason`() = runTest {
        val closedServer = RecordedTmdb(apiKey = "project-token").use { it.settings }
        val catalogue = Catalogue(JdkHttpClient(), closedServer)

        val failure = assertFailsWith<CatalogueUnavailable> { catalogue.moviesRow() }

        assertEquals(CatalogueUnavailable.Reason.Unreachable, failure.reason)
    }
}
