package cn.seniorvoice.demo.nextaction

import cn.seniorvoice.demo.DeviceEvent
import cn.seniorvoice.demo.nextaction.domain.*
import cn.seniorvoice.demo.nextaction.network.AgentJson
import cn.seniorvoice.demo.nextaction.observation.InteractionHistory
import cn.seniorvoice.demo.nextaction.observation.PrivacyFilter
import cn.seniorvoice.demo.nextaction.trigger.BleTriggerAdapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentProtocolTest {
    private fun snapshot(nodes: List<UiNodeSnapshot> = listOf(node())) = UiSnapshot("capture", "com.example.test", 2, 1L, "a".repeat(64), 0, 1080, 1920, nodes)
    private fun node() = UiNodeSnapshot("0", "android.widget.Button", "test:id/next", "下一页", Bounds(0, 0, 100, 100), true, true, true, false, false, false)
    private fun request(): ObservationRequest {
        val snapshot = snapshot()
        return ObservationRequest("request", CapturedObservation(snapshot, ScreenImage(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), 1, 1), emptyList()), CandidateGenerator().generate(snapshot))
    }
    private fun response(request: ObservationRequest) = JSONObject().put("schema_version", 1).put("request_id", request.requestId)
        .put("capture_id", request.captured.snapshot.captureId).put("decision", JSONObject().put("action", "NO_OP").put("candidate_id", "no_op").put("confidence", 0.9).put("reason", "信息不足"))

    @Test fun versionedRequestContainsImageHistoryAndRealCandidates() {
        val encoded = JSONObject(AgentJson.encode(request()))
        assertEquals(1, encoded.getInt("schema_version"))
        assertEquals("image/jpeg", encoded.getJSONObject("screenshot").getString("mime_type"))
        assertEquals("/9j/2Q==", encoded.getJSONObject("screenshot").getString("jpeg_base64"))
        assertEquals(0, encoded.getJSONArray("history").length())
        assertEquals("0", encoded.getJSONArray("ui_nodes").getJSONObject(0).getString("node_key"))
        assertEquals(3, encoded.getJSONArray("candidates").length())
    }
    @Test fun structuredNoOpRoundTrips() {
        val request = request(); val result = AgentJson.decode(response(request).toString(), request)
        assertEquals(ActionType.NO_OP, result.decision.action)
        assertEquals(request.requestId, result.requestId)
    }
    @Test fun rejectsWrongIdentifiersUnknownActionsAndUnknownCandidates() {
        val request = request()
        for (field in listOf("request_id", "capture_id")) {
            assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(response(request).put(field, "other").toString(), request) }
        }
        val unknown = response(request); unknown.getJSONObject("decision").put("candidate_id", "invented")
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(unknown.toString(), request) }
        val illegal = response(request); illegal.getJSONObject("decision").put("action", "TYPE_TEXT")
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(illegal.toString(), request) }
        val mismatch = response(request); mismatch.getJSONObject("decision").put("action", "BACK")
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(mismatch.toString(), request) }
    }
    @Test fun rejectsAdditionalFieldsAndCoercedScalars() {
        val request = request()
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(response(request).put("extra", true).toString(), request) }
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(response(request).put("schema_version", "1").toString(), request) }
        val number = response(request); number.getJSONObject("decision").put("confidence", "0.9")
        assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(number.toString(), request) }
    }
    @Test fun rejectsOversizedOrEmptyReasons() {
        val request = request()
        for (reason in listOf("", "a".repeat(301))) {
            val value = response(request); value.getJSONObject("decision").put("reason", reason)
            assertThrows(IllegalArgumentException::class.java) { AgentJson.decode(value.toString(), request) }
        }
    }
    @Test fun historyIsBoundedDeduplicatedAndHasNoClickedText() {
        val history = InteractionHistory()
        history.add(0, "CLICK", "com.test", 1); history.add(100, "CLICK", "com.test", 1)
        assertEquals(1, history.snapshot().size)
        for (index in 1..30) history.add(index * 500L, "CLICK", "com.test", 1)
        assertEquals(20, history.snapshot().size)
        assertTrue(history.snapshot().all { it.label.isEmpty() })
        assertEquals(31L, history.snapshot().last().sequence)
        history.clear(); assertTrue(history.snapshot().isEmpty())
    }
    @Test fun privacyRejectsInputsPasswordsPersonalTextAndSensitiveApps() {
        assertNotNull(PrivacyFilter.reason(snapshot(listOf(node().copy(editable = true, label = "")))))
        assertNotNull(PrivacyFilter.reason(snapshot(listOf(node().copy(password = true, label = "")))))
        for (label in listOf("请支付", "手机号13812345678", "mail@example.com", "验证码"))
            assertNotNull(label, PrivacyFilter.reason(snapshot(listOf(node().copy(label = label)))))
        assertNotNull(PrivacyFilter.reason(snapshot().copy(packageName = "com.eg.android.AlipayGphone")))
        assertNull(PrivacyFilter.reason(snapshot()))
    }
    @Test fun completeReusesBleJsonWithoutChangingCommandOrWavTypes() {
        val complete = DeviceEvent.parse("{\"v\":1,\"type\":\"complete\",\"id\":\"step-1\"}")
        assertTrue(BleTriggerAdapter.matches(complete))
        val text = DeviceEvent.parse("{\"v\":1,\"type\":\"command\",\"id\":\"step-2\",\"text\":\"COMPLETE\"}")
        assertTrue(BleTriggerAdapter.matches(text))
        val normal = DeviceEvent.parse("{\"v\":1,\"type\":\"command\",\"id\":\"cmd-1\",\"text\":\"打开抖音\"}")
        assertFalse(BleTriggerAdapter.matches(normal))
        assertEquals("command", normal.type)
    }
}
