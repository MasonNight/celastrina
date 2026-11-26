package dev.celastrina.core.port

/** This TV's video decoders, asked by MIME type such as `video/hevc`, so Failover leaves out Streams it can't decode. */
fun interface Decoders {
    fun canDecode(videoMimeType: String): Boolean
}
