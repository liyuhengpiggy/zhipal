package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Adapter for the user's existing Render API. Image-only analysis never becomes a phone action. */
class VisionClient {
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    @Volatile private var epoch = 0L
    fun ticket() = epoch
    fun cancelAll() { epoch++; connections.values.forEach { it.disconnect() }; connections.clear() }
    fun call(base: String, token: String, jpeg: ByteArray, intent: String? = null, expectedEpoch: Long = epoch): JSONObject {
        val ticket = expectedEpoch
        check(epoch == ticket) { "任务已取消" }
        val uri = URI(base.trimEnd('/') + if (intent == null) "/vision/analyze" else "/analyze")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(jpeg.size in 4..(2 * 1024 * 1024)) { "图片大小无效或超过 2 MiB" }
        require(!token.contains('\r') && !token.contains('\n'))
        val boundary = "glasses-${UUID.randomUUID()}"
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            write(jpeg); write("\r\n".toByteArray())
            if (intent != null) {
                require(intent.length in 1..1000)
                write("--$boundary\r\nContent-Disposition: form-data; name=\"intent\"\r\n\r\n$intent\r\n".toByteArray(Charsets.UTF_8))
            }
            write("--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val id = UUID.randomUUID().toString()
        val connection = uri.toURL().openConnection() as HttpURLConnection
        IntegrationLog.event("LEGACY_VISION", "request", url = uri.toString(), bytes = jpeg.size.toLong())
        connections[id] = connection
        try {
            check(epoch == ticket) { "任务已取消" }
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 45000
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            IntegrationLog.event("LEGACY_VISION", "response", code = code, url = uri.toString())
            check(code in 200..299) { "视觉服务 HTTP $code" }
            val response = connection.inputStream.use { it.readNBytesCompat(65536) }
            check(epoch == ticket) { "任务已取消" }
            return JSONObject(response.toString(Charsets.UTF_8))
        } finally { connections.remove(id); connection.disconnect() }
    }
    fun downloadFrame(base: String, location: String, expectedEpoch: Long = epoch): ByteArray {
        check(epoch == expectedEpoch) { "任务已取消" }
        val configured = URI(base); val uri = configured.resolve(location)
        require(uri.scheme == "https" && uri.host == configured.host && uri.port == configured.port &&
            uri.userInfo == null && uri.fragment == null) { "照片地址必须属于配置的 HTTPS 视觉服务" }
        require(uri.normalize().path.trimEnd('/') != "/camera/latest") { "需要本任务专属照片地址，不能使用最近照片缓存" }
        val ticket = expectedEpoch; val id = UUID.randomUUID().toString()
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connections[id] = connection
        try {
            check(epoch == ticket)
            connection.instanceFollowRedirects = false; connection.connectTimeout = 8000; connection.readTimeout = 15000
            check(connection.responseCode in 200..299) { "读取照片失败" }
            val image = connection.inputStream.use { it.readNBytesCompat(2 * 1024 * 1024) }
            check(epoch == ticket)
            require(image.size >= 4 && image[0] == 0xff.toByte() && image[1] == 0xd8.toByte()) { "需要真实 JPEG 照片" }
            return image
        } finally { connections.remove(id); connection.disconnect() }
    }
    companion object {
        const val DEFAULT_BASE = ServerConfig.DEFAULT_BASE
        fun scene(response: JSONObject): String {
            check(response.optString("status") == "ok" && response.optString("mode") == "cloud") { "视觉服务未返回真实云端分析（可能为模拟模式）" }
            val analysis = response.getJSONObject("analysis")
            return listOf(analysis.optString("scene"), analysis.optString("current_state")).filter { it.isNotBlank() }
                .joinToString("\n").take(1400).also { check(it.isNotBlank()) { "没有识别到页面内容" } }
        }
        fun answer(response: JSONObject): String {
            check(response.optString("status") !in setOf("mock", "error") && response.optString("mode") != "mock") { "视觉服务未返回有效回答" }
            check(response.optString("action") != "unknown") { "视觉服务未能识别本次问题，尚未返回图片分析结果" }
            return response.optString("message").takeIf { it.isNotBlank() }?.take(1800) ?: error("视觉服务没有返回文字建议")
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        check(output.size() <= limit) { "服务响应超过大小限制" }
    }
    return output.toByteArray()
}
