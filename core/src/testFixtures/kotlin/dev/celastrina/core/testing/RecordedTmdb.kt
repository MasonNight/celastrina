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
 * A recording is picked by the whole request: its path and every query parameter but the key. The request
 * `/3/trending/movie/week?page=2&language=en-US` replays `recorded/tmdb/trending-movie-week.language=en-US.page=2.json`
 * (parameters sorted by name). So a change in any parameter the Catalogue sends, such as the TV's language or the
 * page, reaches a different recording, and a request nothing was recorded for gets TMDB's 404.
 *
 * It checks the key the way TMDB does: a v4 token in `Authorization: Bearer`, or a v3 key in
 * `api_key`. Anything else gets TMDB's 401.
 */
class RecordedTmdb(private val apiKey: String) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use(::respond) }
        start()
    }

    val settings = TmdbSettings(
        apiKey = apiKey,
        apiBaseUrl = "http://127.0.0.1:${server.address.port}/3",
    )

    private fun respond(exchange: HttpExchange) {
        val recording = recordingFor(exchange).takeIf { javaClass.classLoader.getResource(it) != null }
        when {
            recording == null -> exchange.send(404, """{"success":false,"status_code":34,"status_message":"The resource you requested could not be found."}""")
            !exchange.isAuthorised() -> exchange.send(401, """{"success":false,"status_code":7,"status_message":"Invalid API key: You must be granted a valid key."}""")
            else -> exchange.send(200, resource(recording))
        }
    }

    private fun recordingFor(exchange: HttpExchange): String {
        val path = exchange.requestURI.path.removePrefix("/3/").replace('/', '-')
        val parameters = exchange.queryParameters().filterKeys { it != "api_key" }.toSortedMap()
        return "recorded/tmdb/" + path + parameters.entries.joinToString("") { (name, value) -> ".$name=$value" } + ".json"
    }

    private fun HttpExchange.queryParameters(): Map<String, String> =
        requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
            .map { it.split('=', limit = 2) }
            .associate { URLDecoder.decode(it[0], Charsets.UTF_8) to URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }

    private fun HttpExchange.isAuthorised(): Boolean {
        val bearer = requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return bearer == apiKey || queryParameters()["api_key"] == apiKey
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
