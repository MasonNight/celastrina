package dev.celastrina.core.testing

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch

/**
 * The HTTP fake for Extensions: one local server serving the test Extension Repository and standing in for the sites
 * the test Extension reaches, the Internet Archive (search and metadata) and the Wikidata Query Service, replaying
 * recorded responses.
 *
 * The test Extension Repository is the one Gradle builds (`:test-extension:testRepository`). It's served twice, at
 * [repositoryUrl] and at [otherRepositoryUrl], so a test can offer an Extension with the same name from a second
 * Extension Repository.
 *
 * Creating one routes CloudStream's shared HTTP client, which Extensions use, to this server for `archive.org` and
 * `query.wikidata.org`. A search gets the recording for its title (`internet-archive-search/<slug>.json`), metadata the
 * recording for its item, and a SPARQL query the recording for its IMDb ID; anything unrecorded gets HTTP 500.
 */
class RecordedExtensionSites : AutoCloseable {

    /** Serve the `.cs3` with one byte changed, as a corrupted or substituted download would be. */
    @Volatile
    var tamperedCode = false

    /** Answer the Archive with HTTP 500 and an HTML page, as it does when it's down. */
    @Volatile
    var archiveDown = false

    /** Never answer the Archive: a request waits until the server closes. */
    @Volatile
    var archiveHangs = false

    private val closing = CountDownLatch(1)
    private val requested = mutableListOf<String>()

    /** The path and query of every request so far, oldest first. */
    val requests: List<String> get() = synchronized(requested) { requested.toList() }

    private val repository = File(checkNotNull(System.getProperty("celastrina.testRepository")) { "Run through Gradle: no test repository" })

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        createContext("/") { exchange -> exchange.use(::respond) }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"

    val repositoryUrl = "$base/repo/repo.json"
    val otherRepositoryUrl = "$base/other/repo.json"

    init {
        val port = server.address.port
        val toHere = Interceptor { chain ->
            val url = chain.request().url
            val routed = if (url.host in SITES) url.newBuilder().scheme("http").host("127.0.0.1").port(port).build() else url
            chain.proceed(chain.request().newBuilder().url(routed).build())
        }
        app.baseClient = OkHttpClient.Builder().addInterceptor(toHere).build()
    }

    private fun respond(exchange: HttpExchange) {
        synchronized(requested) { requested += exchange.requestURI.toString() }
        val path = exchange.requestURI.path
        when {
            path.startsWith("/repo/") || path.startsWith("/other/") -> answerRepository(exchange, path.substringAfter('/').substringAfter('/'))
            path == "/sparql" -> answerSparql(exchange)
            archiveHangs -> closing.await()
            // Hand-written: the Archive's error page when it's down can't be summoned on demand.
            archiveDown -> exchange.send(500, "<html><body>Internal Server Error</body></html>".toByteArray(), "text/html")
            path == "/advancedsearch.php" -> answerSearch(exchange)
            path.startsWith("/metadata/") -> answerMetadata(exchange)
            else -> exchange.send(404, ByteArray(0), "text/plain")
        }
    }

    private fun answerRepository(exchange: HttpExchange, name: String) {
        val file = File(repository, name).takeIf { it.isFile } ?: return exchange.send(404, ByteArray(0), "text/plain")
        val bytes = file.readBytes()
        if (tamperedCode && name.endsWith(".cs3")) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        exchange.send(200, bytes, if (name.endsWith(".json")) "application/json" else "application/octet-stream")
    }

    private fun answerSearch(exchange: HttpExchange) {
        val query = exchange.queryParameter("q").orEmpty()
        val title = SEARCHED_TITLE.find(query)?.groupValues?.get(1)
            ?: return exchange.send(500, "Unrecorded search $query".toByteArray(), "text/plain")
        val recording = resource("recorded/internet-archive-search/${slug(title)}.json")
            ?: return exchange.send(500, "No recording of the search for $title".toByteArray(), "text/plain")
        exchange.send(200, recording, "application/json")
    }

    private fun answerMetadata(exchange: HttpExchange) {
        val identifier = URLDecoder.decode(exchange.requestURI.rawPath.removePrefix("/metadata/"), Charsets.UTF_8)
        val recording = resource("recorded/internet-archive/$identifier.json")
            ?: return exchange.send(500, "No recording of Archive item $identifier".toByteArray(), "text/plain")
        exchange.send(200, recording, "application/json")
    }

    private fun answerSparql(exchange: HttpExchange) {
        val query = exchange.queryParameter("query").orEmpty()
        val imdbId = IMDB_ID.find(query)?.groupValues?.get(1)
        val recording = imdbId?.let { resource("recorded/wikidata/imdb-$it.json") } ?: resource("recorded/wikidata/no-match.json")!!
        exchange.send(200, recording, "application/sparql-results+json;charset=utf-8")
    }

    private fun HttpExchange.queryParameter(name: String): String? =
        requestURI.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }

    private fun HttpExchange.send(status: Int, body: ByteArray, contentType: String) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    private fun resource(path: String): ByteArray? = javaClass.classLoader.getResource(path)?.readBytes()

    override fun close() {
        closing.countDown()
        server.stop(0)
    }

    private companion object {
        val SITES = setOf("archive.org", "query.wikidata.org")
        val SEARCHED_TITLE = Regex("""title:\("(.*)"\)""")
        val IMDB_ID = Regex("""wdt:P345 "(tt\d+)"""")

        fun slug(title: String) = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-")
    }
}
