package dev.celastrina.core.failover

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.Stream
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One playback session's Failover: it watches the player through [PlayerEvent]s, decides when the Stream playing isn't
 * working, and moves playback to the next candidate without asking the Household anything.
 *
 * Candidates are the [Lookup]'s Streams this TV can decode, in the Household's Source order and then each Source's own
 * ranking; quality isn't compared across Sources, and Streams that failed on this TV in the last 24 hours ([memory])
 * go last. Each Stream is tried at most once per session. HTTP 429 and 503 skip the Stream's original and resolved hosts for this session.
 * Permanent failures are remembered in [memory]. Give it every newer Lookup with [lookupChanged], so Streams that
 * arrive late join the candidates.
 *
 * After a permanent failure on a Stream list over 30 minutes old, Failover calls [askSourcesAgain], which asks the Sources
 * again in the background (see [dev.celastrina.core.source.StreamLookup.watch]) while the Streams already known are
 * still tried. It gives up when the candidates run out or the time without playback reaches [NO_PLAYBACK_LIMIT].
 *
 * The player adapter checks [addressCheck] when present, otherwise shows [playing] from [startAtMillis].
 * It reports what the player does with [on] (its position about
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
    /** The current Stream; null while Failover is waiting for Streams or has given up. */
    var playing: Stream? = chosen
        private set

    /** The Stream the adapter must check before starting playback, if any. */
    var addressCheck: Stream? = chosen.takeIf { it.delivery == Stream.Delivery.Unknown }
        private set

    /** The upcoming unknown Candidates the adapter may check while [playing] loads. */
    val backgroundAddressChecks: List<Stream>
        get() {
            if (playing == null || stream.firstFrameShown) return emptyList()
            return candidates().asSequence()
                .filter { it.delivery == Stream.Delivery.Unknown && canTry(it) && it !in checkedCandidates }
                .distinctBy { it.key }
                .take(BACKGROUND_ADDRESS_CHECK_LIMIT)
                .toList()
        }

    /** Where [playing] should start, in milliseconds into the Title. */
    var startAtMillis: Long = startAtMillis
        private set

    /** The Stream that last showed its first frame this session, which Continue watching remembers; null before any has. */
    var worked: Stream? = null
        private set

    /** Where playback has got to: the last position reported by a Stream that showed its first frame. */
    var positionMillis: Long = startAtMillis
        private set

    /** The notice currently shown during Failover, if any. */
    var notice: FailoverNotice? = null
        private set

    private var currentCandidate = chosen
    private var onHouseholdsChoice = handPicked
    private val tried = mutableSetOf(chosen.key)
    private val skippedHosts = mutableSetOf<String>()
    private var chosenAtMillis = clock.nowMillis()
    private var stream = StreamWatch(chosenAtMillis)

    /** What failed last while Failover waits for Sources still being asked, so the notice can name it. */
    private var waitingAfter: Failed? = null

    /** Every Stream that failed this session, in order, for the give-up screen. */
    private val failures = mutableListOf<Failed>()

    /** When a Stream was last playing properly: showing pictures, paused, or buffering after a seek. */
    private var lastPlaybackAtMillis = clock.nowMillis()

    /** When Failover last asked the Sources again, if it has. */
    private var askedAgainAtMillis: Long? = null
    private var gaveUp = false
    private val checkedCandidates = mutableMapOf<Stream, Stream>()

    /** Tells Failover what the player just did. Returns what to do next, or null to carry on. */
    fun on(event: PlayerEvent): FailoverStep? {
        if (gaveUp) return null
        val now = clock.nowMillis()
        return when (event) {
            is PlayerEvent.CandidateAddressChecked -> if (event.candidate == addressCheck) {
                onCurrent(event.answer, now)
            } else {
                onBackgroundAddressCheck(event, now)
            }
            else -> onCurrent(event, now)
        }
    }

    private fun onCurrent(event: PlayerEvent, now: Long): FailoverStep? {
        val current = playing ?: return if (event is PlayerEvent.Position) giveUpIfNoPlayback(now) else null
        if (addressCheck != null) {
            giveUpIfNoPlayback(now)?.let { return it }
            val answer = if (now - chosenAtMillis >= ADDRESS_CHECK_LIMIT.inWholeMilliseconds) {
                PlayerEvent.AddressCheckTimedOut
            } else {
                event as? PlayerEvent.AddressCheckAnswer ?: return null
            }
            return when (val result = answer.translate(current)) {
                is CheckedAddress.Ready -> playChecked(result.stream, now)
                is CheckedAddress.Failed -> fail(result.stream, result.reason)
            }
        }
        if (event is PlayerEvent.AddressCheckAnswer) return null
        if (event is PlayerEvent.Position && stream.firstFrameShown) positionMillis = event.millis
        if (event == PlayerEvent.FirstFrame) {
            worked = current
            notice = null
        }
        val failure = stream.on(event, now)
        if (failure == null) {
            if (stream.isPlaying) lastPlaybackAtMillis = now
            return giveUpIfNoPlayback(now)
        }
        return fail(current, failure)
    }

    private fun onBackgroundAddressCheck(event: PlayerEvent.CandidateAddressChecked, now: Long): FailoverStep? {
        val candidate = event.candidate
        if (candidate !in backgroundAddressChecks) return null
        giveUpIfNoPlayback(now)?.let { return it }
        when (val result = event.answer.translate(candidate)) {
            is CheckedAddress.Ready -> {
                checkedCandidates[candidate] = result.stream
                if (event.answer == PlayerEvent.AddressCheckUnavailable) {
                    logger.info("Failover: Background Address check unavailable")
                } else {
                    logger.info("Failover: Background Address check settled delivery as ${result.stream.delivery}")
                }
            }
            is CheckedAddress.Failed -> {
                tried += candidate.key
                val failed = Failed(result.stream, result.reason, handPicked = false)
                recordFailure(failed, "Background Address check", candidate.host)
                if (!failed.isPassing) askAgainIfOld(now)
            }
        }
        return null
    }

    private fun PlayerEvent.AddressCheckAnswer.translate(candidate: Stream): CheckedAddress = when (this) {
        is PlayerEvent.AddressChecked -> CheckedAddress.Ready(candidate.copy(url = url, delivery = delivery))
        is PlayerEvent.AddressCheckHttpError -> CheckedAddress.Failed(
            candidate.copy(url = url ?: candidate.url),
            StreamFailure.Fault(PlayerFault.Http(status)),
        )
        PlayerEvent.AddressCheckTimedOut -> CheckedAddress.Failed(candidate, StreamFailure.AddressCheckTimeout)
        PlayerEvent.AddressCheckUnavailable -> CheckedAddress.Ready(candidate)
    }

    private sealed interface CheckedAddress {
        data class Ready(val stream: Stream) : CheckedAddress
        data class Failed(val stream: Stream, val reason: StreamFailure) : CheckedAddress
    }

    private fun fail(current: Stream, failure: StreamFailure): FailoverStep? {
        val failed = Failed(current, failure, onHouseholdsChoice)
        onHouseholdsChoice = false
        recordFailure(failed, "Stream", currentCandidate.host)
        return next(failed)
    }

    private fun recordFailure(failed: Failed, subject: String, originalHost: String? = null) {
        failures += failed
        val classification = if (failed.isPassing) "passing" else "permanent"
        val detail = failed.httpStatus?.let { "HTTP $it" } ?: "HTTP status unavailable, ${failed.reason}"
        logger.info("Failover: $subject failed ($classification, $detail)")
        if (failed.skipsHost) {
            failed.stream.host?.let { skippedHosts += it }
            originalHost?.let { skippedHosts += it }
        }
        if (!failed.isPassing) memory.demote(failed.stream)
    }

    private fun playChecked(checked: Stream, now: Long): FailoverStep.Play {
        addressCheck = null
        playing = checked
        stream = StreamWatch(now)
        return FailoverStep.Play(checked, startAtMillis)
    }

    /** Tells Failover the Lookup has moved on. Returns a step only when Failover was waiting for Streams. */
    fun lookupChanged(lookup: Lookup): FailoverStep? {
        if (gaveUp) return null
        this.lookup = lookup
        waitingAfter?.let { return next(it) }
        val currentNotice = notice ?: return null
        val progress = currentNotice.next as? FailoverNotice.Next.Trying ?: return null
        notice = currentNotice.copy(next = progress.copy(known = knownCandidateCount(candidates())))
        return null
    }

    /** Moves to the first untried candidate after [failed]; null when it is still waiting for Sources, as before. */
    private fun next(failed: Failed): FailoverStep? {
        val now = clock.nowMillis()
        if (now - lastPlaybackAtMillis >= NO_PLAYBACK_LIMIT.inWholeMilliseconds) return giveUp(GiveUpReason.NoPlaybackTimeLimit)
        if (!failed.isPassing) askAgainIfOld(now)
        val candidates = candidates()
        val next = candidates.firstOrNull(::canTry)
        return when {
            next != null -> {
                tried += next.key
                val checked = checkedCandidates[next]
                val toPlay = checked ?: next
                currentCandidate = next
                playing = toPlay
                addressCheck = next.takeIf { checked == null && it.delivery == Stream.Delivery.Unknown }
                chosenAtMillis = now
                startAtMillis = (positionMillis - RESUME_BEFORE_FAILURE).coerceAtLeast(0)
                stream = StreamWatch(now)
                waitingAfter = null
                val progress = FailoverNotice.Next.Trying(sourceName(next.sourceId), tried.size, knownCandidateCount(candidates))
                FailoverStep.Switch(toPlay, startAtMillis, showNotice(failed, progress))
            }
            !lookup.isFinished || isAskingAgain() -> {
                playing = null
                addressCheck = null
                if (waitingAfter != null) return null
                waitingAfter = failed
                FailoverStep.Waiting(showNotice(failed, FailoverNotice.Next.WaitingForSources))
            }
            else -> giveUp(GiveUpReason.NothingLeftToTry)
        }
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
        if (now - lastPlaybackAtMillis < NO_PLAYBACK_LIMIT.inWholeMilliseconds) return null
        return giveUp(GiveUpReason.NoPlaybackTimeLimit)
    }

    private fun giveUp(because: GiveUpReason): FailoverStep.GaveUp {
        gaveUp = true
        notice = null
        playing = null
        addressCheck = null
        waitingAfter = null
        logger.info("Failover gave up ($because) after ${failures.size} failed Streams")
        return FailoverStep.GaveUp(because, summary())
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

    private fun canTry(candidate: Stream) = candidate.key !in tried && candidate.host !in skippedHosts &&
        checkedCandidates[candidate]?.host !in skippedHosts

    private fun knownCandidateCount(candidates: List<Stream>): Int = (tried + candidates.map { it.key }).size

    private fun showNotice(failed: Failed, next: FailoverNotice.Next) = FailoverNotice(
        failedSourceName = sourceName(failed.stream.sourceId),
        reason = failed.reason,
        awayFromHouseholdsChoice = failed.handPicked,
        next = next,
    ).also { notice = it }

    private fun sourceName(sourceId: SourceId) = lookup.sources.firstOrNull { it.source.id == sourceId }?.source?.name ?: sourceId.value

    private class Failed(val stream: Stream, val reason: StreamFailure, val handPicked: Boolean) {
        val httpStatus = ((reason as? StreamFailure.Fault)?.fault as? PlayerFault.Http)?.status
        val skipsHost = httpStatus == 429 || httpStatus == 503
        val isPassing = skipsHost || reason == StreamFailure.AddressCheckTimeout
    }

    /** The rules for when one Stream counts as not working, from the moment it was ready to play. */
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
                is PlayerEvent.AddressCheckAnswer, is PlayerEvent.CandidateAddressChecked -> Unit
            }
            if (!firstFrameShown && now - chosenAtMillis >= FIRST_FRAME_WITHIN.inWholeMilliseconds) return StreamFailure.NoFirstFrame
            val stallStarted = stallStartedAtMillis
            if (stallStarted != null && now - stallStarted > LONGEST_STALL.inWholeMilliseconds) return StreamFailure.LongStall
            return null
        }
    }

    companion object {
        val ADDRESS_CHECK_LIMIT = 10.seconds
        private const val BACKGROUND_ADDRESS_CHECK_LIMIT = 2
        private val FIRST_FRAME_WITHIN = 15.seconds
        val NO_PLAYBACK_LIMIT = 90.seconds
        private val LONGEST_STALL = 20.seconds
        private const val STALLS_IN_WINDOW = 3
        private val STALL_WINDOW = 2.minutes
        private val RESUME_BEFORE_FAILURE = 5.seconds.inWholeMilliseconds
        private val STREAM_LIST_FRESH_FOR = 30.minutes.inWholeMilliseconds
    }
}

private val Stream.key get() = sourceId to id

private val Stream.host: String?
    get() = try {
        URI(url).host?.lowercase()
    } catch (e: URISyntaxException) {
        null
    }

/** What the player adapter reports. Media3 has already retried loading before it reports [Failed]. */
sealed interface PlayerEvent {
    /** The adapter answered an Address check for this Candidate, which may now be the current Stream. */
    data class CandidateAddressChecked(val candidate: Stream, val answer: AddressCheckAnswer) : PlayerEvent

    sealed interface AddressCheckAnswer : PlayerEvent

    /** The Address check settled the Stream's final address and delivery. */
    data class AddressChecked(val url: String, val delivery: Stream.Delivery) : AddressCheckAnswer

    /** The Address check received an HTTP error at [url], if the final address is known. */
    data class AddressCheckHttpError(val status: Int, val url: String? = null) : AddressCheckAnswer

    /** The server took too long to answer the Address check. */
    data object AddressCheckTimedOut : AddressCheckAnswer

    /** The Address check could not settle the Stream, so let the player try it as offered. */
    data object AddressCheckUnavailable : AddressCheckAnswer

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
sealed interface PlayerFault {
    /** An HTTP error. [status] is null when the player cannot report the response code. */
    data class Http(val status: Int? = null) : PlayerFault

    /** The connection failed or timed out. */
    data object Network : PlayerFault

    /** A container, format or codec the player can't handle. */
    data object UnsupportedFormat : PlayerFault

    /** The decoder couldn't start or failed while decoding. */
    data object Decoder : PlayerFault
    data object Other : PlayerFault
}

/** Why Failover counted a Stream as not working. */
sealed interface StreamFailure {
    /** The Address check did not finish within 10 s. */
    data object AddressCheckTimeout : StreamFailure

    /** No first frame within 15 s of the Stream being ready to play. */
    data object NoFirstFrame : StreamFailure

    /** A stall over 20 s. */
    data object LongStall : StreamFailure

    /** 3 stalls within 2 minutes. */
    data object RepeatedStalls : StreamFailure

    data class Fault(val fault: PlayerFault) : StreamFailure
}

/** What the player must do now that a Stream has failed. */
sealed interface FailoverStep {

    /** Switch to [stream], checking its address if requested, and show [notice]. */
    data class Switch(val stream: Stream, val startAtMillis: Long, val notice: FailoverNotice) : FailoverStep

    /** The Address check finished and the player can start [stream] from [startAtMillis]. */
    data class Play(val stream: Stream, val startAtMillis: Long) : FailoverStep

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
    /** No eligible candidate remains, and no Source is still being asked. */
    NothingLeftToTry,

    /** The time without playback reached [Failover.NO_PLAYBACK_LIMIT]. */
    NoPlaybackTimeLimit,
}

/** What happened at one Source in a playback session, for the error screen when Failover gives up. */
data class SourceSummary(val sourceName: String, val state: State) {
    sealed interface State {
        /** Its Streams tried this session failed, for these reasons in order; [notTried] more were never reached. */
        data class StreamsFailed(val failures: List<StreamFailure>, val notTried: Int) : State

        /** It has Streams this TV can decode that Failover didn't try. */
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

/** The running notice for the Stream being tried or while Failover is waiting for Sources. */
data class FailoverNotice(
    val failedSourceName: String,
    val reason: StreamFailure,
    /** The Stream that failed was the one the Household picked by hand. */
    val awayFromHouseholdsChoice: Boolean,
    val next: Next,
) {
    sealed interface Next {
        data class Trying(val sourceName: String, val tried: Int, val known: Int) : Next
        data object WaitingForSources : Next
    }

    val text: String
        get() {
            val failed = if (awayFromHouseholdsChoice) "The Stream you chose from $failedSourceName" else failedSourceName
            return when (next) {
                is Next.Trying -> {
                    val progress = "${next.sourceName}: trying another Stream (${next.tried} of ${next.known})…"
                    if (awayFromHouseholdsChoice) "$failed isn't working. $progress" else progress
                }
                Next.WaitingForSources -> "$failed isn't working, waiting for your other Sources…"
            }
        }
}
