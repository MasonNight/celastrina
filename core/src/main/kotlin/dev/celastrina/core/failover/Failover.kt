package dev.celastrina.core.failover

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One playback session's Failover: it watches the player through [PlayerEvent]s, decides when the Stream playing isn't
 * working, and moves playback to the next candidate without asking the Household anything.
 *
 * Candidates are the [Lookup]'s Streams this TV can decode, in the Household's Source order and then each Source's own
 * ranking; quality isn't compared across Sources. Each Stream is tried at most once per session. Give it every newer
 * Lookup with [lookupChanged], so Streams that arrive late join the candidates.
 *
 * The player adapter shows [playing] from [startAtMillis], reports what the player does with [on] (its position about
 * every second, which is also when the timing rules are checked), and carries out each [FailoverStep] it gets back.
 */
class Failover(
    private var lookup: Lookup,
    chosen: Stream,
    handPicked: Boolean,
    private val decoders: Decoders,
    private val clock: Clock,
    private val logger: Logger,
    startAtMillis: Long = 0,
) {
    /** The Stream the player should be showing; null while Failover is waiting for Streams or has none left. */
    var playing: Stream? = chosen
        private set

    /** Where [playing] should start, in milliseconds into the Title. */
    var startAtMillis: Long = startAtMillis
        private set

    /** Where playback has got to: the last position reported by a Stream that showed its first frame. */
    var positionMillis: Long = startAtMillis
        private set

    private var onHouseholdsChoice = handPicked
    private val tried = mutableSetOf(chosen.key)
    private var stream = StreamWatch(clock.nowMillis())

    /** What failed last while Failover waits for Sources still being asked, so the notice can name it. */
    private var waitingAfter: Failed? = null

    /** Tells Failover what the player just did. Returns what to do next, or null to carry on. */
    fun on(event: PlayerEvent): FailoverStep? {
        val current = playing ?: return null
        if (event is PlayerEvent.Position && stream.firstFrameShown) positionMillis = event.millis
        val failure = stream.on(event, clock.nowMillis()) ?: return null
        val failed = Failed(current, failure, onHouseholdsChoice)
        onHouseholdsChoice = false
        return next(failed)
    }

    /** Tells Failover the Lookup has moved on. Returns a step only when Failover was waiting for Streams. */
    fun lookupChanged(lookup: Lookup): FailoverStep? {
        this.lookup = lookup
        val failed = waitingAfter ?: return null
        return next(failed)
    }

    /** Moves to the first untried candidate after [failed]; null when it is still waiting for Sources, as before. */
    private fun next(failed: Failed): FailoverStep? {
        val next = candidates().firstOrNull { it.key !in tried }
        val step = when {
            next != null -> {
                tried += next.key
                playing = next
                startAtMillis = (positionMillis - RESUME_BEFORE_FAILURE).coerceAtLeast(0)
                stream = StreamWatch(clock.nowMillis())
                waitingAfter = null
                FailoverStep.Switch(next, startAtMillis, notice(failed, FailoverNotice.Next.Trying(sourceName(next.sourceId), sameSource = next.sourceId == failed.stream.sourceId)))
            }
            !lookup.isFinished -> {
                playing = null
                if (waitingAfter != null) return null
                waitingAfter = failed
                FailoverStep.Waiting(notice(failed, FailoverNotice.Next.WaitingForSources))
            }
            else -> {
                playing = null
                waitingAfter = null
                FailoverStep.OutOfStreams(notice(failed, FailoverNotice.Next.NothingLeft))
            }
        }
        logger.info(
            "Failover: Stream ${failed.stream.id} from Source ${failed.stream.sourceId} failed (${failed.reason}); " +
                when (step) {
                    is FailoverStep.Switch -> "trying Stream ${step.stream.id} from Source ${step.stream.sourceId} at ${step.startAtMillis} ms"
                    is FailoverStep.Waiting -> "waiting for Sources still being asked"
                    is FailoverStep.OutOfStreams -> "no other Stream to try"
                },
        )
        return step
    }

    private fun candidates(): List<Stream> = lookup.candidates(decoders)

    private fun notice(failed: Failed, next: FailoverNotice.Next) = FailoverNotice(
        failedSourceName = sourceName(failed.stream.sourceId),
        reason = failed.reason,
        awayFromHouseholdsChoice = failed.handPicked,
        next = next,
    )

    private fun sourceName(sourceId: String) = lookup.sources.firstOrNull { it.source.id == sourceId }?.source?.name ?: sourceId

    private class Failed(val stream: Stream, val reason: StreamFailure, val handPicked: Boolean)

    /** The rules for when one Stream counts as not working, from the moment it was chosen. */
    private class StreamWatch(private val chosenAtMillis: Long) {
        var firstFrameShown = false
            private set
        private var paused = false
        private var seeking = false
        private var stallStartedAtMillis: Long? = null
        private val stallStarts = ArrayDeque<Long>()

        fun on(event: PlayerEvent, now: Long): StreamFailure? {
            when (event) {
                PlayerEvent.FirstFrame -> firstFrameShown = true
                PlayerEvent.Paused -> {
                    paused = true
                    stallStartedAtMillis = null
                }
                PlayerEvent.Playing -> {
                    paused = false
                    seeking = false
                    stallStartedAtMillis = null
                }
                PlayerEvent.Seeked -> {
                    seeking = true
                    stallStartedAtMillis = null
                }
                PlayerEvent.Buffering -> if (firstFrameShown && !paused && !seeking && stallStartedAtMillis == null) {
                    stallStartedAtMillis = now
                    stallStarts.addLast(now)
                    while (now - stallStarts.first() > STALL_WINDOW.inWholeMilliseconds) stallStarts.removeFirst()
                    if (stallStarts.size >= STALLS_IN_WINDOW) return StreamFailure.RepeatedStalls
                }
                is PlayerEvent.Failed -> return StreamFailure.Error(event.error)
                is PlayerEvent.Position -> Unit
            }
            if (!firstFrameShown && now - chosenAtMillis >= FIRST_FRAME_WITHIN.inWholeMilliseconds) return StreamFailure.NoFirstFrame
            val stallStarted = stallStartedAtMillis
            if (stallStarted != null && now - stallStarted > LONGEST_STALL.inWholeMilliseconds) return StreamFailure.LongStall
            return null
        }
    }

    private companion object {
        val FIRST_FRAME_WITHIN = 15.seconds
        val LONGEST_STALL = 20.seconds
        const val STALLS_IN_WINDOW = 3
        val STALL_WINDOW = 2.minutes
        val RESUME_BEFORE_FAILURE = 5.seconds.inWholeMilliseconds
    }
}

private val Stream.key get() = sourceId to id

/** What the player adapter reports. Media3 has already retried loading before it reports [Failed]. */
sealed interface PlayerEvent {
    /** The Stream's first picture is on screen. */
    data object FirstFrame : PlayerEvent

    /** Playback is waiting for data. */
    data object Buffering : PlayerEvent

    /** Playback is moving again, after starting, buffering, a pause or a seek. */
    data object Playing : PlayerEvent

    /** The Household paused. */
    data object Paused : PlayerEvent

    /** The Household seeked; the buffering until [Playing] isn't a stall. */
    data object Seeked : PlayerEvent

    /** Where the player is, in milliseconds into the Title, reported about every second whatever it is doing. */
    data class Position(val millis: Long) : PlayerEvent

    /** The player gave up on the Stream. */
    data class Failed(val error: PlayerError) : PlayerEvent
}

/** Why the player gave up on a Stream. */
enum class PlayerError {
    /** An HTTP 4xx or 5xx. */
    Http,

    /** The connection failed or timed out. */
    Network,

    /** A container, format or codec the player can't handle. */
    UnsupportedFormat,

    /** The decoder couldn't start or failed while decoding. */
    Decoder,
    Other,
}

/** Why Failover counted a Stream as not working. */
sealed interface StreamFailure {
    /** No first frame within 15 s of the Stream being chosen. */
    data object NoFirstFrame : StreamFailure

    /** A stall over 20 s. */
    data object LongStall : StreamFailure

    /** 3 stalls within 2 minutes. */
    data object RepeatedStalls : StreamFailure

    data class Error(val error: PlayerError) : StreamFailure
}

/** What the player must do now that a Stream has failed. Each comes with a notice for the Household. */
sealed interface FailoverStep {
    val notice: FailoverNotice

    /** Play [stream] from [startAtMillis], about 5 s before the failure point. */
    data class Switch(val stream: Stream, val startAtMillis: Long, override val notice: FailoverNotice) : FailoverStep

    /** Nothing left to try yet, but Sources are still being asked; their Streams will come through [Failover.lookupChanged]. */
    data class Waiting(override val notice: FailoverNotice) : FailoverStep

    /** Every candidate has been tried. */
    data class OutOfStreams(override val notice: FailoverNotice) : FailoverStep
}

/**
 * The small notice that tells the Household what failed and what happens next, such as "Movie Night Jellyfin isn't
 * working, trying Internet Archive…". It asks nothing.
 */
data class FailoverNotice(
    val failedSourceName: String,
    val reason: StreamFailure,
    /** The Stream that failed was the one the Household picked by hand. */
    val awayFromHouseholdsChoice: Boolean,
    val next: Next,
) {
    sealed interface Next {
        data class Trying(val sourceName: String, val sameSource: Boolean) : Next
        data object WaitingForSources : Next
        data object NothingLeft : Next
    }

    val text: String
        get() {
            val failed = when {
                awayFromHouseholdsChoice -> "The Stream you chose from $failedSourceName"
                next is Next.Trying && next.sameSource -> "This Stream from $failedSourceName"
                else -> failedSourceName
            }
            return when (next) {
                is Next.Trying -> if (next.sameSource) "$failed isn't working, trying another from ${next.sourceName}…" else "$failed isn't working, trying ${next.sourceName}…"
                Next.WaitingForSources -> "$failed isn't working, waiting for your other Sources…"
                Next.NothingLeft -> "$failed isn't working, and there's no other Stream to try"
            }
        }
}
