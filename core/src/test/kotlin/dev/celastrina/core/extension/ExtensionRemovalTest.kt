package dev.celastrina.core.extension

import dev.celastrina.core.catalogue.NotPlayableMemory
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationChange
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.JvmExtensionLoader
import dev.celastrina.core.testing.RecordedExtensionSites
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.flow.collect
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

/** The Household or the agent removing an Extension, or a whole Extension Repository. */
class ExtensionRemovalTest {

    @TempDir
    lateinit var dir: File

    private val sites = RecordedExtensionSites()
    private val clock = VirtualClock()
    private val storage by lazy { FileStorage(File(dir, "storage")) }
    private val failoverMemory by lazy { FailoverMemory(storage, clock) }
    private val notPlayable by lazy { NotPlayableMemory(storage, clock) }

    @AfterEach
    fun stopServer() = sites.close()

    private fun service() = ConfigurationService(
        storage,
        JdkHttpClient(),
        appVersion = "1.2.3",
        projectTmdbKey = "",
        clock = clock,
        extensionLoader = JvmExtensionLoader(File(dir, "code")),
        logger = RecordingLogger(),
        failoverMemory = failoverMemory,
        notPlayable = notPlayable,
    )

    private suspend fun installed(): ConfigurationService = service().apply {
        assertIs<ImportOutcome.Imported>(importRepository(sites.repositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Tv))
        assertIs<InstallOutcome.Installed>(installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Tv))
    }

    @Test
    fun `removing an Extension takes its Sources out of the order at once`() = runTest {
        val service = installed()
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        assertEquals(WriteOutcome.Ok, service.removeExtension("InternetArchive", ChangedFrom.Computer))

        assertEquals(listOf("internet-archive"), service.list().map { it.id })
        assertEquals(listOf("internet-archive"), service.sources().map { it.id })
        assertEquals(0, service.status().extensions)
        assertFalse(service.repositories().single().extensions.single().installed)
        assertEquals(listOf(ConfigurationChange(ConfigurationChange.Action.ExtensionRemoved, "Internet Archive (test)", ChangedFrom.Computer)), announced)
    }

    @Test
    fun `removing an Extension again, or one never installed, is harmless`() = runTest {
        val service = installed()
        service.removeExtension("InternetArchive", ChangedFrom.Tv)

        assertEquals(WriteOutcome.Ok, service.removeExtension("InternetArchive", ChangedFrom.Tv))
        assertEquals(WriteOutcome.Ok, service.removeExtension("NeverInstalled", ChangedFrom.Tv))
    }

    @Test
    fun `a removed Extension's code is gone after the next restart`() = runTest {
        installed().removeExtension("InternetArchive", ChangedFrom.Tv)

        service()

        assertEquals(0, File(dir, "code").listFiles().orEmpty().size)
        assertEquals(0, File(dir, "storage").walk().count { it.name.endsWith(".cs3") })
    }

    @Test
    fun `a removed Extension's Sources are forgotten by Failover memory`() = runTest {
        val service = installed()
        val stream = Stream(sourceId = EXTENSION_SOURCE, id = "s1", url = "https://example.org/a.mp4", delivery = Stream.Delivery.Progressive)
        failoverMemory.demote(stream)

        service.removeExtension("InternetArchive", ChangedFrom.Tv)

        assertFalse(failoverMemory.isDemoted(stream))
    }

    @Test
    fun `removing an Extension forgets every Not playable Title`() = runTest {
        val service = installed()
        val nothingHere = object : Source {
            override val id = EXTENSION_SOURCE
            override val name = "Internet Archive (test)"
            override val kind = SourceKind("Extension", setOf(IdScheme.Name), setOf(Title.Kind.Movie))
            override suspend fun lookUp(title: TitleReference) = LookupOutcome.NotAvailable
        }
        val reference = TitleReference(Title.Kind.Movie, 10331, null, null, "Night of the Living Dead", "Night of the Living Dead", emptyList(), 1968)
        StreamLookup(listOf(nothingHere), RecordingLogger(), clock, notPlayable).watch(reference).collect()
        val title = Title(10331, Title.Kind.Movie, "Night of the Living Dead", 1968, posterUrl = null, category = null)
        assertTrue(notPlayable.isNotPlayable(title))

        service.removeExtension("InternetArchive", ChangedFrom.Tv)

        assertFalse(notPlayable.isNotPlayable(title))
    }

    @Test
    fun `once removed, an Extension of the same name can come from another Repository`() = runTest {
        val service = installed()
        service.removeExtension("InternetArchive", ChangedFrom.Tv)
        service.importRepository(sites.otherRepositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Tv)

        assertIs<InstallOutcome.Installed>(service.installExtension(sites.otherRepositoryUrl, "InternetArchive", ChangedFrom.Tv))
    }

    @Test
    fun `removing an Extension Repository removes it and its Extensions, with their Sources`() = runTest {
        val service = installed()
        service.importRepository(sites.otherRepositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Tv)
        val announced = mutableListOf<ConfigurationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { service.changes.toList(announced) }

        assertEquals(WriteOutcome.Ok, service.removeRepository(sites.repositoryUrl, ChangedFrom.Computer))

        val restarted = service()
        assertEquals(listOf(sites.otherRepositoryUrl), restarted.repositories().map { it.url })
        assertEquals(null, restarted.repository(sites.repositoryUrl))
        assertEquals(listOf("internet-archive"), restarted.list().map { it.id })
        assertEquals(0, restarted.status().extensions)
        assertEquals(
            listOf(
                ConfigurationChange(ConfigurationChange.Action.ExtensionRemoved, "Internet Archive (test)", ChangedFrom.Computer),
                ConfigurationChange(ConfigurationChange.Action.RepositoryRemoved, "Celastrina test Extensions", ChangedFrom.Computer),
            ),
            announced,
        )
        assertEquals(WriteOutcome.Ok, restarted.removeRepository(sites.repositoryUrl, ChangedFrom.Computer), "Removing it again wasn't harmless")
        assertTrue(File(dir, "storage").walk().none { it.name.endsWith(".cs3") })
    }

    private companion object {
        const val EXTENSION_SOURCE = "internetarchive-internet-archive-test"
    }
}
