package dev.celastrina.app

import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.PlayerEvent
import dev.celastrina.core.source.Stream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Asks where the Stream leads and what it serves, bounded across redirects and reading the response. */
internal suspend fun Stream.checkAddress(): PlayerEvent.AddressCheckAnswer {
    val request = try {
        Request.Builder().url(url).apply {
            for ((name, value) in headers) header(name, value)
        }.build()
    } catch (e: IllegalArgumentException) {
        return PlayerEvent.AddressCheckUnavailable
    }
    return withTimeoutOrNull(Failover.ADDRESS_CHECK_LIMIT.inWholeMilliseconds) {
        suspendCancellableCoroutine { continuation ->
            val call = addressCheckClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(PlayerEvent.AddressCheckUnavailable)
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { it.addressCheckAnswer() }
                    } catch (e: IOException) {
                        PlayerEvent.AddressCheckUnavailable
                    }
                    continuation.resume(answer)
                }
            })
        }
    } ?: PlayerEvent.AddressCheckTimedOut
}

// The coroutine deadline covers the whole call, including time spent following redirects and sniffing bytes.
private val addressCheckClient = OkHttpClient.Builder()
    .connectTimeout(0, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

private fun Response.addressCheckAnswer(): PlayerEvent.AddressCheckAnswer {
    if (!isSuccessful) return PlayerEvent.AddressCheckHttpError(code)
    val contentType = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
    val delivery = when {
        contentType in HLS_TYPES -> Stream.Delivery.Hls
        contentType == "application/dash+xml" -> Stream.Delivery.Dash
        contentType?.startsWith("video/") == true || contentType?.startsWith("audio/") == true -> Stream.Delivery.Progressive
        else -> sniffDelivery(peekBody(FIRST_BYTES).string())
    }
    return PlayerEvent.AddressChecked(request.url.toString(), delivery)
}

private fun sniffDelivery(firstBytes: String): Stream.Delivery {
    val text = firstBytes.trimStart { it == '\uFEFF' || it.isWhitespace() }
    return when {
        text.startsWith("#EXTM3U") -> Stream.Delivery.Hls
        text.startsWith("<") && "<MPD" in text -> Stream.Delivery.Dash
        else -> Stream.Delivery.Progressive
    }
}

private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
private const val FIRST_BYTES = 1024L
