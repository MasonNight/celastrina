package dev.celastrina.core.catalogue

import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.testing.RecordedTmdb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesDetailsTest {

    @Test
    fun `a Series' season tabs end with Specials when TMDB has a season 0`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val sherlock = Catalogue(JdkHttpClient(), tmdb.settings).seriesDetails(tmdbId = 19885)

            assertEquals(
                listOf("Series 1", "Series 2", "Series 3", "Series 4", "Specials"),
                sherlock.seasons.map { it.name },
            )
            assertEquals(listOf(1, 2, 3, 4, 0), sherlock.seasons.map { it.number })
        }
    }

    @Test
    fun `a Series without a season 0 has no Specials tab`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val fawltyTowers = Catalogue(JdkHttpClient(), tmdb.settings).seriesDetails(tmdbId = 2207)

            assertEquals(listOf("Series 1", "Series 2"), fawltyTowers.seasons.map { it.name })
        }
    }

    @Test
    fun `a season's episode cards show each episode's still, number, name and runtime`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings)
            val sherlock = catalogue.seriesDetails(tmdbId = 19885)

            val episodes = catalogue.episodes(sherlock, sherlock.seasons.single { it.number == 2 })

            assertEquals(
                listOf(
                    EpisodeCard("https://image.tmdb.org/t/p/w300/9fmNcR20uEnMeAKmUucJnCPeUJg.jpg", 1, "A Scandal in Belgravia", 90),
                    EpisodeCard("https://image.tmdb.org/t/p/w300/vjUsJUsF5RcTRKn2prGHCW9Nxro.jpg", 2, "The Hounds of Baskerville", 89),
                    EpisodeCard("https://image.tmdb.org/t/p/w300/PVxRD2JqT2QM4t1Cih0v2AoRSR.jpg", 3, "The Reichenbach Fall", 89),
                ),
                episodes.map { EpisodeCard(it.stillUrl, it.number, it.name, it.runtimeMinutes) },
            )
        }
    }

    @Test
    fun `an episode's Title reference carries the Series' IDs and names, its TMDB numbering, absolute number and air date`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings)
            val sherlock = catalogue.seriesDetails(tmdbId = 19885)

            val reference = catalogue.episodes(sherlock, sherlock.seasons.single { it.number == 2 })
                .single { it.name == "A Scandal in Belgravia" }.reference

            assertEquals(
                TitleReference(
                    kind = Title.Kind.Series,
                    tmdbId = 19885,
                    imdbId = "tt1475582",
                    wikidataId = "Q192837",
                    title = "Sherlock",
                    originalTitle = "Sherlock",
                    alternativeTitles = listOf(
                        "Şerlok", "神探夏洛克", "Σέρλοκ", "新福尔摩斯", "神探福爾摩斯", "شرلوک هلمز",
                        "셜록", "Šerlokas", "Шерлок", "เชอร์ล็อกโฮมส์ อัจฉริยะยอดนักสืบ", "新世紀福爾摩斯", "Sherlok Xolms",
                    ),
                    year = 2010,
                    // Series 1 has three episodes, so this is the fourth; the nine Specials don't count.
                    episode = TitleReference.Episode(season = 2, episode = 1, absoluteEpisode = 4, airDate = AirDate(2012, 1, 1)),
                ),
                reference,
            )
        }
    }

    @Test
    fun `a special has no absolute episode number`() = runTest {
        RecordedTmdb(apiKey = "project-token").use { tmdb ->
            val catalogue = Catalogue(JdkHttpClient(), tmdb.settings)
            val sherlock = catalogue.seriesDetails(tmdbId = 19885)

            val specials = catalogue.episodes(sherlock, sherlock.seasons.single { it.isSpecials })
            val abominableBride = specials.single { it.name == "The Abominable Bride" }

            assertEquals("Specials", sherlock.seasons.last().name)
            assertEquals(
                TitleReference.Episode(season = 0, episode = 9, absoluteEpisode = null, airDate = AirDate(2016, 1, 1)),
                abominableBride.reference.episode,
            )
        }
    }

    private data class EpisodeCard(val stillUrl: String?, val number: Int, val name: String, val runtimeMinutes: Int?)
}
