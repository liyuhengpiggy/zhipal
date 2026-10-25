package cn.seniorvoice.demo.assist

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

class ACommandHttpException(val statusCode: Int) : IOException("A 指令服务 HTTP $statusCode")

/** Blocking worker-only client. cancelAll() cancels sockets and invalidates queued/results tickets. */
class ACommandApiClient(
    baseUrl: String,
    private val token: String,
    allowLanHttp: Boolean = false,
    debugBuild: Boolean = false
) : AutoCloseable {
    val endpoint: URI = ACommandProtocol.endpoint(baseUrl, allowLanHttp, debugBuild)
    private val lock = Any()
    private var epoch = 0L
    private val active = mutableSetOf<Call>()
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    init { ACommandProtocol.validateToken(token) }

    fun ticket(): Long = synchronized(lock) { epoch }
    fun cancelAll() {
        val calls = synchronized(lock) { epoch++; active.toList().also { active.clear() } }
        calls.forEach { it.cancel() }
    }
    fun cancel() = cancelAll()
    private fun checkTicket(ticket: Long) { check(synchronized(lock) { epoch == ticket }) { "A 指令任务已取消" } }

    fun interpret(request: ACommandRequest, ticket: Long = ticket()): ACommandResponse {
        checkTicket(ticket)
        val snapshot = ACommandProtocol.snapshot(request)
        val payload = ACommandProtocol.encode(snapshot)
        val call = http.newCall(Request.Builder().url(endpoint.toString())
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        synchronized(lock) { checkTicket(ticket); active.add(call) }
        val ids = mapOf("task_id" to snapshot.taskId)
        IntegrationLog.event("A_COMMAND", "request", ids, url = endpoint.toString(), bytes = payload.toByteArray(Charsets.UTF_8).size.toLong())
        try {
            val result = call.execute().use { response ->
                checkTicket(ticket)
                IntegrationLog.event("A_COMMAND", "response", ids, code = response.code, url = endpoint.toString())
                if (!response.isSuccessful) throw ACommandHttpException(response.code)
                val body = response.body ?: throw IOException("A 指令服务返回空响应")
                val type = body.contentType()
                require(type?.type == "application" && type.subtype == "json") { "A 指令服务未返回 JSON" }
                require(body.contentLength() <= ACommandProtocol.MAX_RESPONSE_BYTES) { "A 指令响应过大" }
                val source = body.source()
                require(!source.request(ACommandProtocol.MAX_RESPONSE_BYTES.toLong() + 1)) { "A 指令响应过大" }
                checkTicket(ticket)
                val bytes = source.readByteArray()
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) { throw IllegalArgumentException("A 指令响应不是完整 UTF-8") }
                ACommandProtocol.parseResponse(text, snapshot)
            }
            checkTicket(ticket)
            IntegrationLog.event("A_COMMAND", "parsed", ids)
            return result
        } catch (error: IOException) {
            checkTicket(ticket)
            IntegrationLog.event("A_COMMAND", "failed", ids)
            // Provider/server response bodies, endpoints and credentials never enter errors.
            if (error is ACommandHttpException) throw error
            throw IOException("A 指令网络请求失败或超时，请检查服务器配置后重试")
        } finally {
            synchronized(lock) { active.remove(call) }
            call.cancel()
        }
    }

    override fun close() = cancelAll()
    companion object { const val CALL_TIMEOUT_SECONDS = 20L }
}
