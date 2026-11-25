package dev.celastrina.core.configuration

import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationServiceTest {

    @TempDir
    lateinit var dir: File

    /** Nothing here goes online: no command in these tests runs a connection test. */
    private val offline = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = error("No network in these tests: $url")
    }

    private fun service() = ConfigurationService(FileStorage(dir), offline, appVersion = "1.2.3")

    @Test
    fun `a new Household's status shows the app version, one Source, no Extensions and the protocol version`() {
        assertEquals(Status(appVersion = "1.2.3", sources = 1, extensions = 0, protocolVersion = 1), service().status())
    }

    @Test
    fun `a new Household has just the Internet Archive, enabled, first, untested and without a login`() {
        assertEquals(
            listOf(
                SourceSummary(
                    id = "internet-archive",
                    name = "Internet Archive",
                    kind = "internet-archive",
                    enabled = true,
                    position = 1,
                    testState = TestState.Untested,
                    hasLogin = false,
                ),
            ),
            service().list(),
        )
    }

    @Test
    fun `renaming a Source from a computer keeps its ID, survives a restart and is announced`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.rename("internet-archive", "  Archive.org  ", ChangedFrom.Computer))

        val afterRestart = service().list().single()
        assertEquals("internet-archive" to "Archive.org", afterRestart.id to afterRestart.name)
        assertEquals("Archive.org", service().sources().single().name)
        assertEquals(
            listOf(ConfigurationChange(ConfigurationChange.Action.Renamed, "Archive.org", ChangedFrom.Computer, previousName = "Internet Archive")),
            announced,
        )
    }

    @Test
    fun `renaming to the same name again is harmless and announces nothing`() = runTest {
        val service = service()
        service.rename("internet-archive", "Archive.org", ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.rename("internet-archive", "Archive.org", ChangedFrom.Computer))

        assertEquals(emptyList(), announced)
    }

    @Test
    fun `a rename with a blank name or of a Source that doesn't exist is refused and changes nothing`() {
        val service = service()

        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.BlankName, "A Source needs a name."),
            service.rename("internet-archive", " ", ChangedFrom.Computer),
        )
        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID jellyfin."),
            service.rename("jellyfin", "Jellyfin", ChangedFrom.Computer),
        )
        assertEquals(listOf("Internet Archive"), service().list().map { it.name })
    }

    @Test
    fun `a disabled Source stays listed but isn't asked in a Lookup, and enabling it brings it back`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.disable("internet-archive", ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.disable("internet-archive", ChangedFrom.Computer))

        assertEquals(listOf(false), service().list().map { it.enabled })
        assertEquals(emptyList(), service().sources())

        assertEquals(WriteOutcome.Ok, service.enable("internet-archive", ChangedFrom.Tv))
        assertEquals(WriteOutcome.Ok, service.enable("internet-archive", ChangedFrom.Tv))

        assertEquals(listOf("internet-archive"), service().sources().map { it.id })
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

        assertEquals(WriteOutcome.Ok, service.remove("internet-archive", ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.remove("internet-archive", ChangedFrom.Computer))

        val afterRestart = service()
        assertEquals(emptyList(), afterRestart.list())
        assertEquals(emptyList(), afterRestart.sources())
        assertEquals(0, afterRestart.status().sources)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.Removed, "Internet Archive", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `enabling or disabling a Source that doesn't exist is refused`() {
        val unknown = WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID jellyfin.")

        assertEquals(unknown, service().enable("jellyfin", ChangedFrom.Computer))
        assertEquals(unknown, service().disable("jellyfin", ChangedFrom.Computer))
    }

    /** Everything [service] announces from now on, as the on-screen notices would see it. */
    private fun TestScope.announcementsOf(service: ConfigurationService): List<ConfigurationChange> {
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }
        return announced
    }
}
