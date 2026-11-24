package dev.celastrina.core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.celastrina.core.source.internetarchive.InternetArchiveSettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * The HTTP fake for the Internet Archive Source Kind: one local server standing in for both the Wikidata Query
 * Service (`/sparql`) and the Archive's metadata API (`/metadata/<identifier>`), replaying recorded responses.
 *
 * A SPARQL query gets the recording for the first of its IDs (TMDB, then IMDb, then Wikidata) that has one, or
 * Wikidata's recorded empty result when none has. Every Archive item a test reaches must be recorded.
 */
class RecordedInternetArchive(
    /** Answer every Wikidata query with HTTP 429, as the Query Service does to a client over its limits. */
    private val wikidataRateLimited: Boolean = false,
) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/sparql") { exchange -> exchange.use(::answerSparql) }
        createContext("/metadata/") { exchange -> exchange.use(::answerMetadata) }
        start()
    }

    val settings = InternetArchiveSettings(
        wikidataSparqlUrl = "http://127.0.0.1:${server.address.port}/sparql",
        metadataBaseUrl = "http://127.0.0.1:${server.address.port}/metadata",
    )

    private fun answerSparql(exchange: HttpExchange) {
        if (wikidataRateLimited) {
            // Hand-written: the live Query Service only sends this to a client that has exceeded its limits.
            return exchange.send(429, "Too Many Requests", "text/plain")
        }
        val query = exchange.queryParameter("query").orEmpty()
        val recording = ID_SCHEMES
            .mapNotNull { (scheme, pattern) -> pattern.find(query)?.let { "recorded/wikidata/$scheme-${it.groupValues[1]}.json" } }
            .firstNotNullOfOrNull(::resourceOrNull)
            ?: resource("recorded/wikidata/no-match.json")
        exchange.send(200, recording, "application/sparql-results+json;charset=utf-8")
    }

    private fun answerMetadata(exchange: HttpExchange) {
        val identifier = URLDecoder.decode(exchange.requestURI.rawPath.removePrefix("/metadata/"), Charsets.UTF_8)
        val recording = resourceOrNull("recorded/internet-archive/$identifier.json")
            ?: return exchange.send(500, "No recording of Archive item $identifier", "text/plain")
        exchange.send(200, recording, "application/json")
    }

    private fun HttpExchange.queryParameter(name: String): String? =
        requestURI.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }

    private fun HttpExchange.send(status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private fun resourceOrNull(path: String): String? = javaClass.classLoader.getResource(path)?.readText()

    private fun resource(path: String): String = checkNotNull(resourceOrNull(path)) { "No recording at $path" }

    override fun close() = server.stop(0)

    private companion object {
        val ID_SCHEMES = listOf(
            "tmdb" to Regex("""wdt:P4947 "(\d+)""""),
            "imdb" to Regex("""wdt:P345 "(tt\d+)""""),
            "wikidata" to Regex("""wd:(Q\d+)"""),
        )
    }
}
