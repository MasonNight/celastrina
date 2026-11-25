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
 * It checks credentials itself: [username] with [password] logs in and gets a token, anything else gets the demo's
 * recorded 401. Every other request needs a token in a `MediaBrowser` `Authorization` header, as Jellyfin does.
 *
 * Like Jellyfin, it keeps one session per device: a login ends the session of any earlier login from the same
 * `DeviceId`, whose token stops working. The first login gets the recorded token, [ACCESS_TOKEN], which also works
 * before any login (it is the demo's); later logins get tokens made up in its shape. A search ([`/Items`][answerItems]) gets the recording for its Title kind and search term, or the demo's
 * recorded empty result when there is none.
 *
 * Quick Connect replays the demo's recorded flow: [`Initiate`][answerQuickConnect] gets the code, `Connect` answers
 * waiting until the test [approves][approveQuickConnect] it, and then `AuthenticateWithQuickConnect` logs in as the
 * demo account. Its secret is replaced with [QUICK_CONNECT_SECRET].
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

    /** Once false, the server refuses to start Quick Connect, as Jellyfin does when its dashboard turns it off. */
    @Volatile
    var quickConnectEnabled = true

    @Volatile
    private var quickConnect = QuickConnectState.None

    /** The Household approves the code on another device, signed in to the server. */
    fun approveQuickConnect() {
        check(quickConnect == QuickConnectState.Waiting) { "No Quick Connect is waiting: $quickConnect" }
        quickConnect = QuickConnectState.Approved
    }

    /** The server forgets the code, as Jellyfin does after a few minutes. */
    fun forgetQuickConnect() {
        quickConnect = QuickConnectState.None
    }

    private enum class QuickConnectState { None, Waiting, Approved }

    private val requested = mutableListOf<String>()

    /** The token of each device's session, and the tokens of sessions ended by a later login from the same device. */
    private val sessions = mutableMapOf<String, String>()
    private val endedTokens = mutableSetOf<String>()
    private var tokensIssued = 0

    /** Starts a session for the device [authorization] names, ending its earlier one, and answers its token. */
    private fun startSession(authorization: String): String = synchronized(sessions) {
        val device = DEVICE_ID.find(authorization)!!.groupValues[1]
        sessions[device]?.let { endedTokens += it }
        val token = if (tokensIssued == 0) ACCESS_TOKEN else "%032x".format(tokensIssued)
        tokensIssued++
        sessions[device] = token
        token
    }

    private fun isValid(token: String?): Boolean = synchronized(sessions) {
        token != null && token !in endedTokens && (token == ACCESS_TOKEN || token in sessions.values)
    }

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
        if (exchange.requestMethod == "POST" && path == "/Users/AuthenticateByName") return answerLogin(exchange, authorization)
        if (path.startsWith("/QuickConnect/") || path == "/Users/AuthenticateWithQuickConnect") return answerQuickConnect(exchange, authorization)
        if (tokenRevoked || !isValid(TOKEN.find(authorization)?.groupValues?.get(1))) {
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

    private fun answerLogin(exchange: HttpExchange, authorization: String) {
        val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        val user = body["Username"]?.jsonPrimitive?.content
        val pw = body["Pw"]?.jsonPrimitive?.content
        if (user == username && pw == password) {
            exchange.send(200, resource("authenticate-by-name.json").replace(ACCESS_TOKEN, startSession(authorization)), JSON)
        } else {
            exchange.send(401, resource("unauthorized.html"), "text/html")
        }
    }

    private fun answerQuickConnect(exchange: HttpExchange, authorization: String) {
        val path = exchange.requestURI.path
        when {
            exchange.requestMethod == "POST" && path == "/QuickConnect/Initiate" -> if (quickConnectEnabled) {
                quickConnect = QuickConnectState.Waiting
                exchange.send(200, resource("quick-connect-initiate.json"), JSON)
            } else {
                // Hand-written: the demo keeps Quick Connect on. Jellyfin answers 401 with this text when it's off.
                exchange.send(401, "Quick connect is disabled", "text/plain")
            }
            exchange.requestMethod == "GET" && path == "/QuickConnect/Connect" -> when {
                exchange.query()["secret"] != QUICK_CONNECT_SECRET || quickConnect == QuickConnectState.None ->
                    exchange.send(404, resource("not-found.html"), "text/html")
                quickConnect == QuickConnectState.Waiting -> exchange.send(200, resource("quick-connect-connect-waiting.json"), JSON)
                else -> exchange.send(200, resource("quick-connect-connect-approved.json"), JSON)
            }
            exchange.requestMethod == "POST" && path == "/Users/AuthenticateWithQuickConnect" -> {
                val secret = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject["Secret"]?.jsonPrimitive?.content
                if (secret == QUICK_CONNECT_SECRET && quickConnect == QuickConnectState.Approved) {
                    exchange.send(200, resource("authenticate-with-quick-connect.json").replace(ACCESS_TOKEN, startSession(authorization)), JSON)
                } else {
                    // Hand-written: an unapproved secret wasn't recorded. This is Jellyfin's 401 for a login it turns down.
                    exchange.send(401, resource("unauthorized.html"), "text/html")
                }
            }
            else -> exchange.send(404, resource("not-found.html"), "text/html")
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
        // A search with no recording of its own gets the demo's empty answer, recorded searching for Sintel, which it lacks.
        val recording = resourceOrNull("items-$kind-${slug(query.getValue("searchTerm"))}.json") ?: resource("items-no-match.json")
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

        /** The code in the recorded Quick Connect, which the TV shows for the Household to approve. */
        const val QUICK_CONNECT_CODE = "304393"

        /** The secret in the recorded Quick Connect, standing in for the one the demo issued. */
        const val QUICK_CONNECT_SECRET = "F00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00DF00D"

        /** The demo account's user ID. */
        const val USER_ID = "4ed1b8b42a7c4ea682f0fe5d08d4e278"

        private const val JSON = "application/json; charset=utf-8"
        private val CLIENT_FIELDS = listOf("Client", "Device", "DeviceId", "Version")
        private val TOKEN = Regex("""Token="([^"]*)"""")
        private val DEVICE_ID = Regex("""DeviceId="([^"]*)"""")
        private val EPISODES = Regex("/Shows/([0-9a-f]+)/Episodes")
    }
}
