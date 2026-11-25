package dev.celastrina.core.configuration

import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.testing.RecordedJellyfin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The agent adding, changing, ordering and removing a Household's Jellyfin and Emby servers. */
class MediaServerSourcesTest {

    @TempDir
    lateinit var dir: File

    private val jellyfin = RecordedJellyfin()

    @AfterEach
    fun stopServer() = jellyfin.close()

    private fun service() = ConfigurationService(FileStorage(dir), JdkHttpClient(), appVersion = "1.2.3", projectTmdbKey = "")

    private fun movieNight(password: String = jellyfin.password, url: String = jellyfin.url) =
        NewSource(kind = "jellyfin", name = "Movie Night Jellyfin", url = url, username = jellyfin.username, password = password)

    private fun storedText() = dir.walk().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test
    fun `adding a Jellyfin server logs in, keeps the token but never the password, and puts it last in the order`() = runTest {
        val service = service()
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Added("movie-night-jellyfin"), service.add(movieNight(), ChangedFrom.Computer))

        assertEquals(
            SourceSummary(
                id = "movie-night-jellyfin",
                name = "Movie Night Jellyfin",
                kind = "jellyfin",
                enabled = true,
                position = 2,
                testState = TestState.Passing,
                hasLogin = true,
            ),
            service().list().last(),
        )
        assertEquals(listOf("internet-archive", "movie-night-jellyfin"), service().sources().map { it.id })
        assertTrue(RecordedJellyfin.ACCESS_TOKEN in storedText())
        assertFalse(jellyfin.password in storedText(), "The password was saved")
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.Added, "Movie Night Jellyfin", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `a wrong password is saved as a failing Source without a login, and says login needed`() = runTest {
        val outcome = service().add(movieNight(password = "hunter2"), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Failed("movie-night-jellyfin", Reason.AuthRequired, "Movie Night Jellyfin turned down the username or password."), outcome)
        val saved = service().list().last()
        assertEquals(Triple("movie-night-jellyfin", TestState.Failing, false), Triple(saved.id, saved.testState, saved.hasLogin))
        assertFalse("hunter2" in storedText(), "The password was saved")
    }

    @Test
    fun `a server that can't be reached is saved as failing, and says unreachable`() = runTest {
        val gone = jellyfin.url
        jellyfin.close()

        val outcome = service().add(movieNight(url = gone), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Failed("movie-night-jellyfin", Reason.Unreachable, "Can't reach Movie Night Jellyfin at $gone."), outcome)
        assertEquals(TestState.Failing, service().list().last().testState)
    }

    @Test
    fun `adding the same server again is harmless`() = runTest {
        val service = service()
        service.add(movieNight(), ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Added("movie-night-jellyfin"), service.add(movieNight(), ChangedFrom.Computer))

        assertEquals(2, service().list().size)
        assertEquals(emptyList(), announced)
    }

    @Test
    fun `a name already in use for another server is refused, and a name that only shares its ID gets a new ID`() = runTest {
        val service = service()
        service.add(movieNight(), ChangedFrom.Computer)

        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.NameTaken, "There's already a Source called Movie Night Jellyfin. Choose another name."),
            service.add(movieNight().copy(url = "http://192.168.1.20:8096"), ChangedFrom.Computer),
        )
        assertEquals(WriteOutcome.Added("movie-night-jellyfin-2"), service.add(movieNight().copy(name = "Movie-Night Jellyfin"), ChangedFrom.Computer))
        assertEquals(listOf("internet-archive", "movie-night-jellyfin", "movie-night-jellyfin-2"), service().list().map { it.id })
    }

    @Test
    fun `an add that can't work is refused before anything is saved or asked`() = runTest {
        val service = service()
        suspend fun refused(problem: WriteOutcome.Problem, source: NewSource) =
            assertEquals(problem, (service.add(source, ChangedFrom.Computer) as WriteOutcome.Invalid).problem, source.toString())

        refused(WriteOutcome.Problem.UnknownKind, movieNight().copy(kind = "plex"))
        refused(WriteOutcome.Problem.BlankName, movieNight().copy(name = "  "))
        refused(WriteOutcome.Problem.BadUrl, movieNight().copy(url = "192.168.1.20:8096"))
        refused(WriteOutcome.Problem.BadUrl, movieNight().copy(url = "ftp://192.168.1.20"))
        refused(WriteOutcome.Problem.BadUrl, movieNight().copy(url = null))
        refused(WriteOutcome.Problem.MissingLogin, movieNight().copy(username = " "))
        refused(WriteOutcome.Problem.MissingLogin, movieNight().copy(password = null))
        refused(WriteOutcome.Problem.UnneededSetting, NewSource(kind = "internet-archive", name = "Archive", url = jellyfin.url))

        assertEquals(1, service().list().size)
        assertEquals(emptyList(), jellyfin.requests)
    }

    @Test
    fun `renaming or enabling a server tests its connection again`() = runTest {
        val service = service()
        service.add(movieNight(), ChangedFrom.Computer)
        service.disable("movie-night-jellyfin", ChangedFrom.Computer)
        jellyfin.tokenRevoked = true

        val renamed = service.update("movie-night-jellyfin", SourceChanges(name = "Den"), ChangedFrom.Computer)
        val enabled = service.enable("movie-night-jellyfin", ChangedFrom.Computer)

        val loginNeeded = WriteOutcome.Failed("movie-night-jellyfin", Reason.AuthRequired, "Den turned down the saved login. Log in again.")
        assertEquals(loginNeeded, renamed)
        assertEquals(loginNeeded, enabled)
        assertEquals(TestState.Failing, service().list().last().testState)
    }

    @Test
    fun `logging in again with the right password fixes a failing server`() = runTest {
        val service = service()
        service.add(movieNight(password = "hunter2"), ChangedFrom.Computer)

        val outcome = service.update("movie-night-jellyfin", SourceChanges(password = jellyfin.password), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Ok, outcome)
        val fixed = service().list().last()
        assertEquals(TestState.Passing to true, fixed.testState to fixed.hasLogin)
    }

    @Test
    fun `moving a server to a new address keeps its login, and a new username needs the password`() = runTest {
        val service = service()
        service.add(movieNight(url = "http://jellyfin.invalid:8096"), ChangedFrom.Computer)
        service.update("movie-night-jellyfin", SourceChanges(password = jellyfin.password, url = jellyfin.url), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Ok, service.update("movie-night-jellyfin", SourceChanges(url = "${jellyfin.url}/"), ChangedFrom.Computer))
        assertEquals(
            WriteOutcome.Problem.MissingLogin,
            (service.update("movie-night-jellyfin", SourceChanges(username = "guest"), ChangedFrom.Computer) as WriteOutcome.Invalid).problem,
        )
    }

    @Test
    fun `the agent sets the Source order, listing the Sources to go first`() = runTest {
        val service = service()
        service.add(movieNight(), ChangedFrom.Computer)
        service.add(movieNight().copy(name = "Den Emby", kind = "emby"), ChangedFrom.Computer)
        val announced = announcementsOf(service)

        assertEquals(WriteOutcome.Ok, service.setOrder(listOf("den-emby", "movie-night-jellyfin"), ChangedFrom.Computer))
        assertEquals(WriteOutcome.Ok, service.setOrder(listOf("den-emby", "movie-night-jellyfin"), ChangedFrom.Computer))

        assertEquals(listOf("den-emby", "movie-night-jellyfin", "internet-archive"), service().sources().map { it.id })
        assertEquals(listOf(1, 2, 3), service().list().map { it.position })
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.Reordered, "Den Emby", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `an order naming an unknown Source, or one Source twice, is refused`() = runTest {
        val service = service()

        assertEquals(
            WriteOutcome.Invalid(WriteOutcome.Problem.UnknownSource, "There's no Source with the ID plex."),
            service.setOrder(listOf("plex"), ChangedFrom.Computer),
        )
        assertEquals(WriteOutcome.Problem.RepeatedSource, (service.setOrder(listOf("internet-archive", "internet-archive"), ChangedFrom.Computer) as WriteOutcome.Invalid).problem)
    }

    @Test
    fun `removing a server deletes its login`() = runTest {
        val service = service()
        service.add(movieNight(), ChangedFrom.Computer)

        assertEquals(WriteOutcome.Ok, service.remove("movie-night-jellyfin", ChangedFrom.Computer))

        assertFalse(RecordedJellyfin.ACCESS_TOKEN in storedText(), "The login outlived its Source")
        assertEquals(listOf("internet-archive"), service().list().map { it.id })
    }

    /** Everything [service] announces from now on, as the on-screen notices would see it. */
    private fun TestScope.announcementsOf(service: ConfigurationService): List<ConfigurationChange> {
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }
        return announced
    }
}
