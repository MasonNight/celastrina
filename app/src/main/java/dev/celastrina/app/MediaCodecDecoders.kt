package dev.celastrina.app

import android.media.MediaCodecList
import dev.celastrina.core.port.Decoders

/** This TV's video decoders, as Android lists them. Celastrina ships no decoders of its own. */
object MediaCodecDecoders : Decoders {
    private val videoMimeTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .map { it.lowercase() }
            .toSet()
    }

    override fun canDecode(videoMimeType: String): Boolean = videoMimeType.lowercase() in videoMimeTypes
}
