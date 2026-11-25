package dev.celastrina.core.extension

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.source.IdScheme
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.Stream
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

/**
 * One Source an installed Extension provides: one of its providers, as CloudStream calls them.
 *
 * A Title is matched only exactly: through `getLoadUrl` with its IMDb ID where the provider supports that, or else by
 * searching its title, then its original and alternative titles, keeping hits of the same kind whose name is the
 * searched title and whose year is within one of the Title's. Exactly one such hit is the match; none or several is Not
 * available. An episode is the provider's episode with the same season and number, or else the one at the Title's
 * absolute episode number.
 */
internal class ExtensionSource(
    override val id: SourceId,
    override val name: String,
    override val kind: SourceKind,
    private val extension: String,
    private val nameInExtension: String,
    private val host: ExtensionHost,
    private val providers: suspend () -> List<MainAPI>,
    /** Called when the Extension turns out to need classes or methods this Celastrina lacks. */
    private val onNeedsNewerCelastrina: () -> Unit,
    override val updateAvailable: Boolean = false,
    /** The Extension is known to need a newer Celastrina, so every Lookup fails at once, without running its code. */
    private val needsNewerCelastrina: Boolean = false,
) : Source {

    override suspend fun lookUp(title: TitleReference): LookupOutcome = if (needsNewerCelastrina) {
        LookupOutcome.Failure(LookupOutcome.Failure.Reason.SourceError)
    } else {
        lookUpInExtension(title)
    }

    private suspend fun lookUpInExtension(title: TitleReference): LookupOutcome = try {
        val provider = providers().find { it.name == nameInExtension } ?: throw ExtensionFailure(LookupOutcome.Failure.Reason.SourceError, false)
        val page = match(provider, title)
        val data = page?.let { linkData(it, title) }
        if (page == null || data == null) LookupOutcome.NotAvailable else streams(provider, page.url, data)
    } catch (e: ExtensionFailure) {
        if (e.needsNewerCelastrina) onNeedsNewerCelastrina()
        LookupOutcome.Failure(e.reason)
    }

    /** The provider's page for [title], loaded, or null when the provider has no exact match. */
    private suspend fun match(provider: MainAPI, title: TitleReference): LoadResponse? {
        if (title.imdbId != null && SyncIdName.Imdb in provider.supportedSyncNames) {
            val url = call("getLoadUrl") { provider.getLoadUrl(SyncIdName.Imdb, title.imdbId) }
            if (url != null) return call("load") { provider.load(url) }?.takeIf { kindOf(it.type) == title.kind }
        }
        val year = title.year ?: return null
        val names = (listOf(title.title, title.originalTitle) + title.alternativeTitles).distinctBy(::normalized)
        for (name in names) {
            val hits = call("search") { provider.search(name) }.orEmpty().filter { hit ->
                kindOf(hit.type) == title.kind && normalized(hit.name) == normalized(name) && hit.year?.let { abs(it - year) <= 1 } == true
            }.distinctBy { it.url }
            when (hits.size) {
                0 -> continue
                1 -> return call("load") { provider.load(hits.single().url) }?.takeIf { kindOf(it.type) == title.kind }
                else -> return null
            }
        }
        return null
    }

    /** What the provider's `loadLinks` takes for [title] on [page]: the Movie's, or the wanted episode's. */
    private fun linkData(page: LoadResponse, title: TitleReference): String? {
        val wanted = title.episode ?: return (page as? MovieLoadResponse)?.dataUrl
        val episodes = when (page) {
            is TvSeriesLoadResponse -> page.episodes
            is AnimeLoadResponse -> page.episodes.values.firstOrNull { it.isNotEmpty() }.orEmpty()
            else -> return null
        }
        val exact = episodes.find { it.season == wanted.season && it.episode == wanted.episode }
        return (exact ?: wanted.absoluteEpisode?.let { absolute -> episodes.filter { it.season != 0 }.getOrNull(absolute - 1) })?.data
    }

    private suspend fun streams(provider: MainAPI, pageUrl: String, data: String): LookupOutcome {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        // getVideoInterceptor is never asked for: Celastrina plays a Stream's URL with its headers, nothing more.
        call("loadLinks") {
            provider.loadLinks(data, isCasting = false, subtitleCallback = { synchronized(subtitles) { subtitles += it } }) { synchronized(links) { links += it } }
        }
        val sidecars = synchronized(subtitles) { subtitles.toList() }.map { Stream.Subtitle(it.url, language = it.langTag, label = it.lang) }
        val ids = mutableSetOf<String>()
        val streams = synchronized(links) { links.toList() }.map { link ->
            val base = streamId(pageUrl, data, link)
            val streamId = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { ids.add(it) }
            Stream(
                sourceId = id,
                id = streamId,
                url = link.url,
                delivery = when (link.type) {
                    ExtractorLinkType.VIDEO -> Stream.Delivery.Progressive
                    ExtractorLinkType.M3U8 -> Stream.Delivery.Hls
                    ExtractorLinkType.DASH -> Stream.Delivery.Dash
                    ExtractorLinkType.TORRENT, ExtractorLinkType.MAGNET -> Stream.Delivery.Torrent
                },
                drm = link is DrmExtractorLink,
                label = link.name,
                resolution = link.quality.takeIf { it in 100..4320 && it != UNKNOWN_QUALITY },
                headers = link.getAllHeaders(),
                subtitles = sidecars,
            )
        }
        return if (streams.isEmpty()) LookupOutcome.NotAvailable else LookupOutcome.Streams(streams)
    }

    private suspend fun <T> call(what: String, block: suspend () -> T): T = host.call(extension, what, block)

    internal companion object {
        /** CloudStream's `Qualities.Unknown`. */
        private const val UNKNOWN_QUALITY = 400

        private val SearchResponse.year: Int?
            get() = when (this) {
                is MovieSearchResponse -> year
                is TvSeriesSearchResponse -> year
                is AnimeSearchResponse -> year
                else -> null
            }

        private fun kindOf(type: TvType?): Title.Kind? = when (type) {
            TvType.Movie, TvType.AnimeMovie, TvType.Documentary -> Title.Kind.Movie
            TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA, TvType.AsianDrama -> Title.Kind.Series
            else -> null
        }

        /** A title as compared: case, accents, punctuation and spacing don't count. */
        private fun normalized(title: String): String =
            Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * Stable for the same link on the same page: Extensions give no Stream IDs, and their URLs often carry tokens
         * that change on every lookup.
         */
        private fun streamId(pageUrl: String, data: String, link: ExtractorLink): String =
            MessageDigest.getInstance("SHA-256")
                .digest(listOf(pageUrl, data, link.source, link.name, link.quality.toString()).joinToString("\u0000").encodeToByteArray())
                .take(8).joinToString("") { "%02x".format(it) }

        /** The Source Kind of an Extension provider that plays [titleKinds], matching by IMDb ID when [imdb]. */
        fun kind(titleKinds: Set<Title.Kind>, imdb: Boolean) = SourceKind(
            name = "Extension",
            idSchemes = setOfNotNull(IdScheme.Name, IdScheme.Imdb.takeIf { imdb }),
            titleKinds = titleKinds,
            lookupTimeout = ExtensionHost.CALL_TIMEOUT,
        )

        /** What Celastrina keeps of [provider], to make it a Source. */
        fun provided(provider: MainAPI) = ProvidedSource(
            name = provider.name,
            titleKinds = provider.supportedTypes.mapNotNull(::kindOf).toSet(),
            imdb = SyncIdName.Imdb in provider.supportedSyncNames,
        )
    }
}
