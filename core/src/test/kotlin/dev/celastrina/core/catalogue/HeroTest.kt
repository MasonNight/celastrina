package dev.celastrina.core.catalogue

import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroTest {

    @Test
    fun `the hero shows a Movie's title logo, textless backdrop, metadata and blurb`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).hero(movie(1003596))

            assertEquals(
                Hero(
                    name = "Avengers: Doomsday",
                    logoUrl = "https://image.tmdb.org/t/p/w500/ggTGX5lMwzoSbm2WmlAFBK0EQdO.png",
                    backdropUrl = "https://image.tmdb.org/t/p/w1280/rObL4of4eFA8yWaN9pRFo9aOVNi.jpg",
                    year = 2026,
                    runtimeMinutes = 165,
                    seasonCount = null,
                    genres = listOf("Science Fiction", "Action", "Adventure"),
                    blurb = "Beloved heroes from three distinct universes are set on a deadly collision course and face " +
                        "an existential threat unlike anything they've ever encountered.",
                ),
                hero,
            )
        }
    }

    @Test
    fun `a Series' hero counts its seasons, and its backdrop is the best one without text`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).hero(series(84773))

            assertEquals("The Lord of the Rings: The Rings of Power", hero.name)
            assertEquals(3, hero.seasonCount)
            assertNull(hero.runtimeMinutes)
            assertEquals(2022, hero.year)
            // TMDB rates an English backdrop with the title written on it higher; the hero skips it.
            assertEquals("https://image.tmdb.org/t/p/w1280/i1phX1JpaqSnHBBGta6LBFYVCZq.jpg", hero.backdropUrl)
        }
    }

    @Test
    fun `on a German TV the hero is in German, with the German title logo`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale.GERMANY).hero(movie(1568060))

            assertEquals("Perfekte Lügner", hero.name)
            assertEquals(listOf("Drama", "Liebesfilm", "Thriller"), hero.genres)
            assertEquals("https://image.tmdb.org/t/p/w500/9wU8YoqEiHBEp06jCtQI9dTu9I3.png", hero.logoUrl)
            assertEquals("https://image.tmdb.org/t/p/w1280/qKFLZz7rnZAJuyZ9HVEhwIINftz.jpg", hero.backdropUrl)
            assertEquals("Jude Derry trifft mit einer geheimen Mission", hero.blurb.take(44))
        }
    }

    @Test
    fun `without a logo in the TV's language the hero uses the English one`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale.GERMANY).hero(series(65733))

            assertEquals("https://image.tmdb.org/t/p/w500/4PyFYwHjmD85jynyTwMKbKiZjZG.png", hero.logoUrl)
        }
    }

    @Test
    fun `without a German name or blurb the hero falls back to English, and to plain text without a logo`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale.GERMANY).hero(series(34860))

            // TMDB itself falls back to the original Japanese name, 忍たま乱太郎.
            assertEquals("Ninja Boy Rantaro", hero.name)
            assertEquals("Rantarō, Shinbei and Kirimaru are ninja", hero.blurb.take(39))
            assertNull(hero.logoUrl)
        }
    }

    @Test
    fun `a Swiss German TV gets the English blurb when TMDB has none in Swiss German`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val hero = Catalogue(JdkHttpClient(), tmdb.settings, Locale("de", "CH")).hero(movie(1401459))

            assertEquals("Fjord", hero.name)
            assertEquals("Following the deaths of Mihai's parents", hero.blurb.take(39))
        }
    }

    private fun movie(tmdbId: Int) = Title(tmdbId, Title.Kind.Movie, name = "", year = null, posterUrl = null, category = null)

    private fun series(tmdbId: Int) = Title(tmdbId, Title.Kind.Series, name = "", year = null, posterUrl = null, category = null)
}
