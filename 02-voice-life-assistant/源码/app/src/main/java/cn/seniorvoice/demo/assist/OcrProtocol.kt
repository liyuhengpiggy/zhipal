package cn.seniorvoice.demo.assist

import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Contract of the teammate's task API. No phone action is derived from OCR output. */
object OcrProtocol {
    private val id = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    fun newId() = "v_" + UUID.randomUUID().toString().replace("-", "")
    fun checkId(value: String) { require(id.matches(value)) { "OCR 任务编号格式错误" } }

    fun endpoint(value: String, allowLanHttp: Boolean, debugBuild: Boolean = false): URI {
        val uri = URI(value.trim().trimEnd('/') + "/")
        val host = uri.host.orEmpty()
        val parts = host.split('.').map { it.toIntOrNull() }
        val privateV4 = parts.size == 4 && parts.all { it != null && it in 0..255 } &&
            (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1]!! in 16..31))
        require(host.isNotBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.port in -1..65535 && uri.port != 0 && uri.normalize().path == uri.path &&
            !uri.path.trimEnd('/').endsWith("/docs") && !uri.path.trimEnd('/').endsWith("/openapi.json") &&
            (uri.scheme == "https" || (uri.scheme == "http" && debugBuild && allowLanHttp && privateV4))) {
            "请填 HTTPS 服务地址；仅调试版勾选联调后可使用内网 IPv4 HTTP 地址"
        }
        return uri
    }

    fun normalizeQuestion(text: String) = text.trim().replace("读一读", "读一下").replace("念一下", "读一下")

    fun supports(question: String): Boolean {
        val q = normalizeQuestion(question).lowercase()
        return listOf("生产日期", "制造日期", "出厂日期", "有效期", "保质期", "失效", "到期", "过期", "批号",
            "使用说明", "用法", "怎么用", "如何使用", "怎么吃", "用量", "什么药", "药名", "药品名称", "厂家", "生产企业",
            "写了什么", "文字", "读一下", "mfg", "manufacturing date", "manufacture date", "manufacturer", "expir", "shelf life", "batch", "lot", "usage", "read", "text", "ocr").any(q::contains)
    }

    fun validateSnapshot(json: JSONObject, task: String, question: String) {
        require(json.getString("task_id") == task && json.getString("question") == question &&
            json.getInt("expected_frames") == 1) { "OCR 响应与本次问题或任务不匹配" }
        require(json.getString("status") in setOf("awaiting_frame", "queued", "running", "succeeded", "failed", "timed_out")) {
            "OCR 返回未知任务状态"
        }
    }

    data class Answer(val type: String, val answer: String, val evidence: String, val sha256: String) {
        fun display(): String = when (type) {
            "field_candidate" -> "$answer\n请核对原图，识别候选不能代替包装原文。"
            "conflicting_candidates" -> "识别到多个候选，尚不能确定：\n$answer\n请补拍对应位置，不自动选择。"
            "not_found" -> "未识别到对应文字，请靠近并拍清楚标签后重新发起。"
            "unsupported" -> "这套服务暂不支持该问题，仅支持文字和药盒字段识别。"
            else -> answer
        } + if (evidence.isNotBlank() && type != "unsupported") "\n\n识别原文：\n$evidence" else ""
    }

    fun answer(json: JSONObject, task: String, question: String, expectedSha: String? = null): Answer {
        validateSnapshot(json, task, question)
        check(json.getString("status") == "succeeded") { "OCR 任务尚未成功" }
        val result = json.getJSONObject("result")
        val type = result.getString("answer_type")
        require(type in setOf("ocr_text", "field_candidate", "conflicting_candidates", "not_found", "unsupported")) { "未知 OCR 答案类型" }
        val answer = result.getString("answer").trim()
        val evidence = result.getString("evidence_text").trim()
        require(answer.isNotBlank() && json.getString("answer").trim() == answer &&
            json.getString("evidence_text").trim() == evidence) { "OCR 回答与识别证据不一致" }
        val frames = json.getJSONArray("frames")
        val resultFrames = result.getJSONArray("frames")
        require(frames.length() == 1 && resultFrames.length() == 1) { "OCR 照片数量不匹配" }
        val frame = frames.getJSONObject(0); val actual = resultFrames.getJSONObject(0)
        val sha = frame.getString("sha256")
        require(frame.getString("frame_id") == "f0" && actual.getString("frame_id") == "f0" &&
            Regex("[0-9a-f]{64}").matches(sha) && actual.getString("sha256") == sha &&
            (expectedSha == null || expectedSha == sha)) { "OCR 结果不是本次上传的照片" }
        if (type in setOf("field_candidate", "conflicting_candidates", "ocr_text")) {
            require(evidence.isNotBlank() && actual.getString("ocr_text").isNotBlank()) { "OCR 缺少可核对的识别原文" }
        }
        if (type in setOf("field_candidate", "conflicting_candidates")) {
            require(result.getBoolean("review_required")) { "字段候选必须标记人工核对" }
            val candidates = result.getJSONObject("fields").getJSONArray(result.getString("intent"))
            require(if (type == "field_candidate") candidates.length() == 1 else candidates.length() > 1) { "OCR 候选数量与答案类型不一致" }
            for (i in 0 until candidates.length()) {
                val candidate = candidates.getJSONObject(i)
                require(candidate.getString("value").isNotBlank()) { "OCR 候选值为空" }
                val proof = candidate.getJSONArray("evidence")
                require(proof.length() > 0) { "OCR 候选没有证据" }
                for (j in 0 until proof.length()) require(proof.getJSONObject(j).getString("frame_id") == "f0" &&
                    proof.getJSONObject(j).getString("text").isNotBlank()) { "OCR 候选证据属于其他照片" }
            }
        }
        return Answer(type, answer.take(12000), evidence.take(12000), sha)
    }
}
