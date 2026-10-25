package com.mobileagent.app.api

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClient(private val config: ApiConfig) : VlmApiClient {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun predictWithImages(
        systemPrompt: String?,
        textPrompt: String,
        images: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val result = doRequest(systemPrompt, textPrompt, images)
                return@withContext Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        Result.failure(lastError ?: Exception("Unknown error"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun doRequest(
        systemPrompt: String?,
        textPrompt: String,
        images: List<Bitmap>
    ): String {
        val requestBody = BModelProtocol.request(config.model, systemPrompt, textPrompt, images.map { ImageEncoder.encode(it) })

        val endpoint = config.endpoint.trimEnd('/')
        val url = if (endpoint.endsWith("/chat/completions")) endpoint
        else "$endpoint/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        cn.seniorvoice.demo.assist.IntegrationLog.event("B_MODEL", "request", url = url, bytes = requestBody.toString().toByteArray(Charsets.UTF_8).size.toLong())

        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) { response.close() } else response.close()
                }
            })
        }
        return response.use {
        cn.seniorvoice.demo.assist.IntegrationLog.event("B_MODEL", "response", code = response.code, url = url)
        if (!response.isSuccessful) {
            throw Exception("模型接口 HTTP ${response.code}")
        }
        val source = response.body?.source() ?: error("Empty response body")
        require(!source.request(262145)) { "model_response_too_large" }
        BModelProtocol.response(source.readUtf8())
        }
    }

    companion object {
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 1_000L
    }
}
