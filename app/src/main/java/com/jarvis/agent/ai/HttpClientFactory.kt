package com.jarvis.agent.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared HTTP/2-capable client with TLS/connection reuse.
 * Coroutine cancellation closes the in-flight request immediately when Stop is pressed.
 */
class HttpClientFactory {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class Reply(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    suspend fun execute(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { reply ->
                        val result = Reply(reply.code, reply.body?.string().orEmpty())
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
