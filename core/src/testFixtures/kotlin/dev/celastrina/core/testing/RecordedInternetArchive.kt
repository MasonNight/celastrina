package dev.celastrina.core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.celastrina.core.source.internetarchive.InternetArchiveSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * The HTTP fake for the Internet Archive Source Kind: one local server standing in for both the Wikidata Query
 * Service (`/sparql`) and the Archive's metadata API (`/metadata/<identifier>`), replaying recorded responses.
 *
 * A SPARQL query must be exactly the adapter's: a UNION of one clause per ID scheme it knows (TMDB, IMDb, Wikidata),
 * each written as the Query Service expects, then the Archive ID and copyright status. Anything else is answered
 * HTTP 400, as the Query Service answers a query it can't parse. It gets every clause's recording joined, as a UNION
 * does, so each clause the adapter writes is tested by the recordings for its ID. A clause with no recording matches
 * nothing. Every Archive item a test reaches must be recorded.
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
        if (!WikimediaUserAgentPolicy.allows(exchange.requestHeaders.getFirst("User-Agent"))) {
            return exchange.send(403, WikimediaUserAgentPolicy.REFUSAL, "text/html; charset=utf-8")
        }
        val query = exchange.queryParameter("query").orEmpty()
        val clauses = QUERY.matchEntire(query)?.groupValues?.get(1)?.split(" UNION ")
        val recordings = clauses?.map { clause ->
            val (scheme, id) = CLAUSES.firstNotNullOfOrNull { (scheme, pattern) -> pattern.matchEntire(clause)?.let { scheme to it.groupValues[1] } }
                ?: return exchange.send(400, "The fake can't read the clause $clause", "text/plain")
            resourceOrNull("recorded/wikidata/$scheme-$id.json") ?: resource("recorded/wikidata/no-match.json")
        } ?: return exchange.send(400, "The fake can't read the query $query", "text/plain")
        exchange.send(200, union(recordings), "application/sparql-results+json;charset=utf-8")
    }

    /** The recorded results joined into one, as a UNION of their queries answers. */
    private fun union(recordings: List<String>): String {
        val results = recordings.map { Json.parseToJsonElement(it).jsonObject }
        val bindings = results.flatMap { it.getValue("results").jsonObject.getValue("bindings").jsonArray }
        return buildJsonObject {
            put("head", results.first().getValue("head"))
            put("results", buildJsonObject { put("bindings", JsonArray(bindings)) })
        }.toString()
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
        val QUERY = Regex(
            """SELECT \?film \?archiveId \?copyrightStatus WHERE \{ (.+) \?film wdt:P724 \?archiveId \. """ +
                """OPTIONAL \{ \?film wdt:P6216 \?copyrightStatus \. \} \}""",
        )
        val CLAUSES = listOf(
            "tmdb" to Regex("""\{ \?film wdt:P4947 "(\d+)" \. \}"""),
            "imdb" to Regex("""\{ \?film wdt:P345 "(tt\d+)" \. \}"""),
            "wikidata" to Regex("""\{ VALUES \?film \{ wd:(Q\d+) \} \}"""),
        )
    }
}
