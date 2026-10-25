package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant

/** Allowlisted operational evidence only; never log transcripts, HTTP headers, bodies or pixels. */
object IntegrationLog {
    private var file: File? = null
    @Synchronized fun initialize(directory: File) { file = File(directory, "integration.jsonl") }
    @Synchronized fun event(component: String, stage: String, ids: Map<String, String?> = emptyMap(),
                            code: Int? = null, bytes: Long? = null, url: String? = null,
                            elapsedMs: Long? = null, firstBytesHex: String? = null, bytesPerSecond: Long? = null) {
        runCatching {
            val target = file ?: return
            if (target.length() > 1024 * 1024) {
                val previous = File(target.parentFile, "integration.previous.jsonl")
                if (previous.exists()) previous.delete()
                if (!target.renameTo(previous)) return
            }
            val row = JSONObject().put("time", Instant.now().toString()).put("component", component.take(40)).put("stage", stage.take(64))
            for ((key, value) in ids) if (key in setOf("session_id", "task_id", "event_id", "prompt_id", "button", "mode", "state") &&
                value != null && Regex("[A-Za-z0-9._:-]{1,160}").matches(value)) row.put(key, value)
            if (code != null) row.put("http_status", code)
            if (bytes != null && bytes >= 0) row.put("bytes", bytes)
            if (elapsedMs != null && elapsedMs >= 0) row.put("elapsed_ms", elapsedMs)
            if (bytesPerSecond != null && bytesPerSecond >= 0) row.put("bytes_per_second", bytesPerSecond)
            // Only protocol headers may be retained, never the beginning of JSON/user speech.
            if (firstBytesHex != null && Regex("(?:[0-9A-F]{2} ){7}[0-9A-F]{2}").matches(firstBytesHex) &&
                (firstBytesHex.startsWith("52 49 46 46 ") || firstBytesHex.startsWith("A5 5A "))) {
                row.put("first_8_bytes_hex", firstBytesHex)
            }
            if (url != null) runCatching {
                val uri = URI(url)
                row.put("origin", URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString())
                // Only the known API route is useful; arbitrary signed paths can carry secrets.
                row.put("route", when {
                    uri.path.endsWith("/chat/completions") -> "chat/completions"
                    uri.path.contains("/api/v1/vision/tasks") -> "api/v1/vision/tasks"
                    uri.path.endsWith("/api/v1/object-recognition") -> "api/v1/object-recognition"
                    uri.path.endsWith("/health") -> "health"
                    uri.path.endsWith("/vision/analyze") -> "vision/analyze"
                    uri.path.endsWith("/analyze") -> "analyze"
                    else -> "configured_endpoint"
                })
            }
            target.appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }
    @Synchronized fun read(): String = runCatching {
        val current = file ?: return "尚无日志"
        val previous = File(current.parentFile, "integration.previous.jsonl")
        (if (previous.exists()) previous.readText(Charsets.UTF_8) else "") +
            (if (current.exists()) current.readText(Charsets.UTF_8) else "尚无日志")
    }.getOrDefault("读取日志失败")
}
