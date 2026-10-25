package cn.seniorvoice.demo.nextaction.network

import cn.seniorvoice.demo.nextaction.domain.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Exactly one HTTPS POST. No redirects, transport retries, or model tools. API keys stay on the server. */
class AgentApiClient(private val endpoint: String, private val token: String = "") : PredictionService {
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    override fun predict(request: ObservationRequest): PredictionResponse {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) { "AI 预测接口必须是无查询参数的 HTTPS 完整地址" }
        require(!token.contains('\r') && !token.contains('\n')) { "接口令牌不合法" }
        val body = AgentJson.encode(request).toByteArray(Charsets.UTF_8)
        require(body.size <= 2 * 1024 * 1024) { "预测请求超过 2 MiB 限制" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connections[request.requestId] = connection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500; connection.readTimeout = 17000; connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body) }
            require(connection.responseCode in 200..299) { "预测服务返回 HTTP ${connection.responseCode}，本次不执行" }
            val response = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 32768) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 32769 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(response.size <= 32768) { "预测响应超限" }
            return AgentJson.decode(response.toString(Charsets.UTF_8), request)
        } finally { connections.remove(request.requestId); connection.disconnect() }
    }
    override fun cancel(requestId: String) { connections.remove(requestId)?.disconnect() }
}
