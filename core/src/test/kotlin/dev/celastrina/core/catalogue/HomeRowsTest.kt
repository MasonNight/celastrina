package dev.celastrina.core.catalogue

import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRowsTest {

    @Test
    fun `the Movies row is this week's trending Movies, in TMDB's order, without animation`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val row = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).row(HomeRow.Movies)

            assertEquals("Movies", row.name)
            assertEquals(
                Title(
                    tmdbId = 1003596,
                    kind = Title.Kind.Movie,
                    name = "Avengers: Doomsday",
                    year = 2026,
                    posterUrl = "https://image.tmdb.org/t/p/w342/1zqWkLwC1zLBBBtIWzmkmWeMWnT.jpg",
                    category = null,
                ),
                row.titles.first(),
            )
            // Trending's 4th and 9th Movies, Hexed and Steps, are animated: they belong to Cartoons, not here.
            assertEquals(
                listOf(
                    "Avengers: Doomsday", "The Hunger Games: Sunrise on the Reaping", "The Odyssey",
                    "You Can See Everything", "The Social Reckoning", "Spider-Man: Brand New Day", "Wicker",
                    "Clashing Through the Snow", "Guarding Stars", "Dune: Part Three", "Madden", "The Beast",
                    "I Play Rocky", "Godzilla Minus Zero", "Breeder", "Focker In-Law", "Perfect Liars", "Fjord",
                    "Resident Evil", "Heart of the Beast",
                ),
                row.titles.map { it.name },
            )
            assertTrue(row.canLoadMore)
        }
    }

    @Test
    fun `the Series row is this week's trending Series, without animation`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val row = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).row(HomeRow.Series)

            assertEquals("Series", row.name)
            assertEquals(
                Title(
                    tmdbId = 84773,
                    kind = Title.Kind.Series,
                    name = "The Lord of the Rings: The Rings of Power",
                    year = 2022,
                    posterUrl = "https://image.tmdb.org/t/p/w342/bMXWXKM9ydUmMTNvDnYo9QtCBRt.jpg",
                    category = null,
                ),
                row.titles.first(),
            )
            assertEquals(
                listOf("MobLand", "The Body", "The Blue Road", "Percy Jackson and the Olympians"),
                row.titles.drop(1).take(4).map { it.name },
            )
            assertTrue(row.titles.all { it.kind == Title.Kind.Series && it.category == null })
        }
    }

    @Test
    fun `the Anime row is Japanese animation, Movies and Series mixed by popularity`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US)

            val row = catalogue.loadAll(catalogue.row(HomeRow.Anime))

            assertEquals("Anime", row.name)
            assertEquals(
                listOf("Pokémon", "Doraemon", "One Piece", "Chibi Maruko-chan", "Ninja Boy Rantaro", "Detective Conan"),
                row.titles.take(6).map { it.name },
            )
            assertTrue(row.titles.all { it.category == Category.Anime })
            // TMDB's Series are far more popular than its Movies, so the first Movie is the 100th Title.
            assertEquals(
                Title(
                    tmdbId = 1311031,
                    kind = Title.Kind.Movie,
                    name = "Demon Slayer: Kimetsu no Yaiba Infinity Castle",
                    year = 2025,
                    posterUrl = "https://image.tmdb.org/t/p/w342/fWVSwgjpT2D78VUh6X8UBd2rorW.jpg",
                    category = Category.Anime,
                ),
                row.titles.last(),
            )
        }
    }

    @Test
    fun `the Cartoons row is animation in any other language, Movies and Series mixed by popularity`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US)

            val row = catalogue.row(HomeRow.Cartoons)

            assertEquals("Cartoons", row.name)
            // Pokémon, Doraemon and One Piece are popular animation too, but Japanese: they're Anime.
            assertEquals(
                listOf(
                    "Family Guy", "Phineas and Ferb", "The Simpsons", "Arthur", "The Legend of Korra",
                    "SpongeBob SquarePants", "American Dad!", "Wakfu", "Teen Titans Go!",
                    "Miraculous: Tales of Ladybug & Cat Noir", "South Park", "Rick and Morty", "Bob's Burgers",
                    "Peppa Pig", "PAW Patrol", "Steps", "Ninjago: Masters of Spinjitzu", "King of the Hill",
                    "Rugrats", "Regular Show",
                ),
                row.titles.map { it.name },
            )
            assertEquals(Title.Kind.Movie, row.titles.single { it.name == "Steps" }.kind)
            assertTrue(row.titles.all { it.category == Category.Cartoons })
        }
    }

    @Test
    fun `a Title is in at most one row`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US)

            val rows = HomeRow.entries.map { catalogue.loadAll(catalogue.row(it)) }

            val everyTitle = rows.flatMap { row -> row.titles.map { it.kind to it.tmdbId } }
            assertEquals(everyTitle.size, everyTitle.toSet().size)
        }
    }

    @Test
    fun `rows load more as you scroll right and stop at 100 Titles`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US)
            val first = catalogue.row(HomeRow.Movies)

            val second = catalogue.more(first)
            val all = catalogue.loadAll(second)

            assertEquals(20, first.titles.size)
            assertEquals(40, second.titles.size)
            assertEquals(first.titles, second.titles.take(20))
            assertEquals(listOf("Other Mommy", "A Talent for Murder"), second.titles.drop(20).take(2).map { it.name })
            assertEquals(100, all.titles.size)
            assertEquals("Appofeniacs", all.titles.last().name)
            assertFalse(all.canLoadMore)
        }
    }

    @Test
    fun `a Title trending on two pages appears once`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US)

            val row = catalogue.loadAll(catalogue.row(HomeRow.Series))

            assertEquals(100, row.titles.size)
            assertEquals(row.titles.size, row.titles.map { it.tmdbId }.toSet().size)
        }
    }

    @Test
    fun `rows follow the TV's language`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val row = Catalogue(JdkHttpClient(), tmdb.settings, Locale.GERMANY).row(HomeRow.Movies)

            assertTrue("Perfekte Lügner" in row.titles.map { it.name })
        }
    }

    @Test
    fun `a v4 read access token works as the TMDB key`() = runTest {
        val v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.c2lnbmF0dXJl" // gitleaks:allow (made-up token)
        RecordedTmdb(apiKey = v4Token).use { tmdb ->
            val row = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).row(HomeRow.Movies)

            assertEquals(20, row.titles.size)
        }
    }

    @Test
    fun `a rejected TMDB key makes the Catalogue unreadable, with a reason`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings.copy(apiKey = "wrong-key"), Locale.US)

            val failure = assertFailsWith<CatalogueUnreadable> { catalogue.row(HomeRow.Movies) }

            assertEquals(CatalogueUnreadable.Reason.KeyRejected, failure.reason)
        }
    }

    @Test
    fun `an unreachable TMDB makes the Catalogue unreadable, with a reason`() = runTest {
        val closedServer = RecordedTmdb(apiKey = "project-token").use { it.settings }
        val catalogue = Catalogue(JdkHttpClient(), closedServer, Locale.US)

        val failure = assertFailsWith<CatalogueUnreadable> { catalogue.row(HomeRow.Movies) }

        assertEquals(CatalogueUnreadable.Reason.Unreachable, failure.reason)
    }

    private suspend fun Catalogue.loadAll(row: Row): Row {
        var loaded = row
        while (loaded.canLoadMore) loaded = more(loaded)
        return loaded
    }
}
