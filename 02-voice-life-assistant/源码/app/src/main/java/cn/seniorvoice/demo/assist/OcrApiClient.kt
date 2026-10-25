package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Bounded blocking requests, used only on the OCR worker; cancellation closes the active socket. */
class OcrApiClient {
    private val lock = Any()
    private var epoch = 0L
    private var active: HttpURLConnection? = null
    fun ticket(): Long = synchronized(lock) { epoch }
    fun cancelAll() { synchronized(lock) { epoch++; active?.disconnect(); active = null } }
    fun check(ticket: Long) { check(synchronized(lock) { epoch == ticket }) { "OCR 任务已取消" } }

    fun request(base: URI, token: String, method: String, path: String, ticket: Long,
                bytes: ByteArray? = null, contentType: String = "application/json; charset=utf-8"): JSONObject {
        check(ticket)
        require(!token.contains('\r') && !token.contains('\n')) { "令牌不能包含换行" }
        require(!path.startsWith('/') && !path.contains(".."))
        val connection = base.resolve(path).toURL().openConnection() as HttpURLConnection
        IntegrationLog.event("OCR_HTTP", "request", url = connection.url.toString(), bytes = bytes?.size?.toLong())
        synchronized(lock) { check(ticket); check(active == null) { "OCR 请求不可交错" }; active = connection }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 12000
            connection.requestMethod = method
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (bytes != null) {
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            IntegrationLog.event("OCR_HTTP", "response", code = code, url = connection.url.toString())
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    check(ticket)
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024) { "OCR 响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            check(ticket)
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> "令牌不正确或没有权限"
                    404 -> "没有该接口或任务；检查 OCR 服务地址，原 Render 服务尚未提供新任务接口"
                    409 -> "任务或照片冲突，请新建任务重新拍摄"
                    413 -> "照片过大，请降低分辨率"
                    429 -> "OCR 队列已满，请稍后重试"
                    503 -> "OCR 模型或数据库未就绪"
                    else -> "请求失败，请检查 OCR 服务"
                }
                throw IOException("OCR HTTP $code：$hint")
            }
            return JSONObject(body)
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }

    fun ready(base: URI, token: String, ticket: Long) {
        val health = request(base, token, "GET", "health", ticket)
        check(health.optBoolean("ready") && health.optJSONObject("capabilities")?.optBoolean("ocr") == true) {
            "该地址的 OCR 模型尚未就绪；需 health.ready=true 且 capabilities.ocr=true"
        }
    }

    fun create(base: URI, token: String, task: String, question: String, ticket: Long): JSONObject {
        OcrProtocol.checkId(task)
        require(question.isNotBlank() && question.length <= 1000)
        return request(base, token, "POST", "api/v1/vision/tasks", ticket,
            JSONObject().put("task_id", task).put("question", question).put("expected_frames", 1).toString().toByteArray(Charsets.UTF_8))
            .also { OcrProtocol.validateSnapshot(it, task, question) }
    }

    fun upload(base: URI, token: String, task: String, question: String, jpeg: ByteArray, ticket: Long): JSONObject {
        OcrProtocol.checkId(task)
        require(jpeg.size in 4..(8 * 1024 * 1024) && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) { "请选择完整 JPEG，最多 8 MiB" }
        return request(base, token, "PUT", "api/v1/vision/tasks/$task/frames/f0", ticket, jpeg, "image/jpeg")
            .also { OcrProtocol.validateSnapshot(it, task, question) }
    }

    fun get(base: URI, token: String, task: String, question: String, ticket: Long): JSONObject {
        OcrProtocol.checkId(task)
        return request(base, token, "GET", "api/v1/vision/tasks/$task", ticket)
            .also { OcrProtocol.validateSnapshot(it, task, question) }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
