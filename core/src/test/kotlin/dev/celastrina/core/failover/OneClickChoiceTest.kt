package dev.celastrina.core.failover

import dev.celastrina.core.adapter.FileStorage
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.StreamLookup
import dev.celastrina.core.testing.RecordingLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One click on an episode, through a real Lookup: fake Sources that answer after their own delays, on a fake clock that
 * is the coroutine test's virtual time, so the Lookup's asked-at time and the player's "now" move together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneClickChoiceTest {

    @TempDir
    lateinit var dir: File

    private val episode = TitleReference(
        kind = Title.Kind.Series,
        tmdbId = 1399,
        imdbId = "tt0944947",
        wikidataId = null,
        title = "Smoke Series",
        originalTitle = "Smoke Series",
        alternativeTitles = emptyList(),
        year = 2011,
        episode = TitleReference.Episode(season = 1, episode = 1, absoluteEpisode = 1, airDate = null),
    )

    @Test
    fun `the first Source's Stream plays when it answers within 5 s, even though the second answered sooner`() = runTest {
        val movieNight = DelayedSource("movie-night", answersAfter = 4.seconds)
        val archive = DelayedSource("internet-archive", answersAfter = 1.seconds)
        val click = oneClick(listOf(movieNight, archive))

        click.at(2.seconds)
        assertEquals(OneClickChoice.Waiting, click.choice())

        click.at(4.seconds)
        assertEquals(OneClickChoice.Play(movieNight.stream), click.choice())
    }

    @Test
    fun `when the first Source hasn't answered after 5 s, the best Stream so far plays`() = runTest {
        val movieNight = DelayedSource("movie-night", answersAfter = 7.seconds)
        val archive = DelayedSource("internet-archive", answersAfter = 1.seconds)
        val click = oneClick(listOf(movieNight, archive))

        click.at(5.seconds - 1.milliseconds)
        assertEquals(OneClickChoice.Waiting, click.choice())

        click.at(5.seconds)
        assertEquals(OneClickChoice.Play(archive.stream), click.choice())
    }

    /** Clicks the episode now: the Sources are asked, and the player watches the Lookup as it arrives. */
    private fun TestScope.oneClick(sources: List<Source>): OneClick {
        val clock = Clock { testScheduler.currentTime }
        val click = OneClick(this, FailoverMemory(FileStorage(dir), clock))
        backgroundScope.launch {
            StreamLookup(sources, RecordingLogger(), clock).watch(episode).collect { click.lookup = it }
        }
        runCurrent()
        return click
    }

    private class OneClick(private val test: TestScope, private val memory: FailoverMemory) {
        var lookup: Lookup? = null

        /** Moves the fake clock on to [sinceClick] after the click, letting every Source that answers by then answer. */
        fun at(sinceClick: Duration) {
            test.advanceTimeBy(sinceClick.inWholeMilliseconds - test.testScheduler.currentTime)
            test.runCurrent()
        }

        fun choice(): OneClickChoice =
            lookup?.oneClickChoice(Decoders { true }, memory, lastWorked = null, nowMillis = test.testScheduler.currentTime)
                ?: OneClickChoice.Waiting
    }

    private class DelayedSource(id: String, private val answersAfter: Duration) : Source {
        override val id = SourceId(id)
        override val name = id
        override val kind = SourceKind(id, setOf(IdScheme.Imdb), setOf(Title.Kind.Series))
        val stream = Stream(sourceId = this.id, id = "s01e01", url = "https://$id.example/s01e01.mp4", delivery = Stream.Delivery.Progressive)

        override suspend fun lookUp(title: TitleReference): LookupOutcome {
            delay(answersAfter)
            return LookupOutcome.Streams(listOf(stream))
        }
    }
}
