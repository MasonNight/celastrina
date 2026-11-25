package dev.celastrina.core.extension

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.Stream
import dev.celastrina.core.testing.JvmExtensionLoader
import dev.celastrina.core.testing.RecordedExtensionSites
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Lookups answered by the test Extension's provider, a Source like any other once installed. */
class ExtensionMatchingTest {

    @TempDir
    lateinit var dir: File

    private val sites = RecordedExtensionSites()

    @AfterEach
    fun stopServer() = sites.close()

    private suspend fun extensionSource(): Source {
        val service = ConfigurationService(
            FileStorage(File(dir, "storage")),
            JdkHttpClient(),
            appVersion = "1.2.3",
            projectTmdbKey = "",
            extensionLoader = JvmExtensionLoader(File(dir, "code")),
            logger = RecordingLogger(),
        )
        service.importRepository(sites.repositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Computer)
        assertIs<InstallOutcome.Installed>(service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer))
        return service.sources().single { it.id == "internetarchive-internet-archive-test" }
    }

    private suspend fun streamsFor(title: TitleReference): List<Stream> =
        assertIs<LookupOutcome.Streams>(extensionSource().lookUp(title)).streams

    private val searches get() = sites.requests.filter { it.startsWith("/advancedsearch.php") }

    @Test
    fun `an Extension's Source plays Movies and Series and waits 30 s for an answer`() = runTest {
        val kind = extensionSource().kind

        assertEquals(setOf(Title.Kind.Movie, Title.Kind.Series), kind.titleKinds)
        assertEquals(30.seconds, kind.lookupTimeout)
        assertTrue(kind.canMatch(movie("Night of the Living Dead", 1968, imdbId = null)), "A Title with no IMDb ID can be matched by name")
    }

    @Test
    fun `a Movie with an IMDb ID is opened by that ID, without a search`() = runTest {
        val streams = streamsFor(movie("Night of the Living Dead", 1968, imdbId = "tt0063350"))

        assertEquals(
            Stream(
                sourceId = "internetarchive-internet-archive-test",
                id = streams.first().id,
                url = "https://archive.org/download/Night.Of.The.Living.Dead_1080p/NightOfTheLivingDead_PS3.mp4",
                delivery = Stream.Delivery.Progressive,
                label = "NightOfTheLivingDead_PS3.mp4",
                resolution = 1080,
            ),
            streams.first(),
        )
        assertEquals(emptyList(), searches)
    }

    @Test
    fun `each Stream keeps its ID from one Lookup to the next`() = runTest {
        val title = movie("Night of the Living Dead", 1968, imdbId = "tt0063350")

        val first = streamsFor(title).map { it.id }

        assertEquals(first, streamsFor(title).map { it.id })
        assertEquals(first.size, first.toSet().size, "Two Streams share an ID")
    }

    @Test
    fun `a Movie without an IMDb ID is found by a search for its title, kind and year`() = runTest {
        val streams = streamsFor(movie("Murder at Midnight", 1931, imdbId = null))

        assertEquals(listOf("https://archive.org/download/Murder_at_Midnight_1931/Murder_at_Midnight_1931.mp4"), streams.map { it.url }.take(1))
        assertEquals(1, searches.size)
    }

    @Test
    fun `a search hit a year either side still matches, but not two`() = runTest {
        assertIs<LookupOutcome.Streams>(extensionSource().lookUp(movie("Murder at Midnight", 1932, imdbId = null)))
        assertIs<LookupOutcome.Streams>(extensionSource().lookUp(movie("Murder at Midnight", 1930, imdbId = null)))
        assertEquals(LookupOutcome.NotAvailable, extensionSource().lookUp(movie("Murder at Midnight", 1933, imdbId = null)))
    }

    @Test
    fun `a search hit of another kind doesn't match`() = runTest {
        val series = movie("Murder at Midnight", 1931, imdbId = null).copy(kind = Title.Kind.Series, episode = episode(1, 1, 1))

        assertEquals(LookupOutcome.NotAvailable, extensionSource().lookUp(series))
    }

    @Test
    fun `two hits with the Title's name and year are ambiguous, so Not available`() = runTest {
        assertEquals(LookupOutcome.NotAvailable, extensionSource().lookUp(movie("Gunsmoke Ranch", 1937, imdbId = null)))
    }

    @Test
    fun `a Title the search doesn't find by its title is found by an alternative title`() = runTest {
        val title = movie("Buster Keaton: Die Vogelscheuche", 1920, imdbId = null).copy(alternativeTitles = listOf("The Scarecrow"))

        val streams = streamsFor(title)

        assertEquals("https://archive.org/download/the-scarecrow-1920-tmdb51357/The%20Scarecrow%20%281920%29.mp4", streams.first().url)
        assertEquals(2, searches.size)
    }

    @Test
    fun `a matched item with no free licence or public-domain marking is Not available`() = runTest {
        assertEquals(LookupOutcome.NotAvailable, extensionSource().lookUp(movie("The Quatermass Xperiment", 1955, imdbId = null)))

        // The search also finds "The Quatermass Xperiment [1956] - Trailer"; only the hit named as the Title matched.
        assertTrue("/metadata/the-quatermass-xperiment" in sites.requests, sites.requests.toString())
    }

    @Test
    fun `an episode is the one with the same season and number`() = runTest {
        val streams = streamsFor(pioneerOne(episode(season = 1, episode = 2, absolute = 2)))

        assertEquals(
            "https://archive.org/download/pioneer.one.season1.720p.x264-vodo/Pioneer.One.S01E02.720p.x264-VODO/Pioneer.One.S01E02.720p.x264-VODO.mp4",
            streams.first().url,
        )
        assertTrue(streams.all { "S01E02" in it.url }, streams.joinToString { it.url })
    }

    @Test
    fun `an episode numbered differently by the Extension falls back to its absolute number`() = runTest {
        val streams = streamsFor(pioneerOne(episode(season = 2, episode = 1, absolute = 3)))

        assertTrue(streams.all { "S01E03" in it.url }, streams.joinToString { it.url })
    }

    @Test
    fun `an episode the Extension doesn't have is Not available`() = runTest {
        assertEquals(LookupOutcome.NotAvailable, extensionSource().lookUp(pioneerOne(episode(season = 1, episode = 9, absolute = 9))))
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

    private fun pioneerOne(episode: TitleReference.Episode) = TitleReference(
        kind = Title.Kind.Series,
        tmdbId = 34966,
        imdbId = "tt1748166",
        wikidataId = "Q639740",
        title = "Pioneer One",
        originalTitle = "Pioneer One",
        alternativeTitles = emptyList(),
        year = 2010,
        episode = episode,
    )

    private fun episode(season: Int, episode: Int, absolute: Int) = TitleReference.Episode(season, episode, absolute, airDate = null)
}
