package dev.celastrina.core.extension

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationChange
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.testing.JvmExtensionLoader
import dev.celastrina.core.testing.RecordedExtensionSites
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Checking Extension Repositories for newer Extensions, and the Household updating them by hand. */
class ExtensionUpdateTest {

    @TempDir
    lateinit var dir: File

    private val sites = RecordedExtensionSites()
    private val clock = VirtualClock()
    private val loader by lazy { JvmExtensionLoader(File(dir, "code")) }

    @AfterEach
    fun stopServer() = sites.close()

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

    private fun service(loader: JvmExtensionLoader = this.loader) = ConfigurationService(
        FileStorage(File(dir, "storage")),
        JdkHttpClient(),
        appVersion = "1.2.3",
        projectTmdbKey = "",
        clock = clock,
        extensionLoader = loader,
        logger = RecordingLogger(),
    )

    /** A Household that installed version 1 of the test Extension. */
    private suspend fun installed(): ConfigurationService = service().apply {
        assertIs<ImportOutcome.Imported>(importRepository(sites.repositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Tv))
        assertIs<InstallOutcome.Installed>(installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Tv))
    }

    private fun ConfigurationService.testExtension() = repositories().single().extensions.single()

    private fun repositoryReads() = sites.requests.count { it.endsWith("/repo.json") }

    @Test
    fun `with nothing newer listed, the update check finds no updates`() = runTest {
        val service = installed()

        assertEquals(0, service.checkForUpdates())
        assertFalse(service.testExtension().updateAvailable)
        assertEquals(0, service.updatesAvailable())
    }

    @Test
    fun `the update check finds a newer version and says so, but updates nothing`() = runTest {
        val service = installed()
        sites.newerVersion = true

        assertEquals(1, service.checkForUpdates())

        val restarted = service()
        assertEquals(1, restarted.updatesAvailable())
        val extension = restarted.testExtension()
        assertEquals(2, extension.version)
        assertEquals(1, extension.installedVersion)
        assertTrue(extension.updateAvailable)
        assertTrue(restarted.list().last().updateAvailable)
        assertEquals(1, loader.loaded.size, "The newer code was loaded")
        assertEquals(1, sites.requests.count { it.endsWith(".cs3") }, "The newer code was downloaded")
    }

    @Test
    fun `the update check runs at most once a day`() = runTest {
        val service = installed()
        service.checkForUpdates()
        val reads = repositoryReads()
        sites.newerVersion = true

        clock.advanceBy(23.hours)
        assertEquals(0, service().checkForUpdates())
        assertEquals(reads, repositoryReads(), "The Repository was read again within the day")

        clock.advanceBy(1.hours)
        assertEquals(1, service().checkForUpdates())
    }

    @Test
    fun `the agent can check now, whenever the last check was`() = runTest {
        val service = installed()
        service.checkForUpdates()
        sites.newerVersion = true

        assertEquals(1, service.checkForUpdates(now = true))
    }

    @Test
    fun `a Repository that can't be read during the check keeps what it listed before`() = runTest {
        val service = installed()
        sites.close()

        assertEquals(0, service.checkForUpdates())
        assertEquals(1, service.testExtension().version)
    }

    @Test
    fun `updating an Extension installs the newer code and keeps its Sources where they are`() = runTest {
        val service = installed()
        val before = service.list()
        sites.newerVersion = true
        service.checkForUpdates()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        val outcome = service.updateExtension("InternetArchive", ChangedFrom.Computer)

        assertEquals(InstallOutcome.Installed(listOf(EXTENSION_SOURCE)), outcome)
        val restarted = service()
        assertEquals(before.map { it.id to it.position }, restarted.list().map { it.id to it.position })
        assertEquals(2, restarted.testExtension().installedVersion)
        assertFalse(restarted.testExtension().updateAvailable)
        assertEquals(0, restarted.updatesAvailable())
        assertEquals(2, loader.loaded.distinct().size, "The newer code wasn't loaded")
        assertIs<LookupOutcome.Streams>(restarted.sources().single { it.id == EXTENSION_SOURCE }.lookUp(nightOfTheLivingDead))
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.ExtensionUpdated, "Internet Archive (test)", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `an update that adds a provider adds its Source at the end of the order`() = runTest {
        val service = installed()
        sites.newerVersion = true
        sites.newerProviders = listOf("Internet Archive (test)", "Archive Shorts (test)")
        service.checkForUpdates()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        val outcome = service.updateExtension("InternetArchive", ChangedFrom.Tv)

        assertEquals(InstallOutcome.Installed(listOf(EXTENSION_SOURCE, SHORTS_SOURCE)), outcome)
        val restarted = service()
        assertEquals(listOf("internet-archive", EXTENSION_SOURCE, SHORTS_SOURCE), restarted.list().map { it.id })
        assertIs<LookupOutcome.Streams>(restarted.sources().single { it.id == SHORTS_SOURCE }.lookUp(nightOfTheLivingDead))
        assertTrue(ConfigurationChange(ConfigurationChange.Action.Added, "Archive Shorts (test)", ChangedFrom.Tv) in announced)
    }

    @Test
    fun `an update that drops a provider removes its Source`() = runTest {
        val service = installed()
        sites.newerVersion = true
        sites.newerProviders = listOf("Archive Shorts (test)")
        service.checkForUpdates()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        val outcome = service.updateExtension("InternetArchive", ChangedFrom.Tv)

        assertEquals(InstallOutcome.Installed(listOf(SHORTS_SOURCE)), outcome)
        assertEquals(listOf("internet-archive", SHORTS_SOURCE), service().list().map { it.id })
        assertTrue(ConfigurationChange(ConfigurationChange.Action.Removed, "Internet Archive (test)", ChangedFrom.Tv) in announced)
    }

    @Test
    fun `the replaced code is gone after the next restart`() = runTest {
        val service = installed()
        sites.newerVersion = true
        service.checkForUpdates()
        service.updateExtension("InternetArchive", ChangedFrom.Tv)

        service()

        assertEquals(1, File(dir, "code").listFiles().orEmpty().size)
        assertEquals(1, File(dir, "storage").walk().count { it.name.endsWith(".cs3") })
    }

    @Test
    fun `updating an Extension that has no update is harmless`() = runTest {
        val service = installed()

        assertEquals(InstallOutcome.Installed(listOf(EXTENSION_SOURCE)), service.updateExtension("InternetArchive", ChangedFrom.Tv))
        assertEquals(1, loader.loaded.size)
    }

    @Test
    fun `Update all updates every Extension that has an update`() = runTest {
        val service = installed()
        sites.newerVersion = true
        service.checkForUpdates()

        val outcomes = service.updateAllExtensions(ChangedFrom.Tv)

        assertEquals(mapOf("InternetArchive" to InstallOutcome.Installed(listOf(EXTENSION_SOURCE))), outcomes)
        assertEquals(0, service.updatesAvailable())
    }

    @Test
    fun `an update whose download doesn't match its SHA-256 is refused, keeping the installed version`() = runTest {
        val service = installed()
        sites.newerVersion = true
        service.checkForUpdates()
        sites.tamperedCode = true

        val outcome = service.updateExtension("InternetArchive", ChangedFrom.Tv)

        assertEquals(WriteOutcome.Problem.HashMismatch, assertIs<InstallOutcome.Invalid>(outcome).problem)
        assertEquals(1, service().testExtension().installedVersion)
        sites.tamperedCode = false
        assertIs<LookupOutcome.Streams>(service().sources().single { it.id == EXTENSION_SOURCE }.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `updating an Extension that isn't installed is refused`() = runTest {
        val outcome = service().updateExtension("InternetArchive", ChangedFrom.Computer)

        assertEquals(WriteOutcome.Problem.UnknownExtension, assertIs<InstallOutcome.Invalid>(outcome).problem)
    }

    @Test
    fun `an Extension's Sources say when it has an update, so a failing one can say so in Choose Stream`() = runTest {
        val service = installed()
        assertFalse(service.sources().single { it.id == EXTENSION_SOURCE }.updateAvailable)
        sites.newerVersion = true

        service.checkForUpdates()

        assertTrue(service.sources().single { it.id == EXTENSION_SOURCE }.updateAvailable)
        assertFalse(service.sources().single { it.id == "internet-archive" }.updateAvailable)
    }

    private companion object {
        const val EXTENSION_SOURCE = "internetarchive-internet-archive-test"
        const val SHORTS_SOURCE = "internetarchive-archive-shorts-test"
    }
}
