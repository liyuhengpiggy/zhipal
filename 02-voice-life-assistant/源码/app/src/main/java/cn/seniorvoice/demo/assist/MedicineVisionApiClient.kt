package cn.seniorvoice.demo.assist

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** APP_SERVER_HANDOFF v1: OCR tasks and the separate synchronous object endpoint.
 *
 * The documented upload route requires the phone's server Bearer token. It does not issue a
 * scoped hardware upload ticket. The workflow passes only this self-hosted server token to the
 * hardware's documented upload route; third-party model API keys stay on the server.
 * A ticket owns one 180-second budget, including health, creation, uploads and polling.
 */
class MedicineVisionApiClient(
    private val config: ServerConfig,
    private val token: String,
    private val nanoTime: () -> Long = System::nanoTime
) {
    data class Answer(val text: String, val evidence: String, val reviewRequired: Boolean) {
        fun display(): String = (if (reviewRequired) "以下识别结果需要核对原图。\n" else "") + text +
            (if (evidence.isNotBlank() && evidence != text) "\n\n识别原文：\n$evidence" else "")
    }

    data class Snapshot(val taskId: String, val status: String, val answer: Answer?,
                        val errorCode: String?, val errorMessage: String?, val raw: JSONObject)

    data class Health(val status: String, val ready: Boolean, val capabilities: Set<String>)

    class ApiException(val httpStatus: Int, val code: String, message: String) : IOException(message) {
        val mayRetryLater: Boolean get() = httpStatus == 429 || httpStatus == 503
    }

    private data class Spec(val question: String, val expectedFrames: Int, val recordId: String?)
    private val lock = java.lang.Object()
    private var epoch = 0L
    private var deadline: Long? = null
    private var active: Call? = null
    private val tasks = mutableMapOf<String, Spec>()

    init {
        val base = config.base
        require(base.scheme in setOf("https", "http") && !base.host.isNullOrBlank() &&
            base.userInfo == null && base.query == null && base.fragment == null && base.path in setOf("", "/")) {
            "请填写视觉服务器根地址，不包含 /docs 或接口路径"
        }
        require(token.isNotBlank() && token.length <= 8192 && !token.contains('\r') && !token.contains('\n')) {
            "请先配置视觉服务器访问令牌"
        }
    }

    fun ticket(): Long = synchronized(lock) {
        if (deadline == null) deadline = nanoTime() + TASK_TIMEOUT_NANOS
        epoch
    }

    fun cancel() {
        val call = synchronized(lock) {
            epoch++
            deadline = null
            tasks.clear()
            lock.notifyAll()
            active.also { active = null }
        }
        call?.cancel()
    }

    fun check(ticket: Long) { remainingNanos(ticket) }

    fun health(ticket: Long): Health {
        val root = request("GET", "health", ticket, authenticated = false)
        val status = stringField(root, "status") ?: "unknown"
        val capabilities = root.optJSONObject("capabilities")
        val available = setOf("ocr", "object_recognition", "mobileagentb_proxy")
            .filter { capabilities?.opt(it) == true }.toSet()
        return Health(status, root.opt("ready") == true, available)
    }

    fun requireReady(ticket: Long, capability: String = "ocr"): Health = health(ticket).also {
        if (it.status == "degraded") throw ApiException(503, "service_degraded", "视觉服务暂时不可用，请稍后重试")
        if (!it.ready) throw ApiException(503, "service_initializing", "视觉服务正在初始化，请稍后重试")
        if (capability !in it.capabilities)
            throw ApiException(503, "capability_unavailable", "视觉服务尚未提供所需识别能力")
    }

    /** An empty question intentionally creates an awaiting_question task for later PATCH. */
    fun createOcr(id: String, question: String, ticket: Long, recordId: String? = null,
                  expectedFrames: Int = 1): Snapshot {
        check(ticket); checkId(id)
        require(question.length <= 1000 && (question.isEmpty() || question.isNotBlank())) { "视觉问题不能为空白或超过 1000 字" }
        require(expectedFrames in 1..4) { "每个 OCR 任务需要 1 到 4 张照片" }
        require(recordId == null || recordId.length in 1..160) { "记录编号无效" }
        val spec = Spec(question, expectedFrames, recordId)
        synchronized(lock) {
            check(ticket)
            require(tasks[id] == null || tasks[id] == spec) { "不能用相同任务编号提交不同参数" }
            tasks[id] = spec
        }
        val body = JSONObject().put("task_id", id).put("source_button", "A").put("task_type", "medicine_ocr")
            .put("expected_frames", expectedFrames)
        if (question.isNotEmpty()) body.put("question", question)
        if (recordId != null) body.put("record_id", recordId)
        val root = request("POST", "api/v1/vision/tasks", ticket, body.toString().toByteArray(Charsets.UTF_8))
        validateOptionalBinding(root, id)
        // The handoff omits the create-response schema. Confirm an acknowledgement through GET
        // instead of assuming upload_url, question, mode or frame hashes are returned by create.
        return if (root.has("task_id") && root.has("status")) snapshot(root, id) else getOcr(id, ticket)
    }

    fun uploadOcr(id: String, frameId: String, jpeg: ByteArray, ticket: Long) {
        check(ticket); checkId(id); checkId(frameId); checkJpeg(jpeg)
        val root = request("PUT", "api/v1/vision/tasks/$id/frames/$frameId", ticket, jpeg, "image/jpeg",
            mapOf("X-Task-Id" to id, "X-Frame-Id" to frameId))
        if (root.has("frame_id")) require(root.get("frame_id") == frameId) { "视觉上传响应属于其他照片" }
        validateAcknowledgement(root, id)
    }

    fun patchQuestion(id: String, question: String, ticket: Long) {
        check(ticket); checkId(id); checkQuestion(question)
        val root = request("PATCH", "api/v1/vision/tasks/$id", ticket,
            JSONObject().put("question", question).toString().toByteArray(Charsets.UTF_8))
        if (root.has("task_id")) require(root.get("task_id") == id) { "视觉响应属于其他任务" }
        synchronized(lock) {
            check(ticket)
            tasks[id]?.let { tasks[id] = it.copy(question = question) }
        }
        validateAcknowledgement(root, id)
    }

    fun getOcr(id: String, ticket: Long): Snapshot {
        check(ticket); checkId(id)
        return snapshot(request("GET", "api/v1/vision/tasks/$id", ticket), id)
    }

    /** No implicit task creation or upload retry: reconnect with GET before deciding to retry. */
    fun awaitOcr(id: String, ticket: Long, progress: (String) -> Unit = {}): Answer {
        var previous = ""
        while (true) {
            check(ticket)
            val state = getOcr(id, ticket)
            if (state.status != previous) {
                previous = state.status
                check(ticket); progress(state.status); check(ticket)
            }
            if (state.status == "succeeded") return requireNotNull(state.answer)
            rejectFailure(state)
            synchronized(lock) {
                val remaining = remainingNanos(ticket)
                lock.wait(minOf(1000L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))
            }
        }
    }

    /** The object route consumes actual images, never a reference to the latest OCR frame. */
    fun recognizeObject(id: String, question: String, images: List<ByteArray>, ticket: Long): Answer {
        check(ticket); checkId(id); checkQuestion(question)
        require(images.size in 1..4) { "物体识别需要 1 到 4 张真实照片" }
        val encoded = JSONArray()
        images.forEach { image ->
            check(ticket); checkJpeg(image)
            encoded.put("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image))
        }
        val body = JSONObject().put("task_id", id).put("question", question).put("images", encoded)
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 48 * 1024 * 1024) { "物体识别照片过大，请降低分辨率" }
        val root = request("POST", "api/v1/object-recognition", ticket, body)
        require(root.get("task_id") == id) { "物体识别响应属于其他任务" }
        rejectMock(root)
        require(root.optString("status") == "succeeded") { "物体识别未成功，不能作为答案" }
        if (root.has("question")) require(root.get("question") == question) { "物体识别回答对应其他问题" }
        return answer(root)
    }

    private fun snapshot(root: JSONObject, id: String): Snapshot {
        require(root.has("task_id") && root.get("task_id") == id) { "视觉响应缺少本次任务编号或属于其他任务" }
        validateOptionalBinding(root, id)
        val status = root.optString("status")
        require(status in STATUSES) { "未知视觉任务状态" }
        rejectMock(root)
        val error = root.optJSONObject("error")
        val code = (error?.optString("code") ?: root.optString("error_code")).takeIf { it.isNotBlank() }
        val message = (error?.optString("message") ?: root.optString("message")).takeIf { it.isNotBlank() }
        return Snapshot(id, status, if (status == "succeeded") answer(root) else null,
            code?.take(80), message?.let(::safeMessage), root)
    }

    private fun validateOptionalBinding(root: JSONObject, id: String) {
        if (root.has("task_id")) require(root.get("task_id") == id) { "视觉响应属于其他任务" }
        val expected = synchronized(lock) { tasks[id] } ?: return
        if (root.has("question")) require(root.optString("question") == expected.question) { "视觉响应对应其他问题" }
        if (root.has("expected_frames")) require(root.getInt("expected_frames") == expected.expectedFrames) { "视觉任务照片数量不匹配" }
        if (root.has("source_button")) require(root.getString("source_button") == "A") { "视觉任务按钮不匹配" }
        if (root.has("task_type")) require(root.getString("task_type") == "medicine_ocr") { "视觉任务类型不匹配" }
        if (root.has("record_id") && expected.recordId != null)
            require(root.getString("record_id") == expected.recordId) { "视觉任务记录编号不匹配" }
    }

    private fun validateAcknowledgement(root: JSONObject, id: String) {
        validateOptionalBinding(root, id)
        if (root.has("status")) {
            require(root.optString("status") in STATUSES) { "未知视觉任务状态" }
            if (root.has("task_id")) rejectFailure(snapshot(root, id))
            else require(root.optString("status") !in FAILURE_STATUSES) { "视觉任务处理失败，请查询本次任务" }
        }
    }

    private fun rejectFailure(state: Snapshot) {
        if (state.status in FAILURE_STATUSES) throw ApiException(200, state.errorCode ?: state.status,
            "视觉任务${if (state.status in setOf("timed_out", "expired")) "已超时" else "失败"}，请重新拍照发起新任务" +
                (state.errorMessage?.let { "：$it" } ?: ""))
    }

    private fun answer(root: JSONObject): Answer {
        val result = if (root.has("result")) {
            require(root.get("result") is JSONObject) { "视觉任务 result 必须是 JSON 对象" }
            root.getJSONObject("result")
        } else null
        // Android's JSONObject getString/optString may coerce null, numbers or objects to text.
        // Inspect the original JSON values so malformed replies are never displayed or spoken.
        val rootText = stringField(root, "answer")?.trim()
        val nestedText = result?.let { stringField(it, "answer") }?.trim()
        val rootEvidence = stringField(root, "evidence_text")?.trim()
        val nestedEvidence = result?.let { stringField(it, "evidence_text") }?.trim()
        val text = rootText ?: nestedText.orEmpty()
        val evidence = rootEvidence ?: nestedEvidence.orEmpty()
        require(text.length in 1..12000 && evidence.length <= 16000) { "视觉答案为空或过长" }
        if (rootText != null && nestedText != null)
            require(rootText == nestedText) { "视觉回答与任务结果不一致" }
        if (rootEvidence != null && nestedEvidence != null)
            require(rootEvidence == nestedEvidence) { "视觉识别原文与任务结果不一致" }
        // Missing review metadata remains conservative, including dates and medicine dosage.
        val rootReview = booleanField(root, "review_required")
        val nestedReview = result?.let { booleanField(it, "review_required") }
        val review = (rootReview ?: nestedReview ?: true) || nestedReview == true
        return Answer(text, evidence, review)
    }

    private fun stringField(root: JSONObject, name: String): String? {
        if (!root.has(name)) return null
        val value = root.get(name)
        require(value is String) { "视觉响应 $name 必须是字符串" }
        return value
    }

    private fun booleanField(root: JSONObject, name: String): Boolean? {
        if (!root.has(name)) return null
        val value = root.get(name)
        require(value is Boolean) { "视觉响应 $name 必须是布尔值" }
        return value
    }

    private fun request(method: String, path: String, ticket: Long, bytes: ByteArray? = null,
                        contentType: String = "application/json; charset=utf-8", headers: Map<String, String> = emptyMap(),
                        authenticated: Boolean = true): JSONObject {
        val remaining = remainingNanos(ticket)
        val base = if (config.base.path == "") java.net.URI(config.base.toString() + "/") else config.base
        val url = base.resolve(path).toString()
        val builder = Request.Builder().url(url)
        if (authenticated) builder.header("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, bytes?.toRequestBody(contentType.toMediaType()))
        val call = http.newCall(builder.build())
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        synchronized(lock) {
            check(ticket)
            check(active == null) { "视觉请求不可交错，请顺序上传照片" }
            active = call
        }
        IntegrationLog.event("MEDICINE_HTTP", "request", bytes = bytes?.size?.toLong(), url = url)
        try {
            call.execute().use { response ->
                val code = response.code
                IntegrationLog.event("MEDICINE_HTTP", "response", code = code, url = url)
                val body = response.body?.byteStream()?.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(ticket)
                        val count = it.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 1024 * 1024) { "视觉服务响应过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }.orEmpty()
                check(ticket)
                if (code !in 200..299) throw httpError(code, body)
                if (body.isBlank()) return JSONObject()
                return try { JSONObject(body) } catch (_: Exception) { throw IOException("视觉服务返回了无效 JSON") }
            }
        } catch (error: Exception) {
            // Cancellation/deadline wins over the socket error produced by Call.cancel().
            check(ticket)
            throw error
        } finally {
            synchronized(lock) { if (active === call) active = null }
        }
    }

    private fun httpError(status: Int, body: String): ApiException {
        val structured = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val rawCode = structured?.optString("code").orEmpty()
        val code = rawCode.takeIf { Regex("[A-Za-z0-9_-]{1,80}").matches(it) } ?: "http_$status"
        val hint = when {
            code == "model_unavailable" -> "视觉服务暂时不可用，请等待模型服务恢复"
            status == 401 || status == 403 -> "服务器访问令牌无效或没有权限，请检查设置"
            status == 404 -> "没有该任务或接口，请检查服务器地址和任务编号"
            status == 409 -> "任务或照片编号冲突，请创建新任务"
            status == 413 -> "照片过大，请压缩图片或降低分辨率"
            status == 415 -> "服务器只接受原始 JPEG，请检查上传格式"
            status == 422 -> "请求字段或任务编号格式不正确"
            status == 429 -> "视觉任务队列已满，请稍后重试"
            status == 503 -> "视觉服务暂时不可用或正在初始化，请稍后重试"
            status in 300..399 -> "视觉接口发生重定向，请填写最终服务器根地址"
            else -> "视觉请求失败，请检查网络和服务器"
        }
        return ApiException(status, code, "HTTP $status [$code]：$hint")
    }

    private fun safeMessage(value: String) = value.replace(token, "[令牌已隐藏]").take(500)

    private fun remainingNanos(ticket: Long): Long = synchronized(lock) {
        if (epoch != ticket) throw CancellationException("视觉任务已取消")
        val end = deadline ?: throw CancellationException("视觉任务尚未开始或已取消")
        val remaining = end - nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("视觉任务等待超过 180 秒，已停止")
        remaining
    }

    companion object {
        private const val TASK_TIMEOUT_NANOS = 180_000_000_000L
        private val STATUSES = setOf("awaiting_question", "awaiting_frame", "queued", "running", "succeeded", "failed", "timed_out", "expired")
        private val FAILURE_STATUSES = setOf("failed", "timed_out", "expired")
        // Existing MobileAgentB ships OkHttp. Besides cancellable write/read timeouts this also
        // supports PATCH on both Android and JVM, unlike java.net.HttpURLConnection on the JVM.
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        private fun checkId(value: String) {
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(value)) { "视觉编号限 1 到 64 个英文字母、数字、下划线和短横线" }
        }
        private fun checkQuestion(value: String) { require(value.isNotBlank() && value.length <= 1000) { "视觉问题为空或超过 1000 字" } }
        private fun checkJpeg(bytes: ByteArray) {
            require(bytes.size in 4..8 * 1024 * 1024 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "需要完整 JPEG 照片，单张最多 8 MiB" }
        }
        private fun rejectMock(root: JSONObject) {
            require(!root.optBoolean("mock") && root.optString("inference_backend") !in setOf("mock", "demo")) { "模拟结果不能作为真实识别答案" }
        }
    }
}
