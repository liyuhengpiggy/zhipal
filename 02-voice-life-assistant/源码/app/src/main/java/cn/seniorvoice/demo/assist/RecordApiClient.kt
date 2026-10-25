package cn.seniorvoice.demo.assist

import cn.seniorvoice.demo.BackendSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Durable retry is owned by BackendSync; a write only succeeds after an ID-matching acknowledgement. */
class RecordApiClient {
    fun post(config: BackendSettings, id: String, payload: String) {
        val uri = URI(config.endpoint)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "后端记录接口地址不正确" }
        require(!config.token.contains('\r') && !config.token.contains('\n')) { "令牌不能包含换行" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        IntegrationLog.event("RECORD_HTTP", "request", mapOf("event_id" to id), url = uri.toString())
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000; connection.readTimeout = 4000; connection.doOutput = true
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            IntegrationLog.event("RECORD_HTTP", "response", code = code, url = uri.toString())
            check(code in 200..299) { "后端返回 HTTP $code" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder(); val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer); if (count < 0) break
                    out.append(buffer, 0, count); check(out.length <= 16384) { "后端响应过长" }
                }
                out.toString()
            }
            val ack = JSONObject(body)
            check(ack.optBoolean("ok") && ack.optString("record_id") == id) { "后端未按协议确认 record_id" }
        } finally { connection.disconnect() }
    }
}
