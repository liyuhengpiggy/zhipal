package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors

/** One task on one worker. The caller also checks its A generation before accepting callbacks. */
class OcrWorkflow(private val post: (() -> Unit) -> Unit) {
    private val client = OcrApiClient()
    private val worker = Executors.newSingleThreadExecutor()
    fun cancel() = client.cancelAll()
    fun close() { cancel(); worker.shutdownNow() }

    fun start(base: URI, token: String, id: String, question: String, jpeg: ByteArray?,
              expectedSha: String?, resume: Boolean, created: () -> Unit,
              progress: (String) -> Unit, finished: (Result<OcrProtocol.Answer>) -> Unit) {
        cancel()
        val ticket = client.ticket()
        fun deliver(action: () -> Unit) = post { if (runCatching { client.check(ticket) }.isSuccess) action() }
        worker.execute {
            val outcome = runCatching {
                client.check(ticket)
                var snapshot: JSONObject
                if (resume) snapshot = client.get(base, token, id, question, ticket)
                else {
                    deliver { progress("正在检查模型是否就绪") }
                    client.ready(base, token, ticket)
                    deliver { progress("模型已就绪，正在创建本次任务") }
                    snapshot = client.create(base, token, id, question, ticket)
                    if (jpeg != null) {
                        deliver { progress("任务已创建，正在上传本次 JPEG") }
                        snapshot = client.upload(base, token, id, question, jpeg, ticket)
                    }
                    else if (snapshot.getString("status") == "awaiting_frame") deliver(created)
                }
                val deadline = System.nanoTime() + 240_000_000_000L
                var previous = ""
                while (true) {
                    client.check(ticket)
                    val status = snapshot.getString("status")
                    when (status) {
                        "succeeded" -> return@runCatching OcrProtocol.answer(snapshot, id, question, expectedSha)
                        "failed", "timed_out" -> error("OCR ${snapshot.optString("error_code", status)}：${snapshot.optString("message", "请重新拍摄")}")
                    }
                    check(System.nanoTime() < deadline) { "OCR 等待已达 240 秒；可按原编号查询上次任务" }
                    if (status != previous) {
                        previous = status
                        deliver { progress(when (status) {
                            "awaiting_frame" -> "等待本次照片上传"
                            "queued" -> "照片已收到，正在排队"
                            else -> "正在识别文字和药盒字段"
                        }) }
                    }
                    Thread.sleep(1000)
                    snapshot = client.get(base, token, id, question, ticket)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            deliver { finished(outcome) }
        }
    }
}
