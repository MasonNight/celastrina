package dev.celastrina.core.source.stremio

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.deliveryFromAddress
import dev.celastrina.core.source.failureReason
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.security.MessageDigest

/** The Stremio-compatible add-on Source Kind. Add-ons name Titles by IMDb ID, so a Title needs one. */
val StremioAddonKind = SourceKind(
    "Stremio-compatible add-on",
    idSchemes = setOf(IdScheme.Imdb),
    titleKinds = setOf(Title.Kind.Movie, Title.Kind.Series),
    id = "stremio",
)

/** How fetching an add-on's manifest went. */
internal sealed interface ManifestCheck {
    /** [manifest] is the manifest as the add-on sent it, to keep for its Lookups. */
    data class Fetched(val manifest: String) : ManifestCheck

    /** The manifest is fine, but declares no `stream` resource, the only one Celastrina uses. */
    data object NoStreams : ManifestCheck

    /** The add-on answered, but not with a manifest. */
    data object NotAManifest : ManifestCheck

    data class Failed(val reason: Reason) : ManifestCheck
}

/**
 * One Stremio-compatible add-on, reached through [manifestUrl] (ending `/manifest.json`). Only its `stream` resource
 * is used; `catalog` and `meta` are never asked for.
 *
 * Each Stream's quality comes from Celastrina's extension, an object under `behaviorHints.celastrina` that an add-on
 * may add to any Stream: `id` (stable within the add-on), `resolution` (picture height in pixels), `videoCodec`,
 * `container`, `audioLanguages`, `bitrate` (bits per second), `size` (bytes), `delivery` (`progressive`, `hls` or
 * `dash`) and `drm`. Without it, the resolution is read from the Stream's `name`, such as "1080p" or "4K".
 */
internal class StremioAddon(private val http: HttpClient, private val manifestUrl: String) {

    /** Fetches the manifest and checks the add-on offers Streams. */
    suspend fun check(): ManifestCheck {
        val response = try {
            http.get(manifestUrl)
        } catch (e: IOException) {
            return ManifestCheck.Failed(Reason.Unreachable)
        }
        response.failureReason()?.let { reason -> return if (response.status == 404) ManifestCheck.NotAManifest else ManifestCheck.Failed(reason) }
        val manifest = Manifest.parse(response.bodyText()) ?: return ManifestCheck.NotAManifest
        return if (manifest.streams == null) ManifestCheck.NoStreams else ManifestCheck.Fetched(response.bodyText())
    }

    /** The add-on as a Source. [manifest] is the one saved by [check]; without it, the first Lookup fetches it. */
    fun source(id: SourceId, name: String, manifest: String?): Source = AddonSource(id, name, manifest?.let(Manifest::parse))

    private inner class AddonSource(
        override val id: SourceId,
        override val name: String,
        @Volatile private var manifest: Manifest?,
    ) : Source {
        override val kind = StremioAddonKind

        override suspend fun lookUp(title: TitleReference): LookupOutcome {
            val streams = manifest?.streams ?: when (val checked = check()) {
                is ManifestCheck.Fetched -> Manifest.parse(checked.manifest)!!.also { manifest = it }.streams!!
                ManifestCheck.NoStreams -> return LookupOutcome.NotAvailable
                ManifestCheck.NotAManifest -> return LookupOutcome.Failure(Reason.SourceError)
                is ManifestCheck.Failed -> return LookupOutcome.Failure(checked.reason)
            }
            val type = if (title.kind == Title.Kind.Movie) "movie" else "series"
            val videoId = videoId(title) ?: return LookupOutcome.NotAvailable
            if (!streams.serves(type, videoId)) return LookupOutcome.NotAvailable

            val url = manifestUrl.removeSuffix(MANIFEST) + "/stream/$type/${encode(videoId)}.json"
            val response = try {
                http.get(url)
            } catch (e: IOException) {
                return LookupOutcome.Failure(Reason.Unreachable)
            }
            // A static add-on, a folder of JSON files, has no file for a Title it has nothing for.
            if (response.status == 404) return LookupOutcome.NotAvailable
            response.failureReason()?.let { return LookupOutcome.Failure(it) }
            val found = try {
                json.decodeFromString<StreamsResponse>(response.bodyText()).streams.mapNotNull { it.toStream() }
            } catch (e: SerializationException) {
                return LookupOutcome.Failure(Reason.SourceError)
            } catch (e: IllegalArgumentException) {
                return LookupOutcome.Failure(Reason.SourceError)
            }
            return if (found.isEmpty()) LookupOutcome.NotAvailable else LookupOutcome.Streams(found)
        }

        /**
         * Every variant becomes a Stream, playable or not, so that the Lookup drops and logs the ones Celastrina
         * won't play. A Stream with no locator at all is malformed and left out.
         */
        private fun AddonStream.toStream(): Stream? {
            val hints = behaviorHints?.celastrina
            val (locator, delivery) = when {
                url != null -> url to (hints?.delivery?.let(::deliveryNamed) ?: deliveryFromAddress(url))
                infoHash != null -> "magnet:?xt=urn:btih:$infoHash" to Stream.Delivery.Torrent
                nzbUrl != null -> nzbUrl to Stream.Delivery.Usenet
                externalUrl != null -> externalUrl to Stream.Delivery.ExternalApp
                // A YouTube video plays only in YouTube's own player.
                ytId != null -> "https://www.youtube.com/watch?v=$ytId" to Stream.Delivery.ExternalApp
                else -> return null
            }
            return Stream(
                sourceId = this@AddonSource.id,
                id = hints?.id ?: digestOf(locator, fileIdx),
                url = locator,
                delivery = delivery,
                drm = hints?.drm == true,
                label = (description ?: title)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: name?.trim(),
                resolution = hints?.resolution ?: resolutionIn(name),
                videoCodec = hints?.videoCodec,
                container = hints?.container,
                audioLanguages = hints?.audioLanguages.orEmpty(),
                bitrate = hints?.bitrate,
                sizeBytes = hints?.size ?: behaviorHints?.videoSize,
                headers = behaviorHints?.proxyHeaders?.request.orEmpty(),
                subtitles = subtitles.map { Stream.Subtitle(it.url, language = it.lang) },
            )
        }
    }

    /** Which Titles a manifest's `stream` resource serves: its types, and its ID prefixes when it limits them. */
    private class StreamResource(val types: List<String>, val idPrefixes: List<String>?) {
        fun serves(type: String, videoId: String) = type in types && (idPrefixes == null || idPrefixes.any { videoId.startsWith(it) })
    }

    /** What Celastrina reads of a manifest: its `stream` resource, or null when it has none. */
    private class Manifest(val streams: StreamResource?) {
        companion object {
            fun parse(text: String): Manifest? = try {
                val wire = json.decodeFromString<WireManifest>(text)
                val stream = wire.resources.firstOrNull { it.name == "stream" }
                Manifest(stream?.let { StreamResource(it.types ?: wire.types, it.idPrefixes ?: wire.idPrefixes) })
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    @Serializable
    private data class WireManifest(
        val id: String,
        val resources: List<@Serializable(ResourceSerializer::class) WireResource>,
        val types: List<String> = emptyList(),
        val idPrefixes: List<String>? = null,
    )

    /** A manifest resource, written as just its name or as an object that overrides the manifest's types and ID prefixes. */
    @Serializable
    private data class WireResource(val name: String, val types: List<String>? = null, val idPrefixes: List<String>? = null)

    private object ResourceSerializer : KSerializer<WireResource> {
        override val descriptor: SerialDescriptor = WireResource.serializer().descriptor

        override fun deserialize(decoder: Decoder): WireResource {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            return if (element is JsonPrimitive) WireResource(element.content) else decoder.json.decodeFromJsonElement(WireResource.serializer(), element)
        }

        override fun serialize(encoder: Encoder, value: WireResource) = WireResource.serializer().serialize(encoder, value)
    }

    @Serializable
    private data class StreamsResponse(val streams: List<AddonStream> = emptyList())

    @Serializable
    private data class AddonStream(
        val url: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val nzbUrl: String? = null,
        val externalUrl: String? = null,
        val ytId: String? = null,
        val name: String? = null,
        val description: String? = null,
        val title: String? = null,
        val subtitles: List<AddonSubtitle> = emptyList(),
        val behaviorHints: BehaviorHints? = null,
    )

    @Serializable
    private data class AddonSubtitle(val url: String, val lang: String? = null)

    @Serializable
    private data class BehaviorHints(
        val videoSize: Long? = null,
        val proxyHeaders: ProxyHeaders? = null,
        val celastrina: CelastrinaHints? = null,
    )

    @Serializable
    private data class ProxyHeaders(val request: Map<String, String>? = null)

    /** Celastrina's extension under `behaviorHints`. */
    @Serializable
    private data class CelastrinaHints(
        val id: String? = null,
        val resolution: Int? = null,
        val videoCodec: String? = null,
        val container: String? = null,
        val audioLanguages: List<String>? = null,
        val bitrate: Long? = null,
        val size: Long? = null,
        val delivery: String? = null,
        val drm: Boolean? = null,
    )

    companion object {
        private const val MANIFEST = "/manifest.json"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [text] as an add-on's manifest URL, `http(s)://host[:port]/…/manifest.json`, or null when it isn't one.
         * Any path before `/manifest.json`, such as an add-on's settings, is kept for every request.
         */
        fun manifestUrlOf(text: String?): String? {
            val trimmed = text?.trim() ?: return null
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
            val ok = uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty().endsWith(MANIFEST)
            return trimmed.takeIf { ok }
        }

        /** The ID add-ons know the Title or episode by: `tt0063350`, or `tt1748166:1:1` for an episode. */
        private fun videoId(title: TitleReference): String? {
            val imdbId = title.imdbId ?: return null
            val episode = title.episode ?: return imdbId.takeIf { title.kind == Title.Kind.Movie }
            return "$imdbId:${episode.season}:${episode.episode}"
        }

        private fun deliveryNamed(name: String): Stream.Delivery? = when (name.lowercase()) {
            "progressive" -> Stream.Delivery.Progressive
            "hls" -> Stream.Delivery.Hls
            "dash" -> Stream.Delivery.Dash
            else -> null
        }

        private val RESOLUTION = Regex("""(?i)\b(?:(2160|1440|1080|720|576|480|360|240)p|(4k|uhd))\b""")

        /** The picture height a Stream's name gives, such as 1080 for "Torrentio 1080p", or 2160 for "4K". */
        private fun resolutionIn(name: String?): Int? {
            val match = name?.let(RESOLUTION::find) ?: return null
            return match.groupValues[1].toIntOrNull() ?: 2160
        }

        /** A Stream ID made from where it plays from, which may hold a secret, so it is never shown as it is. */
        private fun digestOf(locator: String, fileIdx: Int?): String =
            MessageDigest.getInstance("SHA-256").digest("$locator#${fileIdx ?: ""}".encodeToByteArray())
                .take(8).joinToString("") { "%02x".format(it) }

        /** As Stremio encodes a path component: every byte but letters, digits and `-_.!~*'()` percent-encoded. */
        private fun encode(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
    }
}
