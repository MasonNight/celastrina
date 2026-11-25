package dev.celastrina.core.source.jellyfin

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.Stream
import dev.celastrina.core.testing.RecordedJellyfin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Household's Jellyfin server, added the way the agent adds it, answering Lookups. */
class JellyfinSourceTest {

    @TempDir
    lateinit var dir: File

    private val jellyfin = RecordedJellyfin()

    @AfterEach
    fun stopServer() = jellyfin.close()

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

    private val pioneerOne = TitleReference(
        kind = Title.Kind.Series,
        tmdbId = 33050,
        imdbId = "tt1748166",
        wikidataId = null,
        title = "Pioneer One",
        originalTitle = "Pioneer One",
        alternativeTitles = emptyList(),
        year = 2010,
    )

    private fun pioneerOneEpisode(season: Int, episode: Int) =
        pioneerOne.copy(episode = TitleReference.Episode(season, episode, absoluteEpisode = episode, airDate = null))

    @Test
    fun `a Movie on the server is offered as a Stream that sends the login in a header`() = runTest {
        val outcome = movieNight().lookUp(nightOfTheLivingDead)

        val stream = (outcome as LookupOutcome.Streams).streams.single()
        assertEquals(
            Stream(
                sourceId = "movie-night",
                id = "5e6e8380563c5211106652362c5c6843",
                url = "${jellyfin.url}/Videos/5e6e8380563c5211106652362c5c6843/stream?static=true&MediaSourceId=5e6e8380563c5211106652362c5c6843",
                delivery = Stream.Delivery.Progressive,
                label = "Night of the Living Dead (1968)",
                resolution = 480,
                videoCodec = "h264",
                container = "mp4",
                bitrate = 834_875,
                sizeBytes = 596_645_320,
                headers = stream.headers,
            ),
            stream,
        )
        assertTrue(
            stream.headers["Authorization"].orEmpty().let { it.startsWith("MediaBrowser ") && "Token=\"${RecordedJellyfin.ACCESS_TOKEN}\"" in it },
            "The Stream doesn't carry the login: ${stream.headers}",
        )
    }

    @Test
    fun `a Movie listed under its original title is found when the Catalogue knows it by a translated one`() = runTest {
        val translated = nightOfTheLivingDead.copy(title = "La Nuit des morts-vivants")

        val outcome = movieNight().lookUp(translated)

        assertEquals("5e6e8380563c5211106652362c5c6843", (outcome as LookupOutcome.Streams).streams.single().id)
    }

    @Test
    fun `a different Movie with the same name is Not available`() = runTest {
        val bramStokersDracula = TitleReference(
            kind = Title.Kind.Movie,
            tmdbId = 6114,
            imdbId = "tt0103874",
            wikidataId = null,
            title = "Bram Stoker's Dracula",
            originalTitle = "Dracula",
            alternativeTitles = emptyList(),
            year = 1992,
        )
        val source = movieNight()

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(bramStokersDracula))
        // The 1931 Dracula is on the server, and was looked at and turned down.
        assertTrue(jellyfin.requests.any { "searchTerm=Dracula" in it }, "${jellyfin.requests}")
    }

    @Test
    fun `the 1931 Dracula is matched by its TMDB ID and shown as 1080p`() = runTest {
        val dracula = nightOfTheLivingDead.copy(tmdbId = 138, imdbId = null, wikidataId = null, title = "Dracula", originalTitle = "Dracula", year = 1931)

        val stream = (movieNight().lookUp(dracula) as LookupOutcome.Streams).streams.single()

        assertEquals(1080, stream.resolution)
    }

    @Test
    fun `a Movie that isn't on the server is Not available`() = runTest {
        val sintel = nightOfTheLivingDead.copy(tmdbId = 45745, imdbId = "tt1727587", wikidataId = null, title = "Sintel", originalTitle = "Sintel", year = 2010)

        assertEquals(LookupOutcome.NotAvailable, movieNight().lookUp(sintel))
    }

    @Test
    fun `a Movie's text subtitles come as WebVTT sidecar files, and picture subtitles are left out`() = runTest {
        val llamaDrama = nightOfTheLivingDead.copy(
            tmdbId = 253777,
            imdbId = "tt6059374",
            wikidataId = null,
            title = "Caminandes: Llama Drama",
            originalTitle = "Caminandes: Llama Drama",
            year = 2013,
        )

        val stream = (movieNight().lookUp(llamaDrama) as LookupOutcome.Streams).streams.single()

        val id = "c541e7545e58b28cd4d0da7aac89d7e8"
        assertEquals(
            listOf(Stream.Subtitle("${jellyfin.url}/Videos/$id/$id/Subtitles/2/Stream.vtt", language = "eng", label = "English - SUBRIP - External")),
            stream.subtitles,
        )
        assertEquals(1080, stream.resolution)
    }

    @Test
    fun `an episode of a Series on the server is offered`() = runTest {
        val outcome = movieNight().lookUp(pioneerOneEpisode(season = 1, episode = 3))

        val stream = (outcome as LookupOutcome.Streams).streams.single()
        assertEquals("173a94d516c79947da16ba9b1b84a4e0", stream.id)
        assertEquals("Pioneer One - S01E03 - Alone in the Night", stream.label)
    }

    @Test
    fun `an episode or season the server doesn't have is Not available`() = runTest {
        val source = movieNight()

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(pioneerOneEpisode(season = 1, episode = 7)))
        assertEquals(LookupOutcome.NotAvailable, source.lookUp(pioneerOneEpisode(season = 2, episode = 1)))
    }

    @Test
    fun `a Series is never matched to a Movie, nor a Movie to a Series`() = runTest {
        val source = movieNight()

        assertEquals(LookupOutcome.NotAvailable, source.lookUp(nightOfTheLivingDead.copy(kind = Title.Kind.Series, episode = TitleReference.Episode(1, 1, 1, null))))
        assertEquals(LookupOutcome.NotAvailable, source.lookUp(pioneerOne.copy(kind = Title.Kind.Movie)))
    }

    @Test
    fun `a server that no longer accepts the login answers login needed`() = runTest {
        val source = movieNight()
        jellyfin.tokenRevoked = true

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.AuthRequired), source.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `a server whose reverse proxy says it is down answers unreachable`() = runTest {
        val source = movieNight()
        jellyfin.behindDownProxy = true

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable), source.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `a server that has gone away answers unreachable`() = runTest {
        val source = movieNight()
        jellyfin.close()

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable), source.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `a Source whose login failed answers login needed without asking the server`() = runTest {
        val service = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")
        service.add(NewSource("jellyfin", "Movie Night", jellyfin.url, "demo", "wrong password"), ChangedFrom.Computer)
        val asked = jellyfin.requests.size

        val outcome = service.sources().single { it.id == "movie-night" }.lookUp(nightOfTheLivingDead)

        assertEquals(LookupOutcome.Failure(LookupOutcome.Failure.Reason.AuthRequired), outcome)
        assertEquals(asked, jellyfin.requests.size)
    }

    @Test
    fun `an Emby server is asked the same way`() = runTest {
        val service = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")
        service.add(NewSource("emby", "Den Emby", jellyfin.url, jellyfin.username, jellyfin.password), ChangedFrom.Computer)

        val outcome = service.sources().single { it.id == "den-emby" }.lookUp(nightOfTheLivingDead)

        assertEquals("5e6e8380563c5211106652362c5c6843", (outcome as LookupOutcome.Streams).streams.single().id)
    }

    /** The Household's "Movie Night" Jellyfin, added from a computer with the right login. */
    private suspend fun movieNight(): Source {
        val service = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")
        val added = service.add(NewSource("jellyfin", "Movie Night", jellyfin.url, jellyfin.username, jellyfin.password), ChangedFrom.Computer)
        assertEquals(WriteOutcome.Added("movie-night"), added)
        return service.sources().single { it.id == "movie-night" }
    }
}
