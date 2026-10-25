package cn.seniorvoice.demo

import org.json.JSONObject

data class LocalTranscript(val text: String, val confidence: Double?) {
    // A model score is not a guarantee of accuracy. Do not launch an action on unclear audio.
    val canExecute get() = confidence?.let { it >= 0.65 } == true
}

/** Collect every completed Vosk segment plus the final segment; never execute partial hypotheses. */
class TranscriptCollector {
    private val text = StringBuilder()
    private var confidenceSum = 0.0
    private var words = 0

    fun append(json: String) {
        val result = JSONObject(json)
        val part = (result.opt("text") as? String).orEmpty().replace(Regex("\\s+"), "")
        if (part.isBlank()) return
        require(text.length + part.length <= 500) { "录音文字超过 500 字，请每次只说一条简短指令" }
        text.append(part)
        val items = result.optJSONArray("result") ?: return
        for (index in 0 until items.length()) {
            val confidence = items.optJSONObject(index)?.optDouble("conf", Double.NaN) ?: Double.NaN
            if (confidence.isFinite() && confidence in 0.0..1.0) { confidenceSum += confidence; words++ }
        }
    }

    fun finish(): LocalTranscript {
        require(text.isNotBlank()) { "没有识别出文字。请试听录音，确认有清晰普通话人声，并靠近硬件麦克风重新录制" }
        return LocalTranscript(text.toString(), if (words > 0) confidenceSum / words else null)
    }
}
