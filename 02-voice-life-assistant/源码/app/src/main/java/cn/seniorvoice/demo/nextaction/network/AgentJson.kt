package cn.seniorvoice.demo.nextaction.network

import java.util.Base64
import cn.seniorvoice.demo.nextaction.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned wire contract, intentionally isolated from Android observation and execution. */
object AgentJson {
    fun encode(request: ObservationRequest): String {
        val capture = request.captured
        val snapshot = capture.snapshot
        return JSONObject().put("schema_version", 1).put("request_id", request.requestId)
            .put("capture_id", snapshot.captureId).put("package_name", snapshot.packageName)
            .put("window_id", snapshot.windowId).put("captured_at_ms", snapshot.capturedAtMs)
            .put("ui_fingerprint", snapshot.uiFingerprint)
            .put("screenshot", JSONObject().put("mime_type", "image/jpeg")
                .put("width", capture.screenshot.width).put("height", capture.screenshot.height)
                .put("jpeg_base64", Base64.getEncoder().encodeToString(capture.screenshot.jpeg)))
            .put("ui_nodes", JSONArray().apply { snapshot.nodes.forEach { n -> put(JSONObject()
                .put("node_key", n.nodeKey).put("class_name", n.className).put("view_id", n.viewId ?: JSONObject.NULL)
                .put("label", n.label).put("bounds", bounds(n.bounds)).put("visible", n.visible).put("enabled", n.enabled)
                .put("clickable", n.clickable).put("scrollable", n.scrollable)) } })
            .put("history", JSONArray().apply { capture.history.takeLast(20).forEach { e -> put(JSONObject()
                .put("sequence", e.sequence).put("occurred_at_ms", e.occurredAtMs).put("event_type", e.eventType)
                .put("package_name", e.packageName).put("window_id", e.windowId).put("label", e.label)) } })
            .put("candidates", JSONArray().apply { request.candidates.forEach { c -> put(JSONObject()
                .put("candidate_id", c.candidateId).put("action", c.action.name).put("target_label", c.targetLabel)
                .put("node_key", c.nodeKey ?: JSONObject.NULL).put("class_name", c.className ?: JSONObject.NULL)
                .put("view_id", c.viewId ?: JSONObject.NULL).put("bounds", c.bounds?.let(::bounds) ?: JSONObject.NULL)) } })
            .toString()
    }

    private fun bounds(b: Bounds) = JSONObject().put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)

    fun decode(body: String, request: ObservationRequest): PredictionResponse {
        require(body.toByteArray(Charsets.UTF_8).size <= 32768) { "预测响应过大" }
        val root = JSONObject(body)
        exactKeys(root, setOf("schema_version", "request_id", "capture_id", "decision"))
        require(root.get("schema_version") is Int && root.getInt("schema_version") == 1) { "预测协议版本不匹配" }
        val requestId = strictString(root, "request_id")
        val captureId = strictString(root, "capture_id")
        require(requestId == request.requestId && captureId == request.captured.snapshot.captureId) { "预测编号不匹配" }
        val choice = root.getJSONObject("decision")
        exactKeys(choice, setOf("action", "candidate_id", "confidence", "reason"))
        val action = ActionType.valueOf(strictString(choice, "action"))
        val candidateId = strictString(choice, "candidate_id")
        val confidenceValue = choice.get("confidence")
        require(confidenceValue is Number) { "模型分值必须为数值" }
        val confidence = confidenceValue.toDouble()
        require(confidence.isFinite() && confidence in 0.0..1.0) { "模型分值越界" }
        val reason = strictString(choice, "reason")
        require(reason.length in 1..300) { "预测理由长度不合法" }
        require(request.candidates.count { it.candidateId == candidateId && it.action == action } == 1) { "模型选择了候选集合以外的动作" }
        return PredictionResponse(1, requestId, captureId, Decision(action, candidateId, confidence, reason))
    }
    private fun strictString(json: JSONObject, key: String): String {
        val value = json.get(key); require(value is String) { "$key 必须为字符串" }; return value
    }
    private fun exactKeys(json: JSONObject, keys: Set<String>) { require(json.keys().asSequence().toSet() == keys) { "预测字段不符合协议" } }
}
