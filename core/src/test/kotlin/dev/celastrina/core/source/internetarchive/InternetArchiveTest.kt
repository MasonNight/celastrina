package dev.celastrina.core.source.internetarchive

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.RecordedInternetArchive
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InternetArchiveTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a new Household has the Internet Archive Source, which needs no settings and plays only Movies`() = runTest {
        val sources = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "test", projectTmdbKey = "").sources()

        assertEquals(listOf("internet-archive"), sources.map { it.id.value })
        assertEquals("Internet Archive", sources.single().name)
        assertTrue(sources.single().kind.canMatch(nightOfTheLivingDead))
        val answers = StreamLookup(sources, RecordingLogger()).lookUp(nightOfTheLivingDead.copy(kind = Title.Kind.Series)).toList()
        assertEquals(emptyList(), answers, "A Series isn't asked of the Internet Archive")
    }

    @Test
    fun `a public-domain Movie is matched through Wikidata and plays the Archive's file`() = runTest {
        val streams = streamsFor(nightOfTheLivingDead)

        assertEquals(
            Stream(
                sourceId = SourceId("internet-archive"),
                id = "Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4",
                url = "https://archive.org/download/Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4",
                delivery = Stream.Delivery.Progressive,
                label = "NightOfTheLivingDead_PS3.mp4",
                resolution = 1080,
                videoCodec = "h264",
                container = "mp4",
                sizeBytes = 1_862_510_336,
            ),
            streams.first(),
        )
    }

    @Test
    fun `a Movie Wikidata doesn't link to TMDB is matched by its IMDb ID`() = runTest {
        val streams = streamsFor(nightOfTheLivingDead.copy(tmdbId = 999_999_999, wikidataId = null))

        assertEquals("Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4", streams.first().id)
    }

    @Test
    fun `a Movie Wikidata doesn't link to TMDB is matched by its Wikidata ID`() = runTest {
        val streams = streamsFor(nightOfTheLivingDead.copy(tmdbId = 999_999_999, imdbId = null))

        assertEquals("Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4", streams.first().id)
    }

    @Test
    fun `h264 MP4s come first, then original MP4 and MKV, then other MP4s, and Ogg Video is never offered`() = runTest {
        val files = streamsFor(nightOfTheLivingDead).map { it.id.substringAfter('/') }

        assertEquals(
            listOf(
                // h.264 MP4, tallest first
                "NightOfTheLivingDead_PS3.mp4",
                "NightOfTheLivingDead_1080p.mp4",
                "NightOfTheLivingDead_720p.mp4",
                "NightOfTheLivingDead_DVD5.mp4",
                "NightOfTheLivingDead_DVD9.mp4",
                "NightOfTheLivingDead.mp4",
                "NightOfTheLivingDead_iPhone.mp4",
                "NightOfTheLivingDead_iPod.mp4",
                "NightOfTheLivingDead_PSP.mp4",
                // original MP4 or MKV
                "NightOfTheLivingDead_1080p.mkv",
                "NightOfTheLivingDead_720p.mkv",
                // any other MP4
                "NightOfTheLivingDead_1080p_512kb.mp4",
                "NightOfTheLivingDead_720p_512kb.mp4",
                "NightOfTheLivingDead_DVD5_512kb.mp4",
                "NightOfTheLivingDead_DVD9_512kb.mp4",
                "NightOfTheLivingDead_PS3_512kb.mp4",
                "NightOfTheLivingDead_PSP_512kb.mp4",
                "NightOfTheLivingDead_iPhone_512kb.mp4",
                "NightOfTheLivingDead_iPod_512kb.mp4",
            ),
            files,
        )
    }

    @Test
    fun `a Movie with several Archive items ranks all their files together`() = runTest {
        val ids = streamsFor(gunsmokeRanch).map { it.id }

        assertEquals(
            listOf("gunsmoke_ranch/gunsmoke_ranch.mp4", "gunsmoke-ranch/gunsmoke_ranch.mp4", "gunsmoke_ranch/gunsmoke_ranch_512kb.mp4"),
            ids,
        )
    }

    @Test
    fun `an item with the Public Domain Mark is offered`() = runTest {
        assertEquals("Pygmalion/Pygmalion.mp4", streamsFor(pygmalion).first().id)
    }

    @Test
    fun `an item dedicated to the public domain with CC0 is offered`() = runTest {
        assertTrue(streamsFor(gunsmokeRanch).any { it.id == "gunsmoke-ranch/gunsmoke_ranch.mp4" })
    }

    @Test
    fun `an item with a CC licence is offered`() = runTest {
        assertEquals("Murder_at_Midnight_1931/Murder_at_Midnight_1931.mp4", streamsFor(murderAtMidnight).first().id)
    }

    @Test
    fun `an item without a licence is offered when Wikidata marks the Movie public domain`() = runTest {
        assertEquals(listOf("BusterKeatonthescarecrow/thescarecrow_512kb.mp4"), streamsFor(theScarecrow).map { it.id })
    }

    @Test
    fun `an item without a licence, for a Movie Wikidata doesn't mark public domain, is Not available`() = runTest {
        assertEquals(LookupOutcome.NotAvailable, lookUp(theQuatermassXperiment))
    }

    @Test
    fun `a Movie Wikidata knows no Archive item for is Not available`() = runTest {
        val unknown = nightOfTheLivingDead.copy(tmdbId = 999_999_999, imdbId = null, wikidataId = null, title = "Not on the Archive")

        assertEquals(LookupOutcome.NotAvailable, lookUp(unknown))
    }

    @Test
    fun `Wikidata refusing requests is rate_limited`() = runTest {
        val outcome = RecordedInternetArchive(wikidataRateLimited = true).use { archive ->
            InternetArchiveSource(JdkHttpClient(), archive.settings).lookUp(nightOfTheLivingDead)
        }

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.RateLimited), outcome)
    }

    @Test
    fun `an unreachable Archive is unreachable`() = runTest {
        val closedServer = RecordedInternetArchive().use { it.settings }

        val outcome = InternetArchiveSource(JdkHttpClient(), closedServer).lookUp(nightOfTheLivingDead)

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable), outcome)
    }

    private suspend fun lookUp(title: TitleReference): LookupOutcome =
        RecordedInternetArchive().use { archive -> InternetArchiveSource(JdkHttpClient(), archive.settings).lookUp(title) }

    private suspend fun streamsFor(title: TitleReference): List<Stream> =
        assertIs<LookupOutcome.Streams>(lookUp(title)).streams

    private fun movie(tmdbId: Int, imdbId: String, wikidataId: String, title: String, year: Int) = TitleReference(
        kind = Title.Kind.Movie,
        tmdbId = tmdbId,
        imdbId = imdbId,
        wikidataId = wikidataId,
        title = title,
        originalTitle = title,
        alternativeTitles = emptyList(),
        year = year,
    )

    private val nightOfTheLivingDead = movie(10331, "tt0063350", "Q623051", "Night of the Living Dead", 1968)
    private val theScarecrow = movie(51357, "tt0011656", "Q1057612", "The Scarecrow", 1920)
    private val pygmalion = movie(25016, "tt0030637", "Q1196539", "Pygmalion", 1938)
    private val theQuatermassXperiment = movie(24580, "tt0049646", "Q1683913", "The Quatermass Xperiment", 1955)
    private val gunsmokeRanch = movie(38764, "tt0028971", "Q5619405", "Gunsmoke Ranch", 1937)
    private val murderAtMidnight = movie(156331, "tt0022172", "Q12126345", "Murder at Midnight", 1931)
}
