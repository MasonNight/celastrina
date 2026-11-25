package dev.celastrina.core.configuration

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.testing.RecordedJellyfin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Household adding a Jellyfin server on the TV by approving a Quick Connect code, so nobody types a password. */
class QuickConnectTest {

    @TempDir
    lateinit var dir: File

    private val jellyfin = RecordedJellyfin()

    @AfterEach
    fun stopServer() = jellyfin.close()

    private fun service() = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")

    private fun den(url: String = jellyfin.url, kind: String = "jellyfin") = NewSource(kind = kind, name = "Den", url = url)

    private fun storedText() = dir.walk().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test
    fun `the TV shows a code, waits while it isn't approved, then adds the server with the approving user's login`() = runTest {
        val service = service()

        val started = assertIs<QuickConnect.Waiting>(service.startQuickConnect(den()))
        assertEquals(RecordedJellyfin.QUICK_CONNECT_CODE, started.code)
        assertIs<QuickConnect.Waiting>(service.continueQuickConnect(started, ChangedFrom.Tv))
        assertEquals(listOf("internet-archive"), service().list().map { it.id.value }, "Saved before it was approved")

        jellyfin.approveQuickConnect()

        assertEquals(QuickConnect.Finished(WriteOutcome.Added(SourceId("den"))), service.continueQuickConnect(started, ChangedFrom.Tv))
        val den = service().list().last()
        assertEquals(Triple("den", TestState.Passing, true), Triple(den.id.value, den.testState, den.hasLogin))
        assertTrue(RecordedJellyfin.ACCESS_TOKEN in storedText())
        assertEquals(listOf("internet-archive", "den"), service().sources().map { it.id.value })
    }

    @Test
    fun `a server added with Quick Connect can log in again later with the approving user's password`() = runTest {
        val service = service()
        val started = assertIs<QuickConnect.Waiting>(service.startQuickConnect(den()))
        jellyfin.approveQuickConnect()
        service.continueQuickConnect(started, ChangedFrom.Tv)

        assertEquals(WriteOutcome.Ok, service.update(SourceId("den"), SourceChanges(password = jellyfin.password), ChangedFrom.Tv))
    }

    @Test
    fun `a server with Quick Connect turned off says so, and nothing is saved`() = runTest {
        jellyfin.quickConnectEnabled = false

        assertEquals(
            QuickConnect.Failed(Reason.AuthRequired, "Quick Connect is turned off on Den. Turn it on in the server's dashboard, or log in with a username and password."),
            service().startQuickConnect(den()),
        )
        assertEquals(1, service().list().size)
    }

    @Test
    fun `a server that can't be reached says so`() = runTest {
        val gone = jellyfin.url
        jellyfin.close()

        assertEquals(QuickConnect.Failed(Reason.Unreachable, "Can't reach Den at $gone."), service().startQuickConnect(den(url = gone)))
    }

    @Test
    fun `a code the server has forgotten asks to start again`() = runTest {
        val service = service()
        val started = assertIs<QuickConnect.Waiting>(service.startQuickConnect(den()))
        jellyfin.forgetQuickConnect()

        assertEquals(
            QuickConnect.Failed(Reason.AuthRequired, "The Quick Connect code for Den has expired. Start again for a new code."),
            service.continueQuickConnect(started, ChangedFrom.Tv),
        )
        assertEquals(1, service().list().size)
    }

    @Test
    fun `Quick Connect is refused before anything is asked when it can't work`() = runTest {
        val service = service()
        suspend fun refused(problem: WriteOutcome.Problem, source: NewSource) =
            assertEquals(problem, ((service.startQuickConnect(source) as QuickConnect.Finished).outcome as WriteOutcome.Invalid).problem, source.toString())
        service.add(NewSource(kind = "internet-archive", name = "Den"), ChangedFrom.Tv)

        refused(WriteOutcome.Problem.QuickConnectUnsupported, den(kind = "emby"))
        refused(WriteOutcome.Problem.QuickConnectUnsupported, den(kind = "stremio"))
        refused(WriteOutcome.Problem.BlankName, den().copy(name = " "))
        refused(WriteOutcome.Problem.BadUrl, den(url = "192.168.1.20:8096"))
        refused(WriteOutcome.Problem.NameTaken, den())

        assertEquals(emptyList(), jellyfin.requests)
    }
}
