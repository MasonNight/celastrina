package dev.celastrina.core.extension

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.JdkHttpClient
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.JvmExtensionLoader
import dev.celastrina.core.testing.RecordedExtensionSites
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** An Extension that throws, hangs or needs a newer Celastrina fails only its own Lookups. */
class ExtensionFailureTest {

    @TempDir
    lateinit var dir: File

    private val sites = RecordedExtensionSites()

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

    private fun service(missingClasses: Set<String> = emptySet()) = ConfigurationService(
        FileStorage(File(dir, "storage")),
        JdkHttpClient(),
        appVersion = "1.2.3",
        projectTmdbKey = "",
        extensionLoader = JvmExtensionLoader(File(dir, "code"), missingClasses),
        logger = RecordingLogger(),
    )

    private suspend fun ConfigurationService.install(): InstallOutcome {
        importRepository(sites.repositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Computer)
        return installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)
    }

    private fun ConfigurationService.extensionSource() = sources().single { it.id == EXTENSION_SOURCE }

    @Test
    fun `an Extension that throws answers source_error, and the next Lookup works again`() = runTest {
        val service = service()
        service.install()
        sites.archiveDown = true

        assertEquals(LookupOutcome.Failure(Reason.SourceError), service.extensionSource().lookUp(nightOfTheLivingDead))

        sites.archiveDown = false
        assertIs<LookupOutcome.Streams>(service.extensionSource().lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `an Extension that hangs is unreachable after 30 s`() = runTest {
        val service = service()
        service.install()
        sites.archiveHangs = true

        val answer = StreamLookup(listOf(service.extensionSource()), RecordingLogger()).lookUp(nightOfTheLivingDead).first()

        assertEquals(LookupOutcome.Failure(Reason.Unreachable), answer.outcome)
        assertEquals(30_000, testScheduler.currentTime)
    }

    @Test
    fun `an Extension that needs code this Celastrina lacks is disabled, labelled Needs a newer Celastrina`() = runTest {
        // An older Celastrina: its CloudStream library has no newExtractorLink, which the Extension calls for its links.
        val service = service(missingClasses = setOf("com.lagradost.cloudstream3.utils.ExtractorApiKt"))
        service.install()

        assertEquals(LookupOutcome.Failure(Reason.SourceError), service.extensionSource().lookUp(nightOfTheLivingDead))

        val restarted = service()
        assertEquals(ConfigurationService.NEEDS_NEWER_CELASTRINA, restarted.list().single { it.id == EXTENSION_SOURCE }.problem)
        assertEquals(listOf("internet-archive"), restarted.sources().map { it.id })
    }

    @Test
    fun `an Extension that can't even load on this Celastrina is installed disabled, with no Sources`() = runTest {
        val service = service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI"))

        val outcome = service.install()

        assertEquals(InstallOutcome.Failed(Reason.SourceError, "Internet Archive (test): Needs a newer Celastrina."), outcome)
        assertEquals(1, service().status().extensions)
        assertEquals(listOf("internet-archive"), service().list().map { it.id })
    }

    private companion object {
        const val EXTENSION_SOURCE = "internetarchive-internet-archive-test"
    }
}
