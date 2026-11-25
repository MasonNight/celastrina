package dev.celastrina.core.configuration

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.testing.RecordedStremio
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The agent adding and changing a Household's Stremio-compatible add-ons by manifest URL. */
class StremioSourcesTest {

    @TempDir
    lateinit var dir: File

    private val addons = RecordedStremio()

    @AfterEach
    fun stopServer() = addons.close()

    private val clock = VirtualClock()

    private fun service() = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "", clock = clock)

    private fun household(url: String = addons.manifestUrl(RecordedStremio.HOUSEHOLD)) = NewSource(kind = "stremio", name = "Household Archive", url = url)

    private fun storedText() = dir.walk().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test
    fun `adding an add-on by its manifest URL fetches the manifest, and puts it last in the order`() = runTest {
        val outcome = service().add(household(), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Added(SourceId("household-archive")), outcome)
        assertEquals(
            SourceSummary(
                id = SourceId("household-archive"),
                name = "Household Archive",
                kind = "stremio",
                enabled = true,
                position = 2,
                testState = TestState.Passing,
                hasLogin = false,
                addedFromComputerAt = clock.nowMillis(),
                changedFromComputerAt = null,
            ),
            service().list().last(),
        )
        assertEquals(listOf("/household/manifest.json"), addons.requests)
    }

    @Test
    fun `an add-on that offers no Streams is saved as failing, and says why`() = runTest {
        val outcome = service().add(household(addons.manifestUrl(RecordedStremio.CINEMETA)).copy(name = "Cinemeta"), ChangedFrom.Computer)

        assertEquals(
            WriteOutcome.Failed(SourceId("cinemeta"), Reason.SourceError, "Cinemeta offers no Streams. Celastrina uses only an add-on's Streams."),
            outcome,
        )
        assertEquals(TestState.Failing, service().list().last().testState)
    }

    @Test
    fun `a URL that doesn't answer with a manifest is saved as failing, and says so`() = runTest {
        val outcome = service().add(household(addons.manifestUrl("nothing-here")), ChangedFrom.Computer)

        assertEquals(
            WriteOutcome.Failed(SourceId("household-archive"), Reason.SourceError, "Household Archive didn't answer with an add-on manifest. Check the URL."),
            outcome,
        )
    }

    @Test
    fun `an add-on that can't be reached is saved as failing, without repeating its URL, which may hold a secret`() = runTest {
        addons.takeDown(RecordedStremio.HOUSEHOLD)

        val outcome = service().add(household(), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Failed(SourceId("household-archive"), Reason.Unreachable, "Can't reach the add-on Household Archive."), outcome)
        assertEquals(TestState.Failing, service().list().last().testState)
    }

    @Test
    fun `an add that can't work is refused before anything is saved or asked`() = runTest {
        val service = service()
        suspend fun refused(problem: WriteOutcome.Problem, source: NewSource) =
            assertEquals(problem, (service.add(source, ChangedFrom.Computer) as WriteOutcome.Invalid).problem, source.toString())

        refused(WriteOutcome.Problem.BadUrl, household(url = addons.manifestUrl(RecordedStremio.HOUSEHOLD).removeSuffix("/manifest.json")))
        refused(WriteOutcome.Problem.BadUrl, household(url = "ftp://127.0.0.1/manifest.json"))
        refused(WriteOutcome.Problem.BadUrl, household().copy(url = null))
        refused(WriteOutcome.Problem.UnneededSetting, household().copy(username = "demo", password = "hunter2"))

        assertEquals(1, service().list().size)
        assertEquals(emptyList(), addons.requests)
    }

    @Test
    fun `a bad manifest URL is explained in the add-on's terms`() = runTest {
        val outcome = service().add(household(url = "https://example.org/addon"), ChangedFrom.Computer)

        assertEquals(
            WriteOutcome.Invalid(
                WriteOutcome.Problem.BadUrl,
                "A Stremio-compatible add-on Source needs its manifest URL, starting http:// or https:// and ending /manifest.json.",
            ),
            outcome,
        )
    }

    @Test
    fun `pointing an add-on at a new manifest URL tests it again`() = runTest {
        val service = service()
        service.add(household(), ChangedFrom.Computer)

        val outcome = service.update(SourceId("household-archive"), SourceChanges(url = addons.manifestUrl(RecordedStremio.CINEMETA)), ChangedFrom.Computer)

        assertEquals(Reason.SourceError, (outcome as WriteOutcome.Failed).reason)
        assertEquals(TestState.Failing, service().list().last().testState)
        assertEquals(
            WriteOutcome.Problem.UnneededSetting,
            (service.update(SourceId("household-archive"), SourceChanges(username = "demo", password = "x"), ChangedFrom.Computer) as WriteOutcome.Invalid).problem,
        )
    }

    @Test
    fun `removing an add-on forgets its manifest too`() = runTest {
        val service = service()
        service.add(household(), ChangedFrom.Computer)

        service.remove(SourceId("household-archive"), ChangedFrom.Computer)

        assertFalse("org.household.archive" in storedText(), "The manifest is still saved")
    }
}
