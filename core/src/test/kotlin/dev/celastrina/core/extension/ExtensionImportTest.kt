package dev.celastrina.core.extension

import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationChange
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.configuration.SourceSummary
import dev.celastrina.core.configuration.TestState
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The agent importing an Extension Repository and installing the test Extension from it. */
class ExtensionImportTest {

    @TempDir
    lateinit var dir: File

    private val sites = RecordedExtensionSites()
    private val clock = VirtualClock()

    @AfterEach
    fun stopServer() = sites.close()

    private fun service() = ConfigurationService(
        FileStorage(File(dir, "storage")),
        JdkHttpClient(),
        appVersion = "1.2.3",
        projectTmdbKey = "",
        clock = clock,
        extensionLoader = JvmExtensionLoader(File(dir, "code")),
        logger = RecordingLogger(),
    )

    private suspend fun ConfigurationService.imported(url: String = sites.repositoryUrl) =
        assertIs<ImportOutcome.Imported>(importRepository(url, ExtensionWarning.VERSION, ChangedFrom.Computer))

    @Test
    fun `importing without acknowledging the warning is refused with the warning and its version, and fetches nothing`() = runTest {
        val outcome = service().importRepository(sites.repositoryUrl, warningAcknowledged = null, ChangedFrom.Computer)

        assertEquals(ImportOutcome.WarningNotAcknowledged(ExtensionWarning.TEXT, ExtensionWarning.VERSION), outcome)
        assertTrue("other Sources' logins" in ExtensionWarning.TEXT && "home network" in ExtensionWarning.TEXT)
        assertEquals(emptyList(), sites.requests)
        assertEquals(null, service().repository(sites.repositoryUrl))
    }

    @Test
    fun `acknowledging an older version of the warning is refused too`() = runTest {
        val outcome = service().importRepository(sites.repositoryUrl, warningAcknowledged = ExtensionWarning.VERSION - 1, ChangedFrom.Computer)

        assertIs<ImportOutcome.WarningNotAcknowledged>(outcome)
        assertEquals(emptyList(), sites.requests)
    }

    @Test
    fun `importing with the warning acknowledged lists the Repository's Extensions, none installed`() = runTest {
        val service = service()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        val imported = service.imported()

        assertEquals(
            ExtensionRepository(
                url = sites.repositoryUrl,
                name = "Celastrina test Extensions",
                extensions = listOf(
                    AvailableExtension(
                        name = "InternetArchive",
                        displayName = "Internet Archive (test)",
                        version = 1,
                        description = "Public-domain and CC-licensed films from the Internet Archive.",
                        authors = listOf("Celastrina"),
                        languages = listOf("en"),
                        installed = false,
                    ),
                ),
            ),
            imported.repository,
        )
        assertEquals(imported.repository, service().repository(sites.repositoryUrl), "The import wasn't saved")
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.RepositoryImported, "Celastrina test Extensions", ChangedFrom.Computer)), announced)
        assertEquals(0, service().status().extensions)
    }

    @Test
    fun `importing the same Repository again is harmless`() = runTest {
        val service = service()
        val first = service.imported()

        assertEquals(first, service.imported())
    }

    @Test
    fun `a Repository address that isn't http or https is refused`() = runTest {
        val outcome = service().importRepository("file:///sdcard/repo.json", ExtensionWarning.VERSION, ChangedFrom.Computer)

        assertEquals(WriteOutcome.Problem.BadUrl, assertIs<ImportOutcome.Invalid>(outcome).problem)
    }

    @Test
    fun `a Repository that can't be read is a typed failure`() = runTest {
        val missing = service().importRepository(sites.repositoryUrl.replace("repo.json", "missing.json"), ExtensionWarning.VERSION, ChangedFrom.Computer)
        val unreachable = service().importRepository("http://127.0.0.1:1/repo.json", ExtensionWarning.VERSION, ChangedFrom.Computer)

        assertEquals(Reason.SourceError, assertIs<ImportOutcome.Failed>(missing).reason)
        assertEquals(Reason.Unreachable, assertIs<ImportOutcome.Failed>(unreachable).reason)
    }

    @Test
    fun `installing an Extension makes each provider a Source at the end of the order, enabled`() = runTest {
        val service = service()
        service.imported()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        val outcome = service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)

        assertEquals(InstallOutcome.Installed(listOf("internetarchive-internet-archive-test")), outcome)
        val restarted = service()
        assertEquals(
            SourceSummary(
                id = "internetarchive-internet-archive-test",
                name = "Internet Archive (test)",
                kind = "extension",
                enabled = true,
                position = 2,
                testState = TestState.Untested,
                hasLogin = false,
                addedFromComputerAt = clock.nowMillis(),
                changedFromComputerAt = null,
            ),
            restarted.list().last(),
        )
        assertEquals(listOf("internet-archive", "internetarchive-internet-archive-test"), restarted.sources().map { it.id })
        assertEquals(1, restarted.status().extensions)
        assertTrue(restarted.repository(sites.repositoryUrl)!!.extensions.single().installed)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.Added, "Internet Archive (test)", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `installing the same Extension again is harmless`() = runTest {
        val service = service()
        service.imported()
        val first = service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)

        assertEquals(first, service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer))
        assertEquals(2, service.list().size)
    }

    @Test
    fun `a download that doesn't match the list's SHA-256 isn't installed`() = runTest {
        val service = service()
        service.imported()
        sites.tamperedCode = true

        val outcome = service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)

        assertEquals(WriteOutcome.Problem.HashMismatch, assertIs<InstallOutcome.Invalid>(outcome).problem)
        assertEquals(listOf("internet-archive"), service().list().map { it.id })
        assertEquals(0, service().status().extensions)
    }

    @Test
    fun `an Extension with the same name from another Repository is refused`() = runTest {
        val service = service()
        service.imported()
        service.imported(sites.otherRepositoryUrl)
        service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)

        val outcome = service.installExtension(sites.otherRepositoryUrl, "InternetArchive", ChangedFrom.Computer)

        val refused = assertIs<InstallOutcome.Invalid>(outcome)
        assertEquals(WriteOutcome.Problem.PinnedToOtherRepository, refused.problem)
        assertTrue(sites.repositoryUrl in refused.message, refused.message)
        assertEquals(2, service().list().size)
        assertTrue(sites.requests.none { it.startsWith("/other/") && it.endsWith(".cs3") }, "The other Repository's code was downloaded")
    }

    @Test
    fun `installing from a Repository that wasn't imported, or an Extension it doesn't list, is refused`() = runTest {
        val service = service()

        val unimported = service.installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)
        service.imported()
        val unlisted = service.installExtension(sites.repositoryUrl, "SomethingElse", ChangedFrom.Computer)

        assertEquals(WriteOutcome.Problem.UnknownRepository, assertIs<InstallOutcome.Invalid>(unimported).problem)
        assertEquals(WriteOutcome.Problem.UnknownExtension, assertIs<InstallOutcome.Invalid>(unlisted).problem)
    }
}
