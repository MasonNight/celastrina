package dev.celastrina.core.failover

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Stream
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One playback session's Failover: it watches the player through [PlayerEvent]s, decides when the Stream playing isn't
 * working, and moves playback to the next candidate without asking the Household anything.
 *
 * Candidates are the [Lookup]'s Streams this TV can decode, in the Household's Source order and then each Source's own
 * ranking; quality isn't compared across Sources, and Streams that failed on this TV in the last 24 hours ([memory])
 * go last. Each Stream is tried at most once per session, and each that fails is remembered in [memory]. Give it every
 * newer Lookup with [lookupChanged], so Streams that arrive late join the candidates.
 *
 * When a Failover happens on a Stream list over 30 minutes old, Failover calls [askSourcesAgain], which asks the Sources
 * again in the background (see [dev.celastrina.core.source.StreamLookup.watch]) while the Streams already known are
 * still tried. It gives up when the candidates run out, after 5 failures in a row, or after 60 s without playback.
 *
 * The player adapter shows [playing] from [startAtMillis], reports what the player does with [on] (its position about
 * every second, which is also when the timing rules are checked, even while Failover waits for Sources), and carries out
 * each [FailoverStep] it gets back.
 */
class Failover(
    private var lookup: Lookup,
    chosen: Stream,
    handPicked: Boolean,
    private val decoders: Decoders,
    private val memory: FailoverMemory,
    private val clock: Clock,
    private val logger: Logger,
    private val askSourcesAgain: () -> Unit,
    startAtMillis: Long = 0,
) {
    /** The Stream the player should be showing; null while Failover is waiting for Streams or has given up. */
    var playing: Stream? = chosen
        private set

    /** Where [playing] should start, in milliseconds into the Title. */
    var startAtMillis: Long = startAtMillis
        private set

    /** The Stream that last showed its first frame this session, which Continue watching remembers; null before any has. */
    var worked: Stream? = null
        private set

    /** Where playback has got to: the last position reported by a Stream that showed its first frame. */
    var positionMillis: Long = startAtMillis
        private set

    private var onHouseholdsChoice = handPicked
    private val tried = mutableSetOf(chosen.key)
    private var stream = StreamWatch(clock.nowMillis())

    /** What failed last while Failover waits for Sources still being asked, so the notice can name it. */
    private var waitingAfter: Failed? = null

    /** Every Stream that failed this session, in order, for the give-up screen. */
    private val failures = mutableListOf<Failed>()
    private var failuresInARow = 0

    /** When a Stream was last playing properly: showing pictures, paused, or buffering after a seek. */
    private var lastPlaybackAtMillis = clock.nowMillis()

    /** When Failover last asked the Sources again, if it has. */
    private var askedAgainAtMillis: Long? = null
    private var gaveUp = false

    /** Tells Failover what the player just did. Returns what to do next, or null to carry on. */
    fun on(event: PlayerEvent): FailoverStep? {
        if (gaveUp) return null
        val now = clock.nowMillis()
        val current = playing ?: return if (event is PlayerEvent.Position) giveUpIfNoPlayback(now) else null
        if (event is PlayerEvent.Position && stream.firstFrameShown) positionMillis = event.millis
        if (event == PlayerEvent.FirstFrame) {
            failuresInARow = 0
            worked = current
        }
        val failure = stream.on(event, now)
        if (failure == null) {
            if (stream.isPlaying) lastPlaybackAtMillis = now
            return giveUpIfNoPlayback(now)
        }
        val failed = Failed(current, failure, onHouseholdsChoice)
        onHouseholdsChoice = false
        failures += failed
        failuresInARow++
        memory.demote(current)
        return next(failed)
    }

    /** Tells Failover the Lookup has moved on. Returns a step only when Failover was waiting for Streams. */
    fun lookupChanged(lookup: Lookup): FailoverStep? {
        if (gaveUp) return null
        this.lookup = lookup
        val failed = waitingAfter ?: return null
        return next(failed)
    }

    /** Moves to the first untried candidate after [failed]; null when it is still waiting for Sources, as before. */
    private fun next(failed: Failed): FailoverStep? {
        val now = clock.nowMillis()
        if (failuresInARow >= FAILURES_IN_A_ROW) return giveUp(GiveUpReason.FailuresInARow, failed)
        if (now - lastPlaybackAtMillis >= NO_PLAYBACK_FOR) return giveUp(GiveUpReason.NoPlaybackForAMinute, failed)
        askAgainIfOld(now)
        val next = candidates().firstOrNull { it.key !in tried }
        val step = when {
            next != null -> {
                tried += next.key
                playing = next
                startAtMillis = (positionMillis - RESUME_BEFORE_FAILURE).coerceAtLeast(0)
                stream = StreamWatch(now)
                waitingAfter = null
                FailoverStep.Switch(next, startAtMillis, notice(failed, FailoverNotice.Next.Trying(sourceName(next.sourceId), sameSource = next.sourceId == failed.stream.sourceId)))
            }
            !lookup.isFinished || isAskingAgain() -> {
                playing = null
                if (waitingAfter != null) return null
                waitingAfter = failed
                FailoverStep.Waiting(notice(failed, FailoverNotice.Next.WaitingForSources))
            }
            else -> return giveUp(GiveUpReason.NothingLeftToTry, failed)
        }
        logger.info(
            "Failover: Stream ${failed.stream.id} from Source ${failed.stream.sourceId} failed (${failed.reason}); " +
                when (step) {
                    is FailoverStep.Switch -> "trying Stream ${step.stream.id} from Source ${step.stream.sourceId} at ${step.startAtMillis} ms"
                    else -> "waiting for Sources still being asked"
                },
        )
        return step
    }

    /** Asks the Sources again when the Stream list Failover has is over 30 minutes old. */
    private fun askAgainIfOld(now: Long) {
        val listAskedAt = maxOf(lookup.askedAtMillis, askedAgainAtMillis ?: Long.MIN_VALUE)
        if (now - listAskedAt <= STREAM_LIST_FRESH_FOR) return
        logger.info("Failover: the Stream list is ${(now - lookup.askedAtMillis) / 60_000} min old, so the Sources are being asked again")
        askedAgainAtMillis = now
        askSourcesAgain()
    }

    /** Failover has asked the Sources again, and their answer hasn't started arriving yet. */
    private fun isAskingAgain(): Boolean = askedAgainAtMillis.let { it != null && lookup.askedAtMillis < it }

    private fun giveUpIfNoPlayback(now: Long): FailoverStep? {
        if (now - lastPlaybackAtMillis < NO_PLAYBACK_FOR) return null
        return giveUp(GiveUpReason.NoPlaybackForAMinute, failures.lastOrNull())
    }

    private fun giveUp(because: GiveUpReason, last: Failed?): FailoverStep.GaveUp {
        gaveUp = true
        playing = null
        waitingAfter = null
        val step = FailoverStep.GaveUp(because, summary())
        logger.info(
            "Failover gave up ($because) after ${failures.size} failed Streams" +
                (last?.let { "; the last, Stream ${it.stream.id} from Source ${it.stream.sourceId}, failed (${it.reason})" } ?: ""),
        )
        return step
    }

    /** What happened at each Source this session, in the Household's order. */
    private fun summary(): List<SourceSummary> {
        val candidates = candidates()
        return lookup.sources.map { sourceLookup ->
            val sourceId = sourceLookup.source.id
            val failedHere = failures.filter { it.stream.sourceId == sourceId }.map { it.reason }
            val notTried = candidates.count { it.sourceId == sourceId && it.key !in tried }
            val outcome = sourceLookup.outcome
            val state = when {
                failedHere.isNotEmpty() -> SourceSummary.State.StreamsFailed(failedHere, notTried)
                notTried > 0 -> SourceSummary.State.NotTried(notTried)
                sourceLookup.isLooking -> SourceSummary.State.StillLooking
                outcome is LookupOutcome.Failure -> SourceSummary.State.LookupFailed(outcome.reason)
                outcome is LookupOutcome.Streams -> SourceSummary.State.NothingThisTvCanDecode
                else -> SourceSummary.State.NotAvailable
            }
            SourceSummary(sourceLookup.source.name, state)
        }
    }

    private fun candidates(): List<Stream> = lookup.candidates(decoders, memory)

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

        /** Showing pictures, paused, or buffering after a seek: anything but waiting for a first frame or stalling. */
        val isPlaying: Boolean get() = firstFrameShown && stallStartedAtMillis == null
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
                is PlayerEvent.Failed -> return StreamFailure.Fault(event.fault)
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
        val STREAM_LIST_FRESH_FOR = 30.minutes.inWholeMilliseconds
        const val FAILURES_IN_A_ROW = 5
        val NO_PLAYBACK_FOR = 60.seconds.inWholeMilliseconds
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
    data class Failed(val fault: PlayerFault) : PlayerEvent
}

/** Why the player gave up on a Stream. */
enum class PlayerFault {
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

    data class Fault(val fault: PlayerFault) : StreamFailure
}

/** What the player must do now that a Stream has failed. */
sealed interface FailoverStep {

    /** Play [stream] from [startAtMillis], about 5 s before the failure point, and show [notice]. */
    data class Switch(val stream: Stream, val startAtMillis: Long, val notice: FailoverNotice) : FailoverStep

    /**
     * Nothing left to try yet, but Sources are still being asked; their Streams will come through
     * [Failover.lookupChanged]. Show [notice] until the next step.
     */
    data class Waiting(val notice: FailoverNotice) : FailoverStep

    /** Failover has stopped trying, [because] of which rule; show the error screen with what happened at each Source. */
    data class GaveUp(val because: GiveUpReason, val sources: List<SourceSummary>) : FailoverStep
}

/** Which rule made Failover give up. */
enum class GiveUpReason {
    /** Every candidate has been tried, and no Source is still being asked. */
    NothingLeftToTry,

    /** 5 Streams failed one after another without a picture in between. */
    FailuresInARow,

    /** 60 s passed without playback. */
    NoPlaybackForAMinute,
}

/** What happened at one Source in a playback session, for the error screen when Failover gives up. */
data class SourceSummary(val sourceName: String, val state: State) {
    sealed interface State {
        /** Its Streams tried this session failed, for these reasons in order; [notTried] more were never reached. */
        data class StreamsFailed(val failures: List<StreamFailure>, val notTried: Int) : State

        /** It has Streams this TV can decode, but Failover gave up before reaching them. */
        data class NotTried(val streams: Int) : State

        /** It found Streams, but none this TV can decode. */
        data object NothingThisTvCanDecode : State

        data object NotAvailable : State

        /** Its Lookup ended in a Failure. */
        data class LookupFailed(val reason: LookupOutcome.Failure.Reason) : State

        /** It hasn't answered yet. */
        data object StillLooking : State
    }
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
            }
        }
}
