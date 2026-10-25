package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class VisionMode { OCR, OBJECT, COMBINED }

/** Opt-in task API. The server contract is documented in docs/统一视觉任务接口_0.7.8.md. */
class VisionApiClient(private val config: ServerConfig, private val token: String) {
    data class Upload(val frameId: String, val url: String, val headers: JSONObject, val expiresAt: Instant)
    data class Answer(val text: String, val sha256: String, val reviewRequired: Boolean, val evidence: String) {
        fun display(): String = (if (reviewRequired) "以下识别结果需要人工核对。\n" else "") + text +
            (if (evidence.isNotBlank() && evidence != text) "\n\n识别原文：\n$evidence" else "")
    }
    private val transport = OcrApiClient()
    private val uploadConnection = AtomicReference<HttpURLConnection?>()
    fun ticket() = transport.ticket()
    fun cancel() { transport.cancelAll(); uploadConnection.getAndSet(null)?.disconnect() }
    fun check(ticket: Long) = transport.check(ticket)
    fun create(id: String, question: String, mode: VisionMode, ticket: Long): JSONObject {
        OcrProtocol.checkId(id)
        require(question.length in 1..1000 && question.isNotBlank()) { "视觉问题不能为空或超过 1000 字" }
        IntegrationLog.event("VISION", "create", mapOf("task_id" to id, "mode" to mode.name))
        return transport.request(config.base, token, "POST", "api/v1/vision/tasks", ticket,
            JSONObject().put("task_id", id).put("question", question).put("mode", mode.name)
                .put("expected_frames", 1).toString().toByteArray(Charsets.UTF_8)).also { snapshot(it, id, question, mode) }
    }
    fun get(id: String, question: String, mode: VisionMode, ticket: Long): JSONObject {
        OcrProtocol.checkId(id)
        return transport.request(config.base, token, "GET", "api/v1/vision/tasks/$id", ticket)
            .also { snapshot(it, id, question, mode) }
    }
    fun uploadTicket(snapshot: JSONObject): Upload {
        val id = snapshot.getString("task_id"); OcrProtocol.checkId(id)
        require(snapshot.getString("status") == "awaiting_frame" && snapshot.getString("frame_id") == "f0") { "任务没有可用的本次上传凭证" }
        require(snapshot.getString("upload_method") == "PUT") { "暂只支持 PUT JPEG 上传" }
        val uri = URI(snapshot.getString("upload_url"))
        require(uri.toString().length <= 2048) { "上传地址过长，无法通过 BLE 转交" }
        val sameLan = config.base.scheme == "http" && uri.scheme == "http" && uri.host == config.base.host && uri.port == config.base.port
        require((uri.scheme == "https" || sameLan) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "上传地址不安全或无效" }
        val expires = Instant.parse(snapshot.getString("upload_expires_at"))
        val seconds = expires.epochSecond - Instant.now().epochSecond
        require(seconds in 1..900) { "上传凭证已过期或有效期超过 15 分钟" }
        val headers = snapshot.optJSONObject("upload_headers") ?: JSONObject()
        require(headers.toString().length <= 2048) { "上传凭证过长，无法通过 BLE 转交" }
        require(headers.length() <= 6) { "上传凭证头过多" }
        headers.keys().forEach { name ->
            val value = headers.getString(name)
            require(name.lowercase() in setOf("authorization", "x-upload-token", "content-type") && value.length in 1..2048 &&
                !value.contains('\r') && !value.contains('\n')) { "上传凭证头无效" }
            require(token.isBlank() || (value != token && value != "Bearer $token")) { "服务器不能把长期任务令牌转交给眼镜，请签发限时上传凭证" }
            if (name.equals("content-type", true)) require(value == "image/jpeg") { "上传类型必须是 JPEG" }
        }
        return Upload("f0", uri.toString(), JSONObject(headers.toString()), expires)
    }
    fun upload(upload: Upload, jpeg: ByteArray, ticket: Long) {
        check(ticket)
        require(Instant.now().isBefore(upload.expiresAt)) { "上传凭证已过期" }
        require(jpeg.size in 4..8 * 1024 * 1024 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) { "需要有效 JPEG，最多 8 MiB" }
        val connection = URI(upload.url).toURL().openConnection() as HttpURLConnection
        check(uploadConnection.compareAndSet(null, connection)) { "上传请求不可交错" }
        try {
            check(ticket)
            connection.instanceFollowRedirects = false; connection.requestMethod = "PUT"
            connection.connectTimeout = 8000; connection.readTimeout = 12000
            connection.doOutput = true; connection.setFixedLengthStreamingMode(jpeg.size)
            connection.setRequestProperty("Content-Type", "image/jpeg")
            upload.headers.keys().forEach { connection.setRequestProperty(it, upload.headers.getString(it)) }
            IntegrationLog.event("VISION", "upload", bytes = jpeg.size.toLong(), url = upload.url)
            connection.outputStream.use { it.write(jpeg) }
            val code = connection.responseCode
            IntegrationLog.event("VISION", "uploaded", code = code, url = upload.url)
            require(code in 200..299) { "视觉照片上传失败 HTTP $code" }
            check(ticket)
        } finally { uploadConnection.compareAndSet(connection, null); connection.disconnect() }
    }
    companion object {
        /** Unknown A speech stays in A. A generic question alone is not a request to take a photo. */
        fun isVisualQuestion(question: String): Boolean {
            if (Regex("不要|不用|别(?:帮我)?(?:拍|读|念|识别)|\\bdon't\\b|\\bdo not\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)) return false
            return OcrProtocol.supports(question) || Regex(
                "这是什么|什么东西|什么物体|物体|颜色|形状|眼前|面前|手里|手上|包装|药盒|识别(?:一下|这个|这)|看看这个|看一下这个|" +
                    "what is this|what am i (?:holding|looking at)|identify this|describe this|\\bobject\\b|\\bcolou?r\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(question)
        }
        fun mode(question: String): VisionMode {
            val ocr = OcrProtocol.supports(question)
            val physical = Regex("什么东西|什么物体|物体|颜色|形状|这是什么|what is this|object|colour|color", RegexOption.IGNORE_CASE).containsMatchIn(question)
            return if (ocr && physical) VisionMode.COMBINED else if (ocr) VisionMode.OCR else VisionMode.OBJECT
        }
        fun snapshot(root: JSONObject, id: String, question: String, mode: VisionMode) {
            require(root.getString("task_id") == id && root.getString("question") == question &&
                root.getString("mode") == mode.name && root.getInt("expected_frames") == 1) { "视觉响应与本次任务/问题/模式不匹配" }
            require(root.getString("status") in setOf("awaiting_frame", "queued", "running", "succeeded", "failed", "timed_out", "expired")) { "未知视觉任务状态" }
            require(!root.optBoolean("mock") && root.optString("inference_backend") !in setOf("mock", "demo")) { "模拟结果不能用于验收" }
        }
        fun answer(root: JSONObject, id: String, question: String, mode: VisionMode, expectedSha: String? = null): Answer {
            snapshot(root, id, question, mode)
            require(root.getString("status") == "succeeded") { "视觉任务尚未成功" }
            val result = root.getJSONObject("result")
            val frames = root.getJSONArray("frames")
            val sha = result.getString("sha256")
            require(frames.length() == 1 && frames.getJSONObject(0).getString("frame_id") == "f0" &&
                result.getString("frame_id") == "f0" && Regex("[a-f0-9]{64}").matches(sha) &&
                frames.getJSONObject(0).getString("sha256") == sha && (expectedSha == null || sha == expectedSha)) { "结果没有绑定本次照片或照片校验失败" }
            val answer = result.getString("answer").trim()
            require(answer.length in 1..12000) { "视觉答案为空或过长" }
            val evidence = result.optString("evidence_text")
            require(evidence.length <= 16000 && (mode == VisionMode.OBJECT || evidence.isNotBlank())) { "文字识别缺少可核对原文" }
            return Answer(answer, sha, result.optBoolean("review_required", mode != VisionMode.OBJECT), evidence)
        }
    }
}
