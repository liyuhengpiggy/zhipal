package cn.seniorvoice.demo.assist

import cn.seniorvoice.demo.DeviceEvent
import org.json.JSONObject

data class RecordingEnvelope(val eventId: String, val taskId: String, val button: String,
    val purpose: String, val promptId: String?, val size: Long?, val sessionId: String,
    val localGeneration: Long = -1, val format: String = "wav") {
    val expectedWavSize: Long? get() = size?.let { it + if (format == "pcm_s16le") 44 else 0 }
    fun correlation(): JSONObject = JSONObject().put("session_id", sessionId).put("task_id", taskId)
        .put("event_id", eventId).put("prompt_id", promptId ?: JSONObject.NULL).put("source_button", button)
    companion object {
        private val identifier = Regex("[A-Za-z0-9._:-]{1,96}")
        fun parse(event: DeviceEvent): RecordingEnvelope {
            val data = event.data
            val button = data.optString("source_button")
            val purpose = data.optString("purpose")
            val task = data.optString("task_id")
            val prompt = data.optString("prompt_id").takeIf { it.isNotBlank() }
            val session = data.optString("session_id")
            require(button in setOf("A", "B")) { "录音必须标明 source_button:A/B" }
            require(identifier.matches(task) && identifier.matches(session)) { "需要有效 task_id 和 session_id" }
            require(prompt == null || identifier.matches(prompt)) { "prompt_id 格式无效" }
            require(if (button == "A") purpose == "command" else purpose == "assist_reply" && prompt != null && identifier.matches(prompt)) {
                "A 使用 command；B 使用 assist_reply 并绑定当前 prompt_id"
            }
            val size = if (data.has("size")) data.optLong("size", -1) else null
            val format = data.optString("format", "wav")
            require(format in setOf("wav", "pcm_s16le")) { "仅支持 WAV 或 pcm_s16le" }
            val minimum = if (format == "wav") 44L else 2L
            val maximum = 10L * 1024 * 1024 - if (format == "pcm_s16le") 44 else 0
            require(size == null || size in minimum..maximum) { "录音大小无效" }
            return RecordingEnvelope(event.id, task, button, purpose, prompt, size, session, format = format)
        }
    }
}
