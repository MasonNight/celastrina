package dev.celastrina.core.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * The HTTP fake for the Jellyfin and Emby Source Kinds: a local server replaying responses recorded from the public
 * Jellyfin demo (demo.jellyfin.org/stable, the `demo` account), with its access token and the recorder's address
 * replaced.
 *
 * It checks credentials itself: [username] with [password] logs in and gets the recorded token, anything else gets the
 * demo's recorded 401. Every other request needs that token in a `MediaBrowser` `Authorization` header, as Jellyfin
 * does. A search ([`/Items`][answerItems]) gets the recording for its Title kind and search term, or the demo's
 * recorded empty result when there is none.
 *
 * `items-movie-caminandes-llama-drama.json` is the recorded search with two subtitle streams added by hand (an
 * external English SubRip file and an embedded Spanish PGS track), because nothing on the demo server has subtitles.
 */
class RecordedJellyfin(
    val username: String = "demo",
    val password: String = "correct horse battery staple",
) : AutoCloseable {

    /** Once true, the server refuses the token, as Jellyfin does after the session is signed out or deleted. */
    @Volatile
    var tokenRevoked = false

    private val requested = mutableListOf<String>()

    /** The path and query of every request so far, oldest first. */
    val requests: List<String> get() = synchronized(requested) { requested.toList() }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use(::respond) }
        start()
    }

    /** The server's address as a Household would type it. */
    val url = "http://127.0.0.1:${server.address.port}"

    private fun respond(exchange: HttpExchange) {
        synchronized(requested) { requested += exchange.requestURI.toString() }
        val path = exchange.requestURI.path
        val authorization = exchange.requestHeaders.getFirst("Authorization")
            ?: return exchange.send(401, resource("unauthorized.html"), "text/html")
        if (!authorization.startsWith("MediaBrowser ") || CLIENT_FIELDS.any { "$it=\"" !in authorization }) {
            // Hand-written: Jellyfin's answer to an Authorization header without the client fields.
            return exchange.send(400, "Missing client fields in the MediaBrowser Authorization header", "text/plain")
        }
        if (exchange.requestMethod == "POST" && path == "/Users/AuthenticateByName") return answerLogin(exchange)
        if (tokenRevoked || TOKEN.find(authorization)?.groupValues?.get(1) != ACCESS_TOKEN) {
            return exchange.send(401, resource("unauthorized.html"), "text/html")
        }
        when {
            exchange.requestMethod != "GET" -> exchange.send(405, "", "text/plain")
            path == "/Items" -> answerItems(exchange)
            EPISODES.matches(path) -> answerEpisodes(exchange, EPISODES.matchEntire(path)!!.groupValues[1])
            path == "/Users/$USER_ID" -> exchange.send(200, resource("users-$USER_ID.json"), JSON)
            else -> exchange.send(404, "", "text/plain")
        }
    }

    private fun answerLogin(exchange: HttpExchange) {
        val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        val user = body["Username"]?.jsonPrimitive?.content
        val pw = body["Pw"]?.jsonPrimitive?.content
        if (user == username && pw == password) {
            exchange.send(200, resource("authenticate-by-name.json"), JSON)
        } else {
            exchange.send(401, resource("unauthorized.html"), "text/html")
        }
    }

    private fun answerItems(exchange: HttpExchange) {
        val query = exchange.query()
        val problem = when {
            query["userId"] != USER_ID -> "userId"
            query["Recursive"] != "true" -> "Recursive"
            query["Fields"]?.split(',')?.containsAll(listOf("ProviderIds", "MediaSources")) != true -> "Fields"
            query["IncludeItemTypes"] !in setOf("Movie", "Series") -> "IncludeItemTypes"
            query["searchTerm"].isNullOrBlank() -> "searchTerm"
            else -> null
        }
        if (problem != null) return exchange.send(400, "The fake has no recording for this $problem: ${exchange.requestURI}", "text/plain")
        val kind = query.getValue("IncludeItemTypes").lowercase()
        val recording = resourceOrNull("items-$kind-${slug(query.getValue("searchTerm"))}.json") ?: resource("items-movie-sintel.json")
        exchange.send(200, recording, JSON)
    }

    private fun answerEpisodes(exchange: HttpExchange, seriesId: String) {
        val query = exchange.query()
        if (query["userId"] != USER_ID || query["Fields"]?.split(',')?.contains("MediaSources") != true) {
            return exchange.send(400, "The fake has no recording for ${exchange.requestURI}", "text/plain")
        }
        val recording = resourceOrNull("shows-$seriesId-episodes-season-${query["season"]}.json")
            ?: return exchange.send(404, "", "text/plain")
        exchange.send(200, recording, JSON)
    }

    private fun HttpExchange.query(): Map<String, String> =
        requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (name, value) = (it.split('=', limit = 2) + "").take(2)
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun HttpExchange.send(status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    private fun slug(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-")

    private fun resourceOrNull(name: String): String? = javaClass.classLoader.getResource("recorded/jellyfin/$name")?.readText()

    private fun resource(name: String): String = checkNotNull(resourceOrNull(name)) { "No recording recorded/jellyfin/$name" }

    override fun close() = server.stop(0)

    companion object {
        /** The token in the recorded login, standing in for the one the demo issued. */
        const val ACCESS_TOKEN = "0123456789abcdef0123456789abcdef"

        /** The demo account's user ID. */
        const val USER_ID = "4ed1b8b42a7c4ea682f0fe5d08d4e278"

        private const val JSON = "application/json; charset=utf-8"
        private val CLIENT_FIELDS = listOf("Client", "Device", "DeviceId", "Version")
        private val TOKEN = Regex("""Token="([^"]*)"""")
        private val EPISODES = Regex("/Shows/([0-9a-f]+)/Episodes")
    }
}
