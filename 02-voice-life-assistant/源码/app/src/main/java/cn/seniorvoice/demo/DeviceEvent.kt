package cn.seniorvoice.demo

import org.json.JSONObject

data class DeviceEvent(val id: String, val type: String, val text: String, val data: JSONObject) {
    companion object {
        fun parse(line: String): DeviceEvent {
            val json = JSONObject(line)
            require(json.optInt("v", 0) in 1..2) { "消息必须包含 v:1 或 v:2" }
            val data = json.optJSONObject("data") ?: JSONObject()
            val id = json.optString("id").ifBlank { json.optString("event_id").ifBlank { data.optString("event_id") } }
            require(Regex("[A-Za-z0-9._:-]{1,96}").matches(id)) { "消息必须有唯一 id（字母、数字、点、横线或冒号）" }
            val type = json.optString("type")
            require(type in setOf("ping", "command", "complete", "image_ready", "audio_ready", "audio_start", "audio_chunk", "audio_end", "recording_meta", "button_start", "button_end", "cancel")) { "不支持的消息类型：$type" }
            val text = json.optString("text")
            require(text.length <= 300) { "指令文字过长" }
            require(type != "command" || text.isNotBlank()) { "command 消息需要 text" }
            if (type == "audio_chunk") {
                require((json.optJSONObject("data")?.optString("base64")?.length ?: 0) in 1..BleAudioTransfer.MAX_ENCODED_CHUNK) { "audio_chunk 的 base64 为空或超过 3 MiB，请分块发送" }
            }
            return DeviceEvent(id, type, text, data)
        }
    }
}
