package dev.celastrina.core.catalogue

import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MovieDetailsTest {

    @Test
    fun `opening a Movie shows its backdrop, metadata and synopsis`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val details = Catalogue(JdkHttpClient(), tmdb.settings).movieDetails(tmdbId = 10331)

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
    fun `a Movie's Title reference carries every ID and name the Sources can match on`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val reference = Catalogue(JdkHttpClient(), tmdb.settings).movieDetails(tmdbId = 10331).reference

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
    fun `a Movie TMDB doesn't know makes the Catalogue unavailable with a reason`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val failure = assertFailsWith<CatalogueUnavailable> {
                Catalogue(JdkHttpClient(), tmdb.settings).movieDetails(tmdbId = 999_999_999)
            }

            assertEquals(CatalogueUnavailable.Reason.ServiceError, failure.reason)
        }
    }
}
