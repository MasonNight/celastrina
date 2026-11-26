package dev.celastrina.core.configuration

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.household.ContinueWatching
import dev.celastrina.core.household.Watching
import dev.celastrina.core.household.Watchlist
import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.source.Stream
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.testing.VirtualClock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationServiceTest {

    @TempDir
    lateinit var dir: File

    /** Nothing here goes online: the Internet Archive has no connection test. */
    private val offline = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = error("No network in these tests: $url")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse = error("No network in these tests: $url")
    }

    private val clock = VirtualClock()

    private fun service(projectTmdbKey: String = PROJECT_KEY) =
        ConfigurationService(FileStorage(dir), offline, appVersion = "1.2.3", projectTmdbKey = projectTmdbKey, clock = clock)

    @Test
    fun `a new Household's status shows the app version, the project TMDB key, one Source, no Extensions and the protocol version`() {
        assertEquals(
            Status(appVersion = "1.2.3", tmdbKey = TmdbKeyInUse.Project, sources = 1, extensions = 0, protocolVersion = 1),
            service().status(),
        )
    }

    @Test
    fun `a new Household's Catalogue uses the project TMDB key`() {
        assertEquals(PROJECT_KEY, service().tmdbSettings().apiKey)
    }

    @Test
    fun `a TMDB key set from a computer replaces the project key, survives a restart and is announced`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.setTmdbKey("  household-key  ", ChangedFrom.Computer))

        val afterRestart = service()
        assertEquals("household-key", afterRestart.tmdbSettings().apiKey)
        assertEquals(TmdbKeyInUse.Override, afterRestart.status().tmdbKey)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.TmdbKeySet, sourceName = null, ChangedFrom.Computer)), announced)
    }

    @Test
    fun `status says which TMDB key is in use without revealing either`() {
        val service = service()
        service.setTmdbKey("household-key", ChangedFrom.Computer)

        val status = service.status().toString()

        assertFalse("household-key" in status, status)
        assertFalse(PROJECT_KEY in status, status)
    }

    @Test
    fun `setting the same TMDB key again is harmless and announces nothing`() = runTest {
        val service = service()
        service.setTmdbKey("household-key", ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.setTmdbKey("household-key", ChangedFrom.Computer))

        assertEquals(emptyList(), announced)
    }

    @Test
    fun `a blank TMDB key is refused and keeps the key in use`() {
        val service = service()
        service.setTmdbKey("household-key", ChangedFrom.Computer)

        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.BlankTmdbKey, "A TMDB key can't be blank. To go back to the project key, clear the key instead."),
            service.setTmdbKey(" ", ChangedFrom.Computer),
        )
        assertEquals("household-key", service().tmdbSettings().apiKey)
    }

    @Test
    fun `clearing the TMDB key goes back to the project key, and clearing it again is harmless`() = runTest {
        val service = service()
        service.setTmdbKey("household-key", ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.clearTmdbKey(ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.clearTmdbKey(ChangedFrom.Computer))

        val afterRestart = service()
        assertEquals(PROJECT_KEY, afterRestart.tmdbSettings().apiKey)
        assertEquals(TmdbKeyInUse.Project, afterRestart.status().tmdbKey)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.TmdbKeyCleared, sourceName = null, ChangedFrom.Computer)), announced)
    }

    @Test
    fun `a build without a project key has no TMDB key until the Household sets one`() {
        val service = service(projectTmdbKey = "")
        assertEquals(TmdbKeyInUse.None, service.status().tmdbKey)
        assertEquals("", service.tmdbSettings().apiKey)

        service.setTmdbKey("household-key", ChangedFrom.Computer)
        assertEquals(TmdbKeyInUse.Override, service(projectTmdbKey = "").status().tmdbKey)

        service.clearTmdbKey(ChangedFrom.Computer)
        assertEquals(TmdbKeyInUse.None, service(projectTmdbKey = "").status().tmdbKey)
    }

    @Test
    fun `a new Household has just the Internet Archive, enabled, first, untested and without a login`() {
        assertEquals(
            listOf(
                SourceSummary(
                    id = SourceId("internet-archive"),
                    name = "Internet Archive",
                    kind = "internet-archive",
                    enabled = true,
                    position = 1,
                    testState = TestState.Untested,
                    hasLogin = false,
                    addedFromComputerAt = null,
                    changedFromComputerAt = null,
                ),
            ),
            service().list(),
        )
    }

    @Test
    fun `renaming a Source from a computer keeps its ID, survives a restart and is announced`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.update(SourceId("internet-archive"), SourceChanges(name = "  Archive.org  "), ChangedFrom.Computer))

        val afterRestart = service().list().single()
        assertEquals("internet-archive" to "Archive.org", afterRestart.id.value to afterRestart.name)
        assertEquals("Archive.org", service().sources().single().name)
        assertEquals(
            listOf(ConfigurationChange(ConfigurationChange.Action.Renamed, "Archive.org", ChangedFrom.Computer, previousName = "Internet Archive")),
            announced,
        )
    }

    @Test
    fun `renaming to the same name again is harmless and announces nothing`() = runTest {
        val service = service()
        service.update(SourceId("internet-archive"), SourceChanges(name = "Archive.org"), ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.update(SourceId("internet-archive"), SourceChanges(name = "Archive.org"), ChangedFrom.Computer))

        assertEquals(emptyList(), announced)
    }

    @Test
    fun `a rename with a blank name or of a Source that doesn't exist is refused and changes nothing`() = runTest {
        val service = service()

        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.BlankName, "A Source needs a name."),
            service.update(SourceId("internet-archive"), SourceChanges(name = " "), ChangedFrom.Computer),
        )
        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID jellyfin."),
            service.update(SourceId("jellyfin"), SourceChanges(name = "Jellyfin"), ChangedFrom.Computer),
        )
        assertEquals(listOf("Internet Archive"), service().list().map { it.name })
    }

    @Test
    fun `a disabled Source stays listed but isn't asked in a Lookup, and enabling it brings it back`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.disable(SourceId("internet-archive"), ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.disable(SourceId("internet-archive"), ChangedFrom.Computer))

        assertEquals(listOf(false), service().list().map { it.enabled })
        assertEquals(emptyList(), service().sources())

        assertEquals(WriteOutcome.Ok, service.enable(SourceId("internet-archive"), ChangedFrom.Tv))
        assertEquals(WriteOutcome.Ok, service.enable(SourceId("internet-archive"), ChangedFrom.Tv))

        assertEquals(listOf("internet-archive"), service().sources().map { it.id.value })
        assertEquals(
            listOf(
                ConfigurationChange(ConfigurationChange.Action.Disabled, "Internet Archive", ChangedFrom.Computer),
                ConfigurationChange(ConfigurationChange.Action.Enabled, "Internet Archive", ChangedFrom.Tv),
            ),
            announced,
        )
    }

    @Test
    fun `a removed Source is gone for good, and removing it again is harmless`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.remove(SourceId("internet-archive"), ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.remove(SourceId("internet-archive"), ChangedFrom.Computer))

        val afterRestart = service()
        assertEquals(emptyList(), afterRestart.list())
        assertEquals(emptyList(), afterRestart.sources())
        assertEquals(0, afterRestart.status().sources)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.Removed, "Internet Archive", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `removing a Source forgets its Streams' Failover memory, and only its`() {
        val memory = FailoverMemory(FileStorage(dir), clock)
        val service = ConfigurationService(FileStorage(dir), offline, appVersion = "1.2.3", projectTmdbKey = PROJECT_KEY, clock = clock, failoverMemory = memory)
        val archive = Stream(sourceId = SourceId("internet-archive"), id = "a", url = "https://archive.org/a.mp4", delivery = Stream.Delivery.Progressive)
        val other = Stream(sourceId = SourceId("movie-night"), id = "b", url = "http://192.168.1.20/b.mp4", delivery = Stream.Delivery.Progressive)
        memory.demote(archive)
        memory.demote(other)

        service.remove(SourceId("internet-archive"), ChangedFrom.Tv)

        assertFalse(memory.isDemoted(archive))
        assertTrue(FailoverMemory(FileStorage(dir), clock).isDemoted(other))
        assertFalse(FailoverMemory(FileStorage(dir), clock).isDemoted(archive))
    }

    @Test
    fun `removing a Source leaves Continue watching and the Watchlist as they were`() {
        val continueWatching = ContinueWatching(FileStorage(dir), clock)
        val watchlist = Watchlist(FileStorage(dir))
        val theMatrix = Title(603, Title.Kind.Movie, "The Matrix", 1999, posterUrl = null, category = null)
        val watching = Watching(theMatrix, TitleReference(Title.Kind.Movie, 603, "tt0133093", null, "The Matrix", "The Matrix", emptyList(), 1999), null, null)
        val archive = Stream(sourceId = SourceId("internet-archive"), id = "a", url = "https://archive.org/a.mp4", delivery = Stream.Delivery.Progressive)
        continueWatching.save(watching, archive, positionMillis = 600_000, durationMillis = 8_160_000)
        watchlist.add(theMatrix)

        service().remove(SourceId("internet-archive"), ChangedFrom.Tv)

        val afterRestart = ContinueWatching(FileStorage(dir), clock)
        assertEquals(listOf(watching), afterRestart.row.value.map { it.watching })
        assertEquals(WorkedStream(SourceId("internet-archive"), "a"), afterRestart.of(watching.reference)?.lastWorked)
        assertEquals(listOf(theMatrix), Watchlist(FileStorage(dir)).titles.value)
    }

    @Test
    fun `a Source added from a computer is marked with when, and one added on the TV isn't`() = runTest {
        val service = service()
        val addedAt = clock.nowMillis()

        service.add(NewSource(kind = "internet-archive", name = "Archive Two"), ChangedFrom.Computer)
        service.add(NewSource(kind = "internet-archive", name = "Archive Three"), ChangedFrom.Tv)

        assertEquals(
            listOf(null, addedAt, null),
            service().list().map { it.addedFromComputerAt },
        )
    }

    @Test
    fun `a Source changed from a computer is marked with when it last was, and changes on the TV or that change nothing leave it`() = runTest {
        val service = service()
        clock.advanceBy(1.days)
        service.update(SourceId("internet-archive"), SourceChanges(name = "Archive.org"), ChangedFrom.Computer)
        val renamedAt = clock.nowMillis()

        clock.advanceBy(1.days)
        service.update(SourceId("internet-archive"), SourceChanges(name = "Archive.org"), ChangedFrom.Computer)
        service.disable(SourceId("internet-archive"), ChangedFrom.Tv)
        assertEquals(renamedAt, service().list().single().changedFromComputerAt)

        service.enable(SourceId("internet-archive"), ChangedFrom.Computer)
        assertEquals(clock.nowMillis(), service().list().single().changedFromComputerAt)
        assertEquals(null, service().list().single().addedFromComputerAt)
    }

    @Test
    fun `enabling or disabling a Source that doesn't exist is refused`() = runTest {
        val unknown = WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID jellyfin.")

        assertEquals(unknown, service().enable(SourceId("jellyfin"), ChangedFrom.Computer))
        assertEquals(unknown, service().disable(SourceId("jellyfin"), ChangedFrom.Computer))
    }

    private companion object {
        const val PROJECT_KEY = "project-key"
    }

    /** Everything [service] announces from now on, as the on-screen notices would see it. */
    private fun TestScope.announcementsOf(service: ConfigurationService): List<ConfigurationChange> {
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }
        return announced
    }
}
