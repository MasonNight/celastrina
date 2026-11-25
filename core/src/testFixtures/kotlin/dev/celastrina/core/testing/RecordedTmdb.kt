package dev.celastrina.core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.celastrina.core.catalogue.TmdbSettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * The HTTP fake for TMDB: a local stand-in server that replays recorded TMDB responses.
 *
 * It checks the key the way TMDB does: a v4 token in `Authorization: Bearer`, or a v3 key in
 * `api_key`. Anything else gets TMDB's 401. Unknown paths get 404.
 */
class RecordedTmdb(private val apiKey: String) : AutoCloseable {

    private val recordings = mapOf(
        "/3/trending/movie/week" to "recorded/tmdb/trending-movie-week.json",
        "/3/movie/10331" to "recorded/tmdb/movie-10331.json",
        "/3/tv/19885" to "recorded/tmdb/tv-19885.json",
        "/3/tv/19885/season/0" to "recorded/tmdb/tv-19885-season-0.json",
        "/3/tv/19885/season/2" to "recorded/tmdb/tv-19885-season-2.json",
        "/3/tv/2207" to "recorded/tmdb/tv-2207.json",
    )

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use(::respond) }
        start()
    }

    val settings = TmdbSettings(
        apiKey = apiKey,
        apiBaseUrl = "http://127.0.0.1:${server.address.port}/3",
    )

    private fun respond(exchange: HttpExchange) {
        val recording = recordings[exchange.requestURI.path]
        when {
            recording == null -> exchange.send(404, """{"success":false,"status_code":34,"status_message":"The resource you requested could not be found."}""")
            !exchange.isAuthorised() -> exchange.send(401, """{"success":false,"status_code":7,"status_message":"Invalid API key: You must be granted a valid key."}""")
            else -> exchange.send(200, resource(recording))
        }
    }

    private fun HttpExchange.isAuthorised(): Boolean {
        val bearer = requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        val queryKey = requestURI.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == "api_key" }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
        return bearer == apiKey || queryKey == apiKey
    }

    private fun HttpExchange.send(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json;charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResource(path)) { "No recording at $path" }.readText()

    override fun close() = server.stop(0)
}
