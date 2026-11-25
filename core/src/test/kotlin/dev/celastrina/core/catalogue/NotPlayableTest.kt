package dev.celastrina.core.catalogue

import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.configuration.SourceChanges
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.port.FileStorage
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.RecordingLogger
import dev.celastrina.core.testing.VirtualClock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class NotPlayableTest {

    @TempDir
    lateinit var dir: File

    private val clock = VirtualClock()
    private val memory by lazy { NotPlayableMemory(FileStorage(dir), clock) }

    private val nightOfTheLivingDead = Title(10331, Title.Kind.Movie, "Night of the Living Dead", 1968, posterUrl = null, category = null)
    private val plan9 = Title(10513, Title.Kind.Movie, "Plan 9 from Outer Space", 1957, posterUrl = null, category = null)

    @Test
    fun `a Title every Source answered Not available is Not playable, and Titles never looked up aren't`() = runTest {
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable, LookupOutcome.NotAvailable)

        assertTrue(memory.isNotPlayable(nightOfTheLivingDead))
        assertFalse(memory.isNotPlayable(plan9))
    }

    @Test
    fun `a Lookup where any Source failed isn't remembered, even if the rest were Not available`() = runTest {
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable, LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable))

        assertFalse(memory.isNotPlayable(nightOfTheLivingDead))
    }

    @Test
    fun `a Lookup no Source could be asked for isn't remembered`() = runTest {
        lookUp(nightOfTheLivingDead)

        assertFalse(memory.isNotPlayable(nightOfTheLivingDead))
    }

    @Test
    fun `a later Lookup that doesn't find every Source Not available forgets the Title was Not playable`() = runTest {
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)
        lookUp(plan9, LookupOutcome.NotAvailable)

        lookUp(nightOfTheLivingDead, LookupOutcome.Streams(listOf(stream())))
        lookUp(plan9, LookupOutcome.NotAvailable, LookupOutcome.Failure(LookupOutcome.Failure.Reason.RateLimited))

        assertFalse(memory.isNotPlayable(nightOfTheLivingDead))
        assertFalse(memory.isNotPlayable(plan9))
    }

    @Test
    fun `an episode's Lookup says nothing about whether its Series is playable`() = runTest {
        val sherlock = Title(19885, Title.Kind.Series, "Sherlock", 2010, posterUrl = null, category = null)
        val episode = TitleReference.Episode(season = 1, episode = 1, absoluteEpisode = 1, airDate = null)

        lookUp(referenceTo(sherlock).copy(episode = episode), LookupOutcome.NotAvailable)

        assertFalse(memory.isNotPlayable(sherlock))
    }

    @Test
    fun `a Movie and a Series with the same TMDB ID are different Titles`() = runTest {
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)

        assertFalse(memory.isNotPlayable(nightOfTheLivingDead.copy(kind = Title.Kind.Series)))
    }

    @Test
    fun `Not playable is remembered across restarts for 7 days, then forgotten`() = runTest {
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)

        clock.advanceBy(7.days - 1.minutes)
        assertTrue(NotPlayableMemory(FileStorage(dir), clock).isNotPlayable(nightOfTheLivingDead))

        clock.advanceBy(1.minutes)
        assertFalse(memory.isNotPlayable(nightOfTheLivingDead))
        assertFalse(NotPlayableMemory(FileStorage(dir), clock).isNotPlayable(nightOfTheLivingDead))
    }

    @Test
    fun `screens can tell when what is remembered has changed, to redraw their badges`() = runTest {
        val before = memory.revision.value
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)
        val recorded = memory.revision.value
        configuration().disable("internet-archive", ChangedFrom.Tv)

        assertNotEquals(before, recorded)
        assertNotEquals(recorded, memory.revision.value)
    }

    @Test
    fun `adding a Source forgets every Not playable Title, so the new Source gets a fair chance`() = runTest {
        assertSourceChangeForgets { add(NewSource(kind = "internet-archive", name = "Archive Two"), ChangedFrom.Tv) }
    }

    @Test
    fun `changing a Source forgets every Not playable Title`() = runTest {
        assertSourceChangeForgets { update("internet-archive", SourceChanges(name = "Archive.org"), ChangedFrom.Computer) }
    }

    @Test
    fun `disabling or enabling a Source forgets every Not playable Title`() = runTest {
        assertSourceChangeForgets { disable("internet-archive", ChangedFrom.Tv) }
        assertSourceChangeForgets { enable("internet-archive", ChangedFrom.Tv) }
    }

    @Test
    fun `removing a Source forgets every Not playable Title, its own results with them`() = runTest {
        assertSourceChangeForgets { remove("internet-archive", ChangedFrom.Computer) }
    }

    @Test
    fun `setting the TMDB key or reordering Sources leaves Not playable Titles remembered`() = runTest {
        val service = configuration()
        service.add(NewSource(kind = "internet-archive", name = "Archive Two"), ChangedFrom.Tv)
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)

        service.setTmdbKey("household-key", ChangedFrom.Computer)
        service.setOrder(service.list().map { it.id }.reversed(), ChangedFrom.Tv)

        assertTrue(memory.isNotPlayable(nightOfTheLivingDead))
    }

    private suspend fun assertSourceChangeForgets(change: suspend ConfigurationService.() -> WriteOutcome) {
        val service = configuration()
        lookUp(nightOfTheLivingDead, LookupOutcome.NotAvailable)
        lookUp(plan9, LookupOutcome.NotAvailable)

        val outcome = service.change()
        assertTrue(outcome == WriteOutcome.Ok || outcome is WriteOutcome.Added, "The change didn't save: $outcome")

        assertFalse(memory.isNotPlayable(nightOfTheLivingDead))
        assertFalse(memory.isNotPlayable(plan9))
        assertFalse(NotPlayableMemory(FileStorage(dir), clock).isNotPlayable(plan9))
    }

    private fun configuration() = ConfigurationService(FileStorage(dir), offline, appVersion = "1.2.3", projectTmdbKey = "", clock = clock, notPlayable = memory)

    /** Nothing here goes online: the Internet Archive has no connection test. */
    private val offline = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = error("No network in these tests: $url")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse = error("No network in these tests: $url")
    }

    private suspend fun lookUp(title: Title, vararg answers: LookupOutcome) = lookUp(referenceTo(title), *answers)

    private suspend fun lookUp(title: TitleReference, vararg answers: LookupOutcome) {
        val sources = answers.mapIndexed { i, answer -> FakeSource("source-$i", answer) }
        StreamLookup(sources, RecordingLogger(), clock, notPlayable = memory).watch(title).collect()
    }

    private fun stream() = Stream(sourceId = "source-0", id = "film", url = "https://example.org/film.mp4", delivery = Stream.Delivery.Progressive)

    private fun referenceTo(title: Title) = TitleReference(
        kind = title.kind,
        tmdbId = title.tmdbId,
        imdbId = null,
        wikidataId = null,
        title = title.name,
        originalTitle = title.name,
        alternativeTitles = emptyList(),
        year = title.year,
    )

    private class FakeSource(override val id: String, private val answer: LookupOutcome) : Source {
        override val name = id
        override val kind = SourceKind("Shelf", idSchemes = setOf(IdScheme.Tmdb), titleKinds = setOf(Title.Kind.Movie, Title.Kind.Series))
        override suspend fun lookUp(title: TitleReference) = answer
    }
}
