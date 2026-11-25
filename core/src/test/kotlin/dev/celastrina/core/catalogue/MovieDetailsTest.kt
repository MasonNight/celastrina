package dev.celastrina.core.catalogue

import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MovieDetailsTest {

    @Test
    fun `opening a Movie shows its backdrop, metadata and synopsis`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val details = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).movieDetails(tmdbId = 10331)

            assertEquals("Night of the Living Dead", details.title.name)
            assertEquals(Title.Kind.Movie, details.title.kind)
            assertEquals(1968, details.title.year)
            assertEquals("https://image.tmdb.org/t/p/w1280/5KtmBSqFtHY3I9t8lgH27Mc0bqY.jpg", details.backdropUrl)
            assertEquals(96, details.runtimeMinutes)
            assertEquals(listOf("Horror", "Science Fiction"), details.genres)
            assertEquals(
                "A ragtag group barricade themselves in an old Pennsylvania farmhouse to remain safe from a horde of " +
                    "flesh-eating ghouls ravaging the Northeast.",
                details.synopsis,
            )
        }
    }

    @Test
    fun `a Movie's detail screen shows its title logo over its textless backdrop`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val details = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).movieDetails(tmdbId = 10331)

            assertEquals("https://image.tmdb.org/t/p/w500/dfqAfoQJiDQlDthddcVYn2z3jF7.png", details.logoUrl)
            assertEquals("https://image.tmdb.org/t/p/w1280/5KtmBSqFtHY3I9t8lgH27Mc0bqY.jpg", details.backdropUrl)
        }
    }

    @Test
    fun `on a Swiss German TV a Movie with no synopsis in its language shows the English one, and its name without a logo`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val details = Catalogue(JdkHttpClient(), tmdb.settings, Locale("de", "CH")).movieDetails(tmdbId = 1401459)

            assertEquals("Fjord", details.title.name)
            assertEquals("Following the deaths of Mihai's parents", details.synopsis.take(39))
            assertNull(details.logoUrl)
        }
    }

    @Test
    fun `a Movie's Title reference carries every ID and name the Sources can match on`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val reference = Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).movieDetails(tmdbId = 10331).reference

            assertEquals(
                TitleReference(
                    kind = Title.Kind.Movie,
                    tmdbId = 10331,
                    imdbId = "tt0063350",
                    wikidataId = "Q623051",
                    title = "Night of the Living Dead",
                    originalTitle = "Night of the Living Dead",
                    alternativeTitles = listOf(
                        "Elävien kuolleiden yö",
                        "Night of Anubis",
                        "ナイト・オブ・ザ・リビングデッド",
                        "ナイト・オブ・ザ・リビングデッド／ゾンビの誕生",
                        "生ける屍の夜",
                        "Noc žijících mrtvých",
                        "Monster Flick",
                        "Night of the Flesh Eaters",
                        "Yaşayan Ölülərin Gecəsi",
                        "Night of the Living Dead: 30th Anniversary Edition",
                    ),
                    year = 1968,
                ),
                reference,
            )
        }
    }

    @Test
    fun `a Movie TMDB doesn't know makes the Catalogue unreadable, with a reason`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val failure = assertFailsWith<CatalogueUnreadable> {
                Catalogue(JdkHttpClient(), tmdb.settings, Locale.US).movieDetails(tmdbId = 999_999_999)
            }

            assertEquals(CatalogueUnreadable.Reason.ServiceError, failure.reason)
        }
    }
}
