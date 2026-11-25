package dev.celastrina.core.port

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** [HttpClient] on `HttpURLConnection`, which is plain JVM and also present on every Android. */
class JdkHttpClient : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = request(url, headers)

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse =
        request(url, headers + ("Content-Type" to "application/json"), body = json.encodeToByteArray())

    private suspend fun request(url: String, headers: Map<String, String>, body: ByteArray? = null): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                headers.forEach(connection::setRequestProperty)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status < 400) connection.inputStream else connection.errorStream
                HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
