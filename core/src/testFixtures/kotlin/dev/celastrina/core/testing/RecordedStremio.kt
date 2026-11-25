package dev.celastrina.core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * The HTTP fake for the Stremio-compatible add-on Source Kind: one local server hosting several add-ons, each under its
 * own path, the way a configured add-on keeps its settings in the path before `/manifest.json`. Each add-on answers
 * `GET /<add-on>/manifest.json` and `GET /<add-on>/stream/<type>/<id>.json` with its recording. A stream request with
 * no recording gets GitHub Pages' recorded "Page not found", as a static add-on hosted there answers for a Title it
 * has no file for. Anything else, such as `catalog` or `meta`, gets a 404 and is noted in [requests].
 *
 * Recorded from the live add-ons: [CINEMETA], [WATCHHUB], [PUBLIC_DOMAIN_MOVIES] and [GAME_CINEMA]. [HOUSEHOLD] was
 * recorded from the official stremio-addon-sdk serving `household-addon.js` (beside the recordings), because no live
 * add-on offers http(s) Streams of freely licensed films, nor Celastrina's quality extension under `behaviorHints`.
 */
class RecordedStremio : AutoCloseable {

    @Volatile
    private var down = emptySet<String>()

    private val requested = mutableListOf<String>()

    /** The path of every request so far, oldest first. */
    val requests: List<String> get() = synchronized(requested) { requested.toList() }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use(::respond) }
        start()
    }

    private val address = "http://127.0.0.1:${server.address.port}"

    /** The manifest URL of [addon], as the Household would paste it. */
    fun manifestUrl(addon: String) = "$address/$addon/manifest.json"

    /** From now on [addon] answers every request with HTTP 503, as a sleeping host does. */
    fun takeDown(addon: String) {
        down = down + addon
    }

    /** Undoes [takeDown]. */
    fun bringBack(addon: String) {
        down = down - addon
    }

    private fun respond(exchange: HttpExchange) {
        val path = URLDecoder.decode(exchange.requestURI.rawPath, Charsets.UTF_8)
        synchronized(requested) { requested += path }
        val addon = path.removePrefix("/").substringBefore('/')
        val resource = path.removePrefix("/$addon/")
        if (addon in down) {
            // Hand-written: what any host answers while the add-on behind it is down.
            return exchange.send(503, "Service Unavailable", "text/plain")
        }
        if (exchange.requestMethod != "GET") return exchange.send(405, "", "text/plain")
        when {
            resource == "manifest.json" -> resourceOrNull("$addon-manifest.json")?.let { exchange.send(200, it, JSON) }
                ?: exchange.send(404, "", "text/plain")
            STREAM.matches(resource) -> answerStream(exchange, addon, STREAM.matchEntire(resource)!!.groupValues)
            else -> exchange.send(404, "", "text/plain")
        }
    }

    private fun answerStream(exchange: HttpExchange, addon: String, groups: List<String>) {
        val (_, type, id) = groups
        val recording = resourceOrNull("$addon-stream-$type-${id.replace(':', '-')}.json")
            ?: return exchange.send(404, resource("static-not-found.html"), "text/html; charset=utf-8")
        // The SDK answers a handler that failed with HTTP 500 and {"err":"handler error"}.
        val status = if (recording.startsWith("{\"err\"")) 500 else 200
        exchange.send(status, recording, JSON)
    }

    private fun HttpExchange.send(status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", contentType)
        responseHeaders.add("Access-Control-Allow-Origin", "*")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    private fun resourceOrNull(name: String): String? = javaClass.classLoader.getResource("recorded/stremio/$name")?.readText()

    private fun resource(name: String): String = checkNotNull(resourceOrNull(name)) { "No recording recorded/stremio/$name" }

    override fun close() = server.stop(0)

    companion object {
        /** A Household's own add-on: movies and series, `catalog`, `meta` and `stream`, with http(s) Streams. */
        const val HOUSEHOLD = "household"

        /** Stremio's own Catalogue add-on: `catalog` and `meta`, no `stream`. */
        const val CINEMETA = "cinemeta"

        /** Only `stream`, and every Stream opens another app (`externalUrl`). */
        const val WATCHHUB = "watchhub"

        /** `catalog` and `stream`, movies only, and every Stream is a torrent. */
        const val PUBLIC_DOMAIN_MOVIES = "public-domain-movies"

        /** A `stream` resource only for its own `gc_yt:` and `gc_pl:` IDs, never IMDb's. */
        const val GAME_CINEMA = "game-cinema"

        private const val JSON = "application/json; charset=utf-8"
        private val STREAM = Regex("stream/([^/]+)/(.+)\\.json")
    }
}
