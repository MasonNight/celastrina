package dev.celastrina.core.port

/** The Core's only way onto the network. */
interface HttpClient {
    /** Throws [java.io.IOException] when no response arrives at all. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

class HttpResponse(val status: Int, val body: ByteArray) {
    val isSuccessful: Boolean get() = status in 200..299
    fun bodyText(): String = body.decodeToString()
}
