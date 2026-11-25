package dev.celastrina.core.source

/**
 * One offering of a Title from a specific Source.
 *
 * Sources report every Stream they find; [StreamLookup] drops the ones Celastrina won't play (see [unplayableBecause])
 * before anything else sees them.
 */
data class Stream(
    val sourceId: SourceId,
    /** Stable within the Source, so Failover can remember which Streams work. */
    val id: String,
    val url: String,
    val delivery: Delivery,
    /** Encrypted with DRM. Celastrina never plays these. */
    val drm: Boolean = false,
    val label: String? = null,
    /** Height of the picture in pixels, such as 1080. */
    val resolution: Int? = null,
    val videoCodec: String? = null,
    val container: String? = null,
    val audioLanguages: List<String> = emptyList(),
    val bitrate: Long? = null,
    val sizeBytes: Long? = null,
    /** HTTP request headers playback must send, such as a token or Referer. */
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
) {
    enum class Delivery(val isPlayable: Boolean) {
        /** A single http(s) file. */
        Progressive(true),
        Hls(true),
        Dash(true),
        Torrent(false),

        /** A page to open in another app or a browser. */
        ExternalApp(false),
        Usenet(false),
    }

    /** Why Celastrina won't play this Stream, or null when it will: only unencrypted http(s), HLS and DASH play. */
    val unplayableBecause: Unplayable?
        get() = when {
            drm -> Unplayable.Drm
            !delivery.isPlayable -> Unplayable.NotPlayableDelivery(delivery)
            url.substringBefore(':').lowercase() !in setOf("http", "https") -> Unplayable.NotHttp(url.substringBefore(':'))
            else -> null
        }

    /** Why Celastrina won't play a Stream. Each says itself for the device log. */
    sealed interface Unplayable {
        data object Drm : Unplayable {
            override fun toString() = "DRM"
        }

        /** Delivered in a way Celastrina doesn't play, such as a torrent. */
        data class NotPlayableDelivery(val delivery: Delivery) : Unplayable {
            override fun toString() = delivery.name
        }

        /** Its URL's [scheme] isn't http or https. */
        data class NotHttp(val scheme: String) : Unplayable {
            override fun toString() = "not http(s): $scheme"
        }
    }

    /** A sidecar subtitle file. [language] is a BCP 47 tag where known. */
    data class Subtitle(val url: String, val language: String? = null, val label: String? = null)
}
