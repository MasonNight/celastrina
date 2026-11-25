package dev.celastrina.core.extension

import dev.celastrina.core.source.SourceId
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.adapter.JdkHttpClient
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
import kotlin.test.assertTrue

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

    private fun service(missingClasses: Set<String> = emptySet(), appVersion: String = "1.2.3") = ConfigurationService(
        FileStorage(File(dir, "storage")),
        JdkHttpClient(),
        appVersion = appVersion,
        projectTmdbKey = "",
        extensionLoader = JvmExtensionLoader(File(dir, "code"), missingClasses),
        logger = RecordingLogger(),
    )

    private suspend fun ConfigurationService.install(): InstallOutcome {
        importRepository(sites.repositoryUrl, ExtensionWarning.VERSION, ChangedFrom.Computer)
        return installExtension(sites.repositoryUrl, "InternetArchive", ChangedFrom.Computer)
    }

    private fun ConfigurationService.extensionSource() = sources().single { it.id.value == EXTENSION_SOURCE }

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
        assertEquals(ConfigurationService.NEEDS_NEWER_CELASTRINA, restarted.list().single { it.id.value == EXTENSION_SOURCE }.problem)
        val requestsBefore = sites.requests.size
        assertEquals(LookupOutcome.Failure(Reason.SourceError), restarted.extensionSource().lookUp(nightOfTheLivingDead))
        assertEquals(requestsBefore, sites.requests.size, "The Extension's code was run again")
    }

    @Test
    fun `after Celastrina is updated, an Extension that needed a newer one is asked again`() = runTest {
        service(missingClasses = setOf("com.lagradost.cloudstream3.utils.ExtractorApiKt")).apply {
            install()
            extensionSource().lookUp(nightOfTheLivingDead)
        }

        val updated = service(appVersion = "1.3.0")

        assertEquals(null, updated.list().single { it.id.value == EXTENSION_SOURCE }.problem)
        assertIs<LookupOutcome.Streams>(updated.extensionSource().lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `after Celastrina is updated, an Extension installed while it needed a newer one gets its Sources`() = runTest {
        service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI")).install()
        val updated = service(appVersion = "1.3.0")

        updated.recheckExtensions()

        assertEquals(listOf(EXTENSION_SOURCE, "internet-archive"), updated.list().map { it.id.value })
        assertEquals(null, updated.list().first().problem)
        assertIs<LookupOutcome.Streams>(updated.extensionSource().lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `an Extension that still needs a newer Celastrina after an update stays labelled`() = runTest {
        service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI")).install()
        val updated = service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI"), appVersion = "1.3.0")

        updated.recheckExtensions()

        assertEquals(listOf("internet-archive"), updated.list().map { it.id.value })
        assertEquals(1, updated.status().extensions)
    }

    @Test
    fun `updating an Extension installed while it needed a newer Celastrina gives it its Sources, once the update loads`() = runTest {
        service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI")).install()
        sites.newerVersion = true
        val service = service()
        service.checkForUpdates(now = true)

        val outcome = service.updateExtension("InternetArchive", ChangedFrom.Tv)

        assertEquals(InstallOutcome.Installed(listOf(EXTENSION_SOURCE).map(::SourceId)), outcome)
        assertIs<LookupOutcome.Streams>(service.extensionSource().lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `a Source whose Extension needs a newer Celastrina still says when an update is available, in Choose Stream`() = runTest {
        val old = service(missingClasses = setOf("com.lagradost.cloudstream3.utils.ExtractorApiKt"))
        old.install()
        old.extensionSource().lookUp(nightOfTheLivingDead)
        sites.newerVersion = true

        old.checkForUpdates(now = true)

        val source = service().extensionSource()
        assertTrue(source.updateAvailable)
        assertEquals(LookupOutcome.Failure(Reason.SourceError), source.lookUp(nightOfTheLivingDead))
    }

    @Test
    fun `an Extension that can't even load on this Celastrina is installed disabled, with no Sources`() = runTest {
        val service = service(missingClasses = setOf("com.lagradost.cloudstream3.MainAPI"))

        val outcome = service.install()

        assertEquals(InstallOutcome.Failed(Reason.SourceError, "Internet Archive (test): Needs a newer Celastrina."), outcome)
        assertEquals(1, service().status().extensions)
        assertEquals(listOf("internet-archive"), service().list().map { it.id.value })
    }

    private companion object {
        const val EXTENSION_SOURCE = "internetarchive-internet-archive-test"
    }
}
