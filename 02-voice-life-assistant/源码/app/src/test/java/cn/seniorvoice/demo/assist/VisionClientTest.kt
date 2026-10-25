package cn.seniorvoice.demo.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionClientTest {
    @Test fun http200UnknownIntentIsNotAVisualAnswer() {
        val response = JSONObject().put("action", "unknown")
            .put("message", "抱歉，我暂时无法确定您想执行的操作。")
        assertThrows(IllegalStateException::class.java) { VisionClient.answer(response) }
    }

    @Test fun mockOrFailedBodiesNeverBecomeAnswers() {
        for (response in listOf(
            JSONObject().put("status", "mock").put("message", "测试回答"),
            JSONObject().put("mode", "mock").put("message", "测试回答"),
            JSONObject().put("status", "error").put("message", "服务失败")
        )) assertThrows(IllegalStateException::class.java) { VisionClient.answer(response) }
        assertThrows(IllegalStateException::class.java) { VisionClient.scene(JSONObject().put("status", "mock").put("mode", "mock")) }
    }

    @Test fun validTextAndCloudObservationRemainSupported() {
        assertEquals("这是手机设置页面", VisionClient.answer(JSONObject().put("message", "这是手机设置页面")))
        assertEquals("设置页面\n等待选择", VisionClient.scene(JSONObject().put("status", "ok").put("mode", "cloud")
            .put("analysis", JSONObject().put("scene", "设置页面").put("current_state", "等待选择"))))
    }
}
