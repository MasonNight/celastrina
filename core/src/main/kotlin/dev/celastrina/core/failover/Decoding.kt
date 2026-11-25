package dev.celastrina.core.failover

import dev.celastrina.core.port.Decoders
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.Stream

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
 * Source's own ranking. Play's automatic choice is the first of them.
 */
fun Lookup.candidates(decoders: Decoders): List<Stream> = streams.filter { it.isDecodableBy(decoders) }
