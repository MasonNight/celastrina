package dev.celastrina.core.failover

import dev.celastrina.core.household.WorkedStream
import dev.celastrina.core.port.Decoders
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream
import kotlin.time.Duration.Companion.seconds

/**
 * Whether this TV can decode the Stream's video. A Stream that doesn't say its codec, or names one Celastrina doesn't
 * recognise, might still play, so it counts as decodable.
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
fun Lookup.resumeChoice(decoders: Decoders, memory: FailoverMemory, lastWorked: WorkedStream?, nowMillis: Long): ResumeChoice {
    val first = candidates(decoders, memory, lastWorked).firstOrNull()
    val waitingForLastWorked = lastWorked != null && nowMillis - askedAtMillis < LAST_WORKED_WAIT.inWholeMilliseconds &&
        sources.any { it.source.id == lastWorked.sourceId && it.isLooking }
    return when {
        first != null && lastWorked?.isOf(first) == true -> ResumeChoice.Play(first)
        waitingForLastWorked -> ResumeChoice.Waiting
        first != null -> ResumeChoice.Play(first)
        isFinished -> ResumeChoice.NothingToPlay
        else -> ResumeChoice.Waiting
    }
}

/**
 * How long resuming waits for the Source of the Stream that last worked before taking Play's choice. Story 38 asks for
 * resuming to be instant: a healthy Jellyfin, Emby or add-on on the home network, or the Internet Archive, answers well
 * within it, while a slow Source (an Extension may take its whole 30 s) no longer holds the Household up.
 */
val LAST_WORKED_WAIT = 3.seconds

/** Where resuming from Continue watching has got to. */
sealed interface ResumeChoice {
    /** Sources are still being asked. */
    data object Waiting : ResumeChoice

    data class Play(val stream: Stream) : ResumeChoice

    /** Every Source has answered, and none has a Stream this TV can play. */
    data object NothingToPlay : ResumeChoice
}
