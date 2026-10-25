package cn.seniorvoice.demo.assist

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VisionApiClientTest {
    private val id = "v_test"
    private val question = "读文字并告诉我这是什么东西"
    private val sha = "a".repeat(64)
    private fun waiting() = JSONObject().put("task_id", id).put("question", question).put("mode", "COMBINED")
        .put("expected_frames", 1).put("status", "awaiting_frame").put("frame_id", "f0")
        .put("upload_method", "PUT").put("upload_url", "https://uploads.example.test/frame")
        .put("upload_expires_at", Instant.now().plusSeconds(120).toString())
        .put("upload_headers", JSONObject().put("X-Upload-Token", "scoped-short-token").put("Content-Type", "image/jpeg"))
    @Test fun modesAreChosenFromTheOriginalQuestion() {
        assertEquals(VisionMode.OCR, VisionApiClient.mode("生产日期是什么"))
        assertEquals(VisionMode.OBJECT, VisionApiClient.mode("这是什么东西"))
        assertEquals(VisionMode.COMBINED, VisionApiClient.mode(question))
    }
    @Test fun ambiguousOrNegativeASpeechDoesNotStartCameraAndEnglishPhysicalQuestionsDo() {
        for (text in listOf("现在应该做什么", "今天星期几", "这一步怎么办", "不要识别眼前的物体", "do not read this text"))
            assertFalse(text, VisionApiClient.isVisualQuestion(text))
        for (text in listOf("what is this?", "What am I holding?", "这是什么东西", "看看这个包装", "生产日期是什么", "读一下文字"))
            assertTrue(text, VisionApiClient.isVisualQuestion(text))
        assertEquals(VisionMode.OBJECT, VisionApiClient.mode("what is this?"))
    }
    @Test fun uploadTicketIsShortLivedAndCannotReusePermanentBearer() {
        val client = VisionApiClient(ServerConfig(URI("https://api.example.test/")), "permanent")
        assertEquals("f0", client.uploadTicket(waiting()).frameId)
        for (bad in listOf(
            waiting().put("upload_headers", JSONObject().put("Authorization", "Bearer permanent")),
            waiting().put("upload_expires_at", Instant.now().plusSeconds(1000).toString()),
            waiting().put("upload_expires_at", Instant.now().minusSeconds(1).toString()),
            waiting().put("upload_url", "http://uploads.example.test/frame"),
            waiting().put("upload_headers", JSONObject().put("Host", "another.example.test"))
        )) assertThrows(IllegalArgumentException::class.java) { client.uploadTicket(bad) }
    }
    @Test fun answerMustBindTaskQuestionModeAndExactlyOneFrameHash() {
        val frame = JSONObject().put("frame_id", "f0").put("sha256", sha)
        val root = waiting().put("status", "succeeded").put("frames", JSONArray().put(frame))
            .put("result", JSONObject().put("frame_id", "f0").put("sha256", sha).put("answer", "这是药盒，生产日期是 2026-08-12")
                .put("evidence_text", "生产日期 2026-08-12").put("review_required", true))
        assertEquals(sha, VisionApiClient.answer(root, id, question, VisionMode.COMBINED, sha).sha256)
        for (bad in listOf(
            JSONObject(root.toString()).put("task_id", "other"),
            JSONObject(root.toString()).put("question", "旧问题"),
            JSONObject(root.toString()).put("mode", "OBJECT"),
            JSONObject(root.toString()).put("mock", true),
            JSONObject(root.toString()).put("frames", JSONArray().put(JSONObject().put("frame_id", "f0").put("sha256", "b".repeat(64))))
        )) assertThrows(IllegalArgumentException::class.java) { VisionApiClient.answer(bad, id, question, VisionMode.COMBINED, sha) }
        assertThrows(IllegalArgumentException::class.java) { VisionApiClient.answer(root, id, question, VisionMode.COMBINED, "c".repeat(64)) }
    }
    @Test fun serverConfigRejectsEndpointInsteadOfRoot() {
        assertEquals("https://api.example.test/", ServerConfig.parse("https://api.example.test").base.toString())
        assertThrows(IllegalArgumentException::class.java) { ServerConfig.parse("https://api.example.test/upload") }
        assertThrows(IllegalArgumentException::class.java) { ServerConfig.parse("http://api.example.test") }
    }
    @Test fun cancelledClientCannotCreateAnotherRemoteTask() {
        val client = VisionApiClient(ServerConfig(URI("https://api.example.test/")), "")
        val ticket = client.ticket(); client.cancel()
        assertThrows(IllegalStateException::class.java) { client.create(id, question, VisionMode.COMBINED, ticket) }
    }

    @Test fun actualHttpUsesOriginalQuestionScopedUploadCredentialAndSameTaskPolling() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val actualSha = OcrApiClient.sha256(jpeg)
        val delivered = LinkedBlockingQueue<() -> Unit>()
        lateinit var base: URI
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            val response = when (calls++) {
                0 -> {
                    assertTrue(request.line.startsWith("POST /api/v1/vision/tasks "))
                    assertEquals("Bearer permanent", request.headers["authorization"])
                    val submitted = JSONObject(request.body.toString(Charsets.UTF_8))
                    assertEquals(question, submitted.getString("question")); assertEquals("COMBINED", submitted.getString("mode"))
                    waiting().put("upload_url", base.resolve("upload/frame?signature=short").toString())
                        .put("upload_headers", JSONObject().put("Authorization", "Bearer scoped"))
                }
                1 -> {
                    assertTrue(request.line.startsWith("PUT /upload/frame?signature=short "))
                    assertEquals("Bearer scoped", request.headers["authorization"])
                    assertEquals("image/jpeg", request.headers["content-type"]); assertArrayEquals(jpeg, request.body)
                    JSONObject()
                }
                else -> {
                    assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id "))
                    assertEquals("Bearer permanent", request.headers["authorization"])
                    waiting().put("status", "succeeded").put("frames", JSONArray().put(JSONObject().put("frame_id", "f0").put("sha256", actualSha)))
                        .put("result", JSONObject().put("frame_id", "f0").put("sha256", actualSha).put("answer", "测试内容")
                            .put("evidence_text", "测试原文").put("review_required", true))
                }
            }
            200 to response
        }.use { server ->
            base = server.base
            val workflow = VisionWorkflow { delivered.add(it) }
            try {
                var outcome: Result<VisionApiClient.Answer>? = null
                workflow.start(ServerConfig.parse(base.toString(), true, true), "permanent", id, question, VisionMode.COMBINED, jpeg,
                    { fail("Local image must not trigger hardware capture") }, {}, { outcome = it })
                val deadline = System.nanoTime() + 6_000_000_000L
                while (outcome == null && System.nanoTime() < deadline) delivered.poll(1, TimeUnit.SECONDS)?.invoke()
                assertNotNull(outcome); assertEquals(actualSha, outcome!!.getOrThrow().sha256); assertEquals(3, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun cancellingPostedCaptureDoesNotSendOldCredentialToHardware() {
        val delivered = LinkedBlockingQueue<() -> Unit>()
        OcrIntegrationTest.HttpFixture { 200 to waiting() }.use { server ->
            val workflow = VisionWorkflow { delivered.add(it) }
            try {
                var captured = false; var finished = false
                workflow.start(ServerConfig(server.base), "permanent", id, question, VisionMode.COMBINED,
                    created = { captured = true }, progress = {}, finished = { finished = true })
                val callback = delivered.poll(4, TimeUnit.SECONDS) ?: error("Missing capture")
                workflow.cancel(); callback()
                assertFalse(captured); assertFalse(finished)
            } finally { workflow.close() }
        }
    }
}
