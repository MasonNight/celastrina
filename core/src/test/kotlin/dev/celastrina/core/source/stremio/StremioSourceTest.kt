package dev.celastrina.core.source.stremio

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.RecordedStremio
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Household's Stremio-compatible add-ons, added by manifest URL the way the agent adds them, answering Lookups. */
class StremioSourceTest {

    @TempDir
    lateinit var dir: File

    private val addons = RecordedStremio()

    @AfterEach
    fun stopServer() = addons.close()

    private val nightOfTheLivingDead = TitleReference(
        kind = Title.Kind.Movie,
        tmdbId = 10331,
        imdbId = "tt0063350",
        wikidataId = "Q623051",
        title = "Night of the Living Dead",
        originalTitle = "Night of the Living Dead",
        alternativeTitles = emptyList(),
        year = 1968,
    )

    private val pioneerOneEpisode = TitleReference(
        kind = Title.Kind.Series,
        tmdbId = 33050,
        imdbId = "tt1748166",
        wikidataId = null,
        title = "Pioneer One",
        originalTitle = "Pioneer One",
        alternativeTitles = emptyList(),
        year = 2010,
        episode = TitleReference.Episode(season = 1, episode = 1, absoluteEpisode = 1, airDate = null),
    )

    private val sintel = nightOfTheLivingDead.copy(tmdbId = 45745, imdbId = "tt1727587", wikidataId = null, title = "Sintel", originalTitle = "Sintel", year = 2010)

    private val ia = "https://archive.org/download/Night.Of.The.Living.Dead_1080p"

    @Test
    fun `a Movie's Stream takes its quality from the Celastrina extension, whatever its name says`() = runTest {
        val streams = (household().lookUp(nightOfTheLivingDead) as LookupOutcome.Streams).streams

        assertEquals(
            Stream(
                sourceId = SourceId("household-archive"),
                id = "notld-1080p-mkv",
                url = "$ia/NightOfTheLivingDead_1080p.mkv",
                delivery = Stream.Delivery.Progressive,
                label = "Night of the Living Dead (1968)",
                resolution = 1080,
                videoCodec = "h264",
                container = "mkv",
                audioLanguages = listOf("eng"),
                bitrate = 16_024_000,
                sizeBytes = 11_523_767_255,
            ),
            streams.first(),
        )
    }

    @Test
    fun `a Stream without the extension takes its quality from its name, and keeps its subtitles`() = runTest {
        val streams = (household().lookUp(nightOfTheLivingDead) as LookupOutcome.Streams).streams

        val stream = streams.single { it.url == "$ia/NightOfTheLivingDead_720p.mp4" }
        assertEquals(720, stream.resolution)
        assertEquals(Stream.Delivery.Progressive, stream.delivery)
        assertEquals(597_796_730, stream.sizeBytes)
        assertEquals(listOf(Stream.Subtitle("$ia/NightOfTheLivingDead_DVD5.asr.srt", language = "eng")), stream.subtitles)
    }

    @Test
    fun `an HLS Stream is offered as HLS with the headers the add-on asks playback to send`() = runTest {
        val streams = (household().lookUp(nightOfTheLivingDead) as LookupOutcome.Streams).streams

        val stream = streams.single { it.url.endsWith(".m3u8") }
        assertEquals(Stream.Delivery.Hls, stream.delivery)
        assertEquals(mapOf("Referer" to "http://192.168.1.20:8080/"), stream.headers)
        assertEquals(null, stream.resolution)
    }

    @Test
    fun `Streams Stream IDs stay the same from one Lookup to the next`() = runTest {
        val source = household()

        val first = (source.lookUp(nightOfTheLivingDead) as LookupOutcome.Streams).streams.map { it.id }
        val second = (source.lookUp(nightOfTheLivingDead) as LookupOutcome.Streams).streams.map { it.id }

        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size, "Two Streams share an ID: $first")
    }

    @Test
    fun `torrent, YouTube, Usenet, external-app and DRM Streams are logged and not offered`() = runTest {
        val logger = RecordingLogger()

        val answer = lookUp(household(), nightOfTheLivingDead, logger)

        val offered = (answer.outcome as LookupOutcome.Streams).streams
        assertEquals(
            listOf("$ia/NightOfTheLivingDead_1080p.mkv", "$ia/NightOfTheLivingDead_720p.mp4", "http://192.168.1.20:8080/hls/night-of-the-living-dead/master.m3u8"),
            offered.map { it.url },
        )
        val dropped = logger.messages.filter { it.startsWith("Dropped Stream") && "household-archive" in it }
        assertEquals(5, dropped.size, "${logger.messages}")
        listOf("Torrent", "ExternalApp", "Usenet", "DRM").forEach { why ->
            assertTrue(dropped.any { it.endsWith(why) }, "Nothing dropped for $why: $dropped")
        }
    }

    @Test
    fun `an add-on whose Streams are all torrents is Not available, and each one is logged`() = runTest {
        val logger = RecordingLogger()
        val source = added(RecordedStremio.PUBLIC_DOMAIN_MOVIES, "Public Domain Movies")

        val answer = lookUp(source, nightOfTheLivingDead, logger)

        assertEquals(LookupOutcome.NotAvailable, answer.outcome)
        assertEquals(1, logger.messages.count { it.startsWith("Dropped Stream") && it.endsWith("Torrent") }, "${logger.messages}")
    }

    @Test
    fun `an add-on whose Streams all open other apps is Not available, and each one is logged`() = runTest {
        val logger = RecordingLogger()
        val source = added(RecordedStremio.WATCHHUB, "WatchHub")

        val answer = lookUp(source, nightOfTheLivingDead, logger)

        assertEquals(LookupOutcome.NotAvailable, answer.outcome)
        assertTrue(logger.messages.isNotEmpty() && logger.messages.all { it.startsWith("Dropped Stream") && it.endsWith("ExternalApp") }, "${logger.messages}")
    }

    @Test
    fun `only the add-on's manifest and stream resource are asked for, never its catalog or meta`() = runTest {
        val source = household()

        source.lookUp(nightOfTheLivingDead)

        assertEquals(listOf("/household/manifest.json", "/household/stream/movie/tt0063350.json"), addons.requests)
    }

    @Test
    fun `an episode is asked for by its IMDb ID, season and episode`() = runTest {
        val outcome = household().lookUp(pioneerOneEpisode)

        val stream = (outcome as LookupOutcome.Streams).streams.single()
        assertEquals(360, stream.resolution)
        assertEquals("Pioneer One S01E01 Earthfall", stream.label)
        assertEquals("/household/stream/series/tt1748166:1:1.json", addons.requests.last())
    }

    @Test
    fun `a Title the add-on has no Streams for is Not available, whether it answers empty or has no file for it`() = runTest {
        val source = household()

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(sintel))
        assertEquals(LookupOutcome.NotAvailable, source.lookUp(pioneerOneEpisode.copy(episode = TitleReference.Episode(1, 7, 7, null))))
    }

    @Test
    fun `an add-on whose handler fails answers a Source error`() = runTest {
        val shawshank = nightOfTheLivingDead.copy(tmdbId = 278, imdbId = "tt0111161", title = "The Shawshank Redemption", year = 1994)

        assertEquals(LookupOutcome.Failure(Reason.SourceError), household().lookUp(shawshank))
    }

    @Test
    fun `an add-on that is down or gone answers unreachable`() = runTest {
        val source = household()

        addons.takeDown(RecordedStremio.HOUSEHOLD)
        assertEquals(LookupOutcome.Failure(Reason.Unreachable), source.lookUp(nightOfTheLivingDead))
        addons.close()
        assertEquals(LookupOutcome.Failure(Reason.Unreachable), source.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `an add-on whose Streams are only for its own IDs is never asked about a Title from the Catalogue`() = runTest {
        val source = added(RecordedStremio.GAME_CINEMA, "Game Cinema")

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(nightOfTheLivingDead))
        assertEquals(listOf("/game-cinema/manifest.json"), addons.requests)
    }

    @Test
    fun `a movies-only add-on is never asked about a Series`() = runTest {
        val source = added(RecordedStremio.PUBLIC_DOMAIN_MOVIES, "Public Domain Movies")

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(pioneerOneEpisode))
        assertFalse(addons.requests.any { "/stream/" in it }, "${addons.requests}")
    }

    @Test
    fun `a Title without an IMDb ID is not looked up on an add-on`() = runTest {
        val source = household()
        val lookup = StreamLookup(listOf(source), RecordingLogger())

        assertEquals(emptyList(), lookup.lookUp(nightOfTheLivingDead.copy(imdbId = null)).toList())
        assertFalse(addons.requests.any { "/stream/" in it }, "${addons.requests}")
    }

    @Test
    fun `an add-on that was down when it was added is asked once it is back`() = runTest {
        addons.takeDown(RecordedStremio.HOUSEHOLD)
        val service = service()
        service.add(NewSource("stremio", "Household Archive", addons.manifestUrl(RecordedStremio.HOUSEHOLD)), ChangedFrom.Computer)
        addons.bringBack(RecordedStremio.HOUSEHOLD)

        val outcome = service.sources().single { it.id == SourceId("household-archive") }.lookUp(nightOfTheLivingDead)

        assertEquals("notld-1080p-mkv", (outcome as LookupOutcome.Streams).streams.first().id)
    }

    /** A whole Lookup, on real time: the add-ons answer over real HTTP, which the test scheduler's virtual time would outrun. */
    private suspend fun lookUp(source: Source, title: TitleReference, logger: RecordingLogger) =
        withContext(Dispatchers.Default) { StreamLookup(listOf(source), logger).lookUp(title).first() }

    private fun service() = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")

    private suspend fun household(): Source = added(RecordedStremio.HOUSEHOLD, "Household Archive")

    private suspend fun added(addon: String, name: String): Source {
        val service = service()
        service.add(NewSource("stremio", name, addons.manifestUrl(addon)), ChangedFrom.Computer)
        return service.sources().last()
    }
}
