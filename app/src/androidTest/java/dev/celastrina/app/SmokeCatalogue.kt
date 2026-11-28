package dev.celastrina.app

import android.content.res.AssetManager
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.HomeRow
import dev.celastrina.core.catalogue.TmdbSettings
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.configuration.TmdbKeyInUse
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.source.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.test.assertIs

internal const val SMOKE_MOVIE_ID = 90000001
internal const val SMOKE_SERIES_ID = 90000002
internal const val SMOKE_MOVIE = "Smoke Test Movie"
internal const val SMOKE_SERIES = "Smoke Test Series"
internal const val SMOKE_SEASON = "Smoke Season One"
internal const val SMOKE_EPISODE = "Smoke Episode"

/**
 * Synthetic Titles keep the smoke test independent of live Catalogue changes, credentials and artwork.
 * The local Source serves the existing synthetic clip, then goes offline on demand to open Give up.
 */
internal class SmokeCatalogue(private val app: CelastrinaApplication, assets: AssetManager) : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val sourceAddress = "http://127.0.0.1:${server.localPort}"
    private val clip = assets.open("video.mp4").use { it.readBytes() }
    private val internetArchive = SourceId("internet-archive")
    private var sourceId: SourceId? = null
    private var configuredTmdbKey = false
    private var disabledInternetArchive = false
    @Volatile private var serveVideo = true

    init {
        thread(name = "smoke-source", isDaemon = true) { serve() }
    }

    fun seed() = runBlocking {
        val configuration = app.configuration
        if (configuration.status().tmdbKey == TmdbKeyInUse.None) {
            assertEquals(WriteOutcome.Ok, configuration.setTmdbKey("screen-smoke-test", ChangedFrom.Tv))
            configuredTmdbKey = true
        }
        if (configuration.sources().any { it.id == internetArchive }) {
            assertEquals(WriteOutcome.Ok, configuration.disable(internetArchive, ChangedFrom.Tv))
            disabledInternetArchive = true
        }
        val source = configuration.add(NewSource("stremio", "Smoke Test Source", "$sourceAddress/manifest.json"), ChangedFrom.Tv)
        sourceId = assertIs<WriteOutcome.Added>(source).sourceId

        // Populate the same cache the real activity reads through the Catalogue's public API.
        val catalogue = Catalogue(CatalogueAnswers(), TmdbSettings("screen-smoke-test"), Locale.getDefault(), app.catalogueCache)
        val home = HomeRow.entries.map { catalogue.row(it) }.flatMap { it.titles }
        home.forEach { catalogue.hero(it) }
        catalogue.trending()
        catalogue.movieDetails(SMOKE_MOVIE_ID)
        val series = catalogue.seriesDetails(SMOKE_SERIES_ID)
        catalogue.episodes(series, series.seasons.single())
        Unit
    }

    fun stopServingVideo() {
        serveVideo = false
    }

    override fun close() {
        server.close()
        val configuration = app.configuration
        sourceId?.let { configuration.remove(it, ChangedFrom.Tv) }
        if (disabledInternetArchive) runBlocking { configuration.enable(internetArchive, ChangedFrom.Tv) }
        if (configuredTmdbKey) configuration.clearTmdbKey(ChangedFrom.Tv)
    }

    private fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketException) {
                if (server.isClosed) return
                throw IllegalStateException("Smoke Source stopped accepting connections")
            }
            socket.use {
                it.soTimeout = 5_000
                val request = it.getInputStream().bufferedReader()
                val path = request.readLine()?.split(" ")?.getOrNull(1)
                while (!request.readLine().isNullOrEmpty()) Unit
                val (mime, body) = when {
                    path == "/manifest.json" -> "application/json" to
                        """{"id":"test.screen-smoke","version":"1.0.0","name":"Smoke Test Source","resources":["stream"],"types":["movie"],"idPrefixes":["tt99000001"]}""".encodeToByteArray()
                    path == "/stream/movie/tt99000001.json" -> "application/json" to
                        """{"streams":[{"url":"$sourceAddress/smoke.mp4","description":"Smoke video"}]}""".encodeToByteArray()
                    path == "/smoke.mp4" && serveVideo -> "video/mp4" to clip
                    else -> "text/plain" to null
                }
                val status = if (body == null) "404 Not Found" else "200 OK"
                val output = it.getOutputStream()
                output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                if (body != null) output.write(body)
            }
        }
    }

    private class CatalogueAnswers : HttpClient {
        private val movie = """{"id":$SMOKE_MOVIE_ID,"title":"$SMOKE_MOVIE","media_type":"movie","external_ids":{"imdb_id":"tt99000001"}}"""
        private val series = """{"id":$SMOKE_SERIES_ID,"name":"$SMOKE_SERIES","media_type":"tv","seasons":[{"season_number":1,"name":"$SMOKE_SEASON","episode_count":1}]}"""

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            val body = when (URI(url).path.removePrefix("/3")) {
                "/trending/movie/week" -> """{"results":[$movie]}"""
                "/trending/tv/week" -> """{"results":[$series]}"""
                "/trending/all/week" -> """{"results":[$movie,$series]}"""
                "/discover/movie", "/discover/tv" -> """{"results":[]}"""
                "/movie/$SMOKE_MOVIE_ID" -> movie
                "/tv/$SMOKE_SERIES_ID" -> series
                "/tv/$SMOKE_SERIES_ID/season/1" -> """{"episodes":[{"episode_number":1,"name":"$SMOKE_EPISODE"}]}"""
                else -> error("No smoke-test Catalogue answer for $url")
            }
            return HttpResponse(200, body.encodeToByteArray())
        }

        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse =
            error("Unexpected smoke-test Catalogue POST to $url")
    }
}
