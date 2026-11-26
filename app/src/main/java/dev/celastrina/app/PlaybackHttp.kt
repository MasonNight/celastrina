package dev.celastrina.app

import dev.celastrina.core.source.Stream

/** Keep Android's playback User-Agent unless the Source supplies one for this Stream. */
internal fun Stream.playbackHeaders(): Map<String, String> {
    if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) return headers
    val userAgent = System.getProperty("http.agent") ?: return headers
    return headers + ("User-Agent" to userAgent)
}
