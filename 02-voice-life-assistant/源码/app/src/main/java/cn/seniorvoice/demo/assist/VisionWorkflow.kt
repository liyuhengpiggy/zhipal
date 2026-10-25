package cn.seniorvoice.demo.assist

import java.util.concurrent.Executors

/** One generation owns create, capture and polling; cancellation invalidates every pending callback. */
class VisionWorkflow(private val post: (() -> Unit) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private var active: VisionApiClient? = null
    @Synchronized fun cancel() { active?.cancel(); active = null }
    fun close() { cancel(); worker.shutdownNow() }
    @Synchronized private fun current(client: VisionApiClient) = active === client
    fun start(config: ServerConfig, token: String, id: String, question: String, mode: VisionMode, jpeg: ByteArray? = null,
              created: (VisionApiClient.Upload) -> Unit, progress: (String) -> Unit, finished: (Result<VisionApiClient.Answer>) -> Unit) {
        cancel()
        val client = VisionApiClient(config, token)
        synchronized(this) { active = client }
        val ticket = client.ticket()
        fun deliver(action: () -> Unit) = post { if (current(client) && runCatching { client.check(ticket) }.isSuccess) action() }
        worker.execute {
            val outcome = runCatching {
                var state = client.create(id, question, mode, ticket)
                require(state.getString("status") == "awaiting_frame") { "新任务应等待本次照片，不能直接复用历史结果" }
                val upload = client.uploadTicket(state)
                if (jpeg != null) client.upload(upload, jpeg, ticket) else deliver { created(upload) }
                val deadline = System.nanoTime() + 120_000_000_000L
                var previous = ""
                while (true) {
                    client.check(ticket)
                    val status = state.getString("status")
                    when (status) {
                        "succeeded" -> return@runCatching VisionApiClient.answer(state, id, question, mode, jpeg?.let(OcrApiClient::sha256))
                        "failed", "timed_out", "expired" -> error("视觉任务 $status；请重新发起拍照")
                    }
                    check(System.nanoTime() < deadline) { "视觉任务等待超过 120 秒，已停止" }
                    check(java.time.Instant.now().isBefore(upload.expiresAt) || status != "awaiting_frame") { "照片上传凭证已过期，请重新拍摄" }
                    if (previous != status) { previous = status; deliver { progress(status) } }
                    Thread.sleep(1000)
                    state = client.get(id, question, mode, ticket)
                }
                @Suppress("UNREACHABLE_CODE") error("Unreachable")
            }
            deliver { finished(outcome) }
        }
    }
}
