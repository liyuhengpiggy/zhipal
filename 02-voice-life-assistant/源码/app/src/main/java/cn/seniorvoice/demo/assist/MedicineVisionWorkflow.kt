package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** One active A task owns its capture, server requests and at most one returned image. */
class MedicineVisionWorkflow(private val post: (() -> Unit) -> Unit) {
    data class Capture(val taskId: String, val frameId: String = "f0", val uploadUrl: String?, val headers: JSONObject)

    private class Run(val client: MedicineVisionApiClient, val ticket: Long, val id: String,
                      val mode: VisionMode, initialFrame: ByteArray?) {
        var frame: ByteArray? = initialFrame
        var frameAccepted = initialFrame != null
        var acceptsHardwareFrame = false
        var captureDelivered = false
        var captureFailure: Throwable? = null
        var completed = false
    }

    private val lock = java.lang.Object()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "medicine-vision-workflow").apply { isDaemon = true }
    }
    private var active: Run? = null
    private var closed = false

    fun cancel() = synchronized(lock) { cancelLocked() }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            cancelLocked()
            worker.shutdownNow()
        }
    }

    /** Only the active task may submit one JPEG; duplicate, stale and cross-task images are ignored. */
    fun provideFrame(id: String, jpeg: ByteArray): Boolean = synchronized(lock) {
        val run = active ?: return@synchronized false
        if (run.id != id || !run.acceptsHardwareFrame || run.frameAccepted || run.completed ||
            runCatching { run.client.check(run.ticket) }.isFailure) return@synchronized false
        checkJpeg(jpeg)
        run.frame = jpeg.copyOf()
        run.frameAccepted = true
        lock.notifyAll()
        true
    }

    fun start(config: ServerConfig, token: String, id: String, question: String, mode: VisionMode,
              jpeg: ByteArray? = null, recordId: String? = null, resume: Boolean = false,
              created: (Capture) -> Unit, progress: (String) -> Unit,
              finished: (Result<MedicineVisionApiClient.Answer>) -> Unit) = synchronized(lock) {
        check(!closed) { "视觉流程已关闭" }
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id)) { "视觉任务编号无效" }
        require(question.isNotBlank() && question.length <= 1000) { "视觉问题为空或过长" }
        if (jpeg != null) checkJpeg(jpeg)
        val client = MedicineVisionApiClient(config, token)
        cancelLocked()
        val run = Run(client, client.ticket(), id, mode, jpeg?.copyOf())
        active = run
        worker.execute {
            val outcome = runCatching {
                client.check(run.ticket)
                require(!resume || (mode == VisionMode.OCR && jpeg == null)) { "仅 OCR 支持按原任务编号恢复查询，恢复时不能重新提交照片" }
                report(run, progress, "检查视觉服务状态")
                val health = client.requireReady(run.ticket, if (mode == VisionMode.OBJECT) "object_recognition" else "ocr")
                if (mode == VisionMode.COMBINED && "object_recognition" !in health.capabilities)
                    throw MedicineVisionApiClient.ApiException(503, "capability_unavailable", "视觉服务尚未同时提供文字与物体识别")

                if (resume) {
                    report(run, progress, "恢复查询本次 OCR 任务")
                    val state = client.getOcr(id, run.ticket)
                    validateResume(state, question, recordId)
                    checkFailure(state)
                    state.answer ?: client.awaitOcr(id, run.ticket) { report(run, progress, phase(it)) }
                } else if (mode == VisionMode.OBJECT) {
                    val image = if (jpeg != null) takeFrame(run) else {
                        capture(run, Capture(id, uploadUrl = null, headers = JSONObject()), created, progress)
                        awaitFrame(run, progress)
                    }
                    report(run, progress, "已收到本次照片，正在识别物体")
                    client.recognizeObject(id, question, listOf(image), run.ticket)
                } else {
                    report(run, progress, "创建本次 OCR 任务")
                    var state = client.createOcr(id, question, run.ticket, recordId)
                    checkFailure(state)
                    require(state.status != "succeeded") { "新建任务返回了已有答案，请更换任务编号重新拍摄" }
                    if (state.status == "awaiting_question") {
                        client.patchQuestion(id, question, run.ticket)
                        state = client.getOcr(id, run.ticket)
                        checkFailure(state)
                        require(state.status != "succeeded") { "任务尚未上传本次照片，不能使用已有答案" }
                    }

                    var freshCapture = false
                    var objectImage: ByteArray? = null
                    if (state.status == "awaiting_frame") {
                        if (jpeg != null) {
                            val image = takeFrame(run)
                            report(run, progress, "正在上传本次照片")
                            client.uploadOcr(id, "f0", image, run.ticket)
                            freshCapture = true
                            if (mode == VisionMode.COMBINED) objectImage = image
                        } else {
                            val base = config.base.toString().trimEnd('/')
                            val headers = JSONObject().put("Authorization", "Bearer $token")
                                .put("X-Task-Id", id).put("X-Frame-Id", "f0").put("Content-Type", "image/jpeg")
                            capture(run, Capture(id, uploadUrl = "$base/api/v1/vision/tasks/$id/frames/f0", headers = headers), created, progress)
                            freshCapture = true
                            if (mode == VisionMode.COMBINED) objectImage = awaitFrame(run, progress)
                        }
                    }
                    val ocr = client.awaitOcr(id, run.ticket) { report(run, progress, phase(it)) }
                    require(freshCapture) { "本次尚未上传或请求拍摄照片，不能复用已有 OCR 答案；请重新拍摄" }
                    if (mode == VisionMode.OCR) ocr else {
                        val image = requireNotNull(objectImage) { "缺少本次照片，无法完成物体识别" }
                        report(run, progress, "文字已识别，正在使用同一张照片识别物体")
                        val objects = client.recognizeObject(id, question, listOf(image), run.ticket)
                        combine(ocr, objects)
                    }
                }
            }
            synchronized(lock) {
                run.completed = true
                run.frame = null
                run.acceptsHardwareFrame = false
                lock.notifyAll()
            }
            // A timeout is a deliverable failure. Rechecking only the deadline here would hide it
            // and leave the UI waiting forever; generation and identity still reject cancellation.
            post {
                synchronized(lock) {
                    if (active !== run || client.ticket() != run.ticket) return@synchronized
                    val deliverable = if (outcome.isSuccess) runCatching { client.check(run.ticket); outcome.getOrThrow() } else outcome
                    try { finished(deliverable) } finally { if (active === run) active = null }
                }
            }
        }
    }

    private fun cancelLocked() {
        val run = active
        active = null
        run?.frame = null
        run?.acceptsHardwareFrame = false
        lock.notifyAll()
        run?.client?.cancel()
    }

    private fun report(run: Run, callback: (String) -> Unit, message: String) {
        post {
            synchronized(lock) {
                if (active === run && !run.completed && runCatching { run.client.check(run.ticket) }.isSuccess)
                    callback(message)
            }
        }
    }

    private fun capture(run: Run, request: Capture, callback: (Capture) -> Unit, progress: (String) -> Unit) {
        synchronized(lock) {
            checkActive(run)
            run.acceptsHardwareFrame = run.mode != VisionMode.OCR
        }
        report(run, progress, "等待眼镜拍照并上传本次照片")
        post {
            synchronized(lock) {
                if (active !== run || run.completed || runCatching { run.client.check(run.ticket) }.isFailure) return@synchronized
                try { callback(request); run.captureDelivered = true }
                catch (error: Throwable) { run.captureFailure = error }
                finally { lock.notifyAll() }
            }
        }
        synchronized(lock) {
            while (!run.captureDelivered) {
                checkActive(run)
                run.captureFailure?.let { throw it }
                lock.wait(500)
            }
            checkActive(run)
        }
    }

    private fun awaitFrame(run: Run, progress: (String) -> Unit): ByteArray {
        report(run, progress, "等待本次照片数据以完成物体识别")
        synchronized(lock) {
            while (run.frame == null) {
                checkActive(run)
                lock.wait(500)
            }
            checkActive(run)
            return takeFrame(run)
        }
    }

    private fun takeFrame(run: Run): ByteArray = synchronized(lock) {
        checkActive(run)
        requireNotNull(run.frame) { "缺少本次照片" }.also { run.frame = null }
    }

    private fun checkActive(run: Run) {
        if (active !== run || closed) throw CancellationException("视觉任务已取消")
        run.client.check(run.ticket)
    }

    private fun validateResume(state: MedicineVisionApiClient.Snapshot, question: String, recordId: String?) {
        if (state.raw.has("question")) require(state.raw.getString("question") == question) { "恢复任务对应其他问题" }
        if (state.raw.has("source_button")) require(state.raw.getString("source_button") == "A") { "恢复任务不属于 A" }
        if (state.raw.has("task_type")) require(state.raw.getString("task_type") == "medicine_ocr") { "恢复任务不是 OCR 任务" }
        if (state.raw.has("record_id") && recordId != null)
            require(state.raw.getString("record_id") == recordId) { "恢复任务对应其他记录" }
    }

    private fun checkFailure(state: MedicineVisionApiClient.Snapshot) {
        if (state.status in setOf("failed", "timed_out", "expired"))
            throw IOException("本次视觉任务${state.status}，请重新拍摄" + (state.errorCode?.let { " [$it]" } ?: ""))
    }

    private fun phase(status: String) = when (status) {
        "awaiting_question" -> "等待本次任务的问题"
        "awaiting_frame" -> "等待眼镜上传本次照片"
        "queued" -> "照片已收到，等待服务器识别"
        "running" -> "服务器正在识别本次照片"
        "succeeded" -> "识别完成，正在整理反馈"
        else -> "视觉任务状态：$status"
    }

    private fun combine(ocr: MedicineVisionApiClient.Answer, objects: MedicineVisionApiClient.Answer) =
        MedicineVisionApiClient.Answer("文字识别：\n${ocr.text}\n\n物体识别：\n${objects.text}",
            listOf(ocr.evidence.takeIf { it.isNotBlank() }?.let { "文字识别原文：\n$it" },
                objects.evidence.takeIf { it.isNotBlank() }?.let { "物体识别说明：\n$it" }).filterNotNull().joinToString("\n\n"),
            ocr.reviewRequired || objects.reviewRequired)

    private fun checkJpeg(bytes: ByteArray) {
        require(bytes.size in 4..8 * 1024 * 1024 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "需要完整 JPEG，单张最多 8 MiB" }
    }
}
