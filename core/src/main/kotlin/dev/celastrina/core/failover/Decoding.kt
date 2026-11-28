package dev.celastrina.core.failover

import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream
import kotlin.time.Duration.Companion.seconds

/**
 * Whether this TV can decode the Stream's video. A Stream that doesn't say its codec, or names one Celastrina doesn't
 * recognise, might still play, so it counts as decodable. Delivery does not affect the codec check.
 */
fun Stream.isDecodableBy(decoders: Decoders): Boolean {
    val mimeType = videoCodec?.let { VIDEO_MIME_TYPES[it.lowercase()] } ?: return true
    return decoders.canDecode(mimeType)
}

/** Codec names as Sources spell them (FFmpeg's, FourCCs, release-name tags) to Android's video MIME types. */
private val VIDEO_MIME_TYPES = mapOf(
    "video/avc" to listOf("h264", "avc", "avc1", "x264"),
    "video/hevc" to listOf("hevc", "h265", "x265", "hvc1", "hev1"),
    "video/x-vnd.on2.vp9" to listOf("vp9", "vp09"),
    "video/x-vnd.on2.vp8" to listOf("vp8"),
    "video/av01" to listOf("av1", "av01"),
    "video/mp4v-es" to listOf("mpeg4", "mp4v", "xvid", "divx"),
    "video/mpeg2" to listOf("mpeg2video", "mpeg2"),
    "video/3gpp" to listOf("h263"),
    "video/dolby-vision" to listOf("dvhe", "dvh1"),
).flatMap { (mimeType, names) -> names.map { it to mimeType } }.toMap()

/**
 * The Streams Failover tries, in order: those this TV can decode, in the Household's Source order and then each
 * Source's own ranking, with those that failed on this TV in the last 24 hours ([memory]) moved to the end. The Stream
 * that [lastWorked] for this Title or episode (from Continue watching) goes first, unless it has failed since. Play's
 * automatic choice is the first of them.
 */
fun Lookup.candidates(decoders: Decoders, memory: FailoverMemory, lastWorked: WorkedStream? = null): List<Stream> {
    val (fresh, demoted) = streams.filter { it.isDecodableBy(decoders) }.partition { !memory.isDemoted(it) }
    val (worked, others) = fresh.partition { lastWorked?.isOf(it) == true }
    return worked + others + demoted
}

/**
 * Which Stream a Continue watching card resumes on, in one press: the Stream that [lastWorked], as soon as it arrives.
 * Until its Source has answered, resuming waits for it, but for no more than [LAST_WORKED_WAIT] after the Sources were
 * asked; once it has answered without it (or the Source is gone, or the wait is over), resuming takes Play's choice,
 * the first of the [candidates]. [nowMillis] is the time now, on the clock the Lookup was asked by.
 */
fun Lookup.resumeChoice(decoders: Decoders, memory: FailoverMemory, lastWorked: WorkedStream?, nowMillis: Long): StreamChoice {
    val first = candidates(decoders, memory, lastWorked).firstOrNull()
    val waitingForLastWorked = lastWorked != null && nowMillis - askedAtMillis < LAST_WORKED_WAIT.inWholeMilliseconds &&
        sources.any { it.source.id == lastWorked.sourceId && it.isLooking }
    return when {
        first != null && lastWorked?.isOf(first) == true -> StreamChoice.Play(first)
        waitingForLastWorked -> StreamChoice.Waiting
        first != null -> StreamChoice.Play(first)
        else -> waitingOrNothingToPlay()
    }
}

/**
 * How long resuming waits for the Source of the Stream that last worked before taking Play's choice. Story 38 asks for
 * resuming to be instant: a healthy Jellyfin, Emby or add-on on the home network, or the Internet Archive, answers well
 * within it, while a slow Source (an Extension may take its whole 30 s) no longer holds the Household up.
 */
val LAST_WORKED_WAIT = 3.seconds

/**
 * Which Stream one click on an episode plays: Play's choice, the first of the [candidates]. One click plays the Stream
 * that [lastWorked] at once. Otherwise it waits while a Source still being asked could give a better first Candidate:
 * - a Source ahead of the best Candidate's in the Household's order,
 * - the Source of the Stream that [lastWorked], wherever it is in the order, or
 * - any Source, when every Candidate so far is in Failover [memory].
 *
 * It waits for no more than [ONE_CLICK_WAIT] after the Sources were asked. After that it plays the best Candidate so far,
 * or, when none has arrived yet, the first to arrive. [nowMillis] is the time now, on the clock the Lookup was asked by.
 */
fun Lookup.oneClickChoice(decoders: Decoders, memory: FailoverMemory, lastWorked: WorkedStream?, nowMillis: Long): StreamChoice {
    val first = candidates(decoders, memory, lastWorked).firstOrNull() ?: return waitingOrNothingToPlay()
    if (lastWorked?.isOf(first) == true || nowMillis - askedAtMillis >= ONE_CLICK_WAIT.inWholeMilliseconds) return StreamChoice.Play(first)
    val ahead = sources.takeWhile { it.source.id != first.sourceId }
    val looking = sources.filter { it.isLooking }
    val couldBeBetter = looking.isNotEmpty() && memory.isDemoted(first) ||
        looking.any { it in ahead || it.source.id == lastWorked?.sourceId }
    return if (couldBeBetter) StreamChoice.Waiting else StreamChoice.Play(first)
}

/**
 * The longest one click waits for a Source that could give a better first Candidate (see [oneClickChoice]) before it
 * plays the best Candidate so far.
 */
val ONE_CLICK_WAIT = 5.seconds

/** Where choosing a Stream without the Household, on resuming or on one click, has got to. */
sealed interface StreamChoice {
    /** Sources are still being asked. */
    data object Waiting : StreamChoice

    data class Play(val stream: Stream) : StreamChoice

    /** Every Source has answered, and none has a Stream this TV can play; [sources] says what happened at each. */
    data class NothingToPlay(val sources: List<SourceSummary>) : StreamChoice
}

/** With no Candidate yet: waiting while Sources are still being asked, otherwise nothing to play. */
private fun Lookup.waitingOrNothingToPlay(): StreamChoice =
    if (isFinished) StreamChoice.NothingToPlay(sources.map { SourceSummary(it.source.name, it.answerSummary()) }) else StreamChoice.Waiting
