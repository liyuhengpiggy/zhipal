package cn.seniorvoice.demo.assist

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OcrIntegrationTest {
    private val task = "v_test"
    private val question = "生产日期是什么？"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private val sha get() = OcrApiClient.sha256(jpeg)
    private fun snapshot(status: String = "awaiting_frame") = JSONObject().put("task_id", task)
        .put("question", question).put("expected_frames", 1).put("status", status)
    private fun answer(type: String = "field_candidate"): JSONObject {
        val proof = JSONObject().put("frame_id", "f0").put("text", "生产日期：2026/08/12")
        val candidates = JSONArray().put(JSONObject().put("value", "2026/08/12").put("evidence", JSONArray().put(proof)))
        if (type == "conflicting_candidates") candidates.put(JSONObject().put("value", "2025/12/03").put("evidence", JSONArray().put(proof)))
        val frame = JSONObject().put("frame_id", "f0").put("sha256", sha).put("ocr_text", proof.getString("text"))
        val result = JSONObject().put("answer_type", type).put("intent", "生产日期").put("review_required", true)
            .put("fields", JSONObject().put("生产日期", candidates)).put("frames", JSONArray().put(frame))
            .put("answer", "生产日期识别候选：2026/08/12。请对照原图确认。")
            .put("evidence_text", if (type in setOf("not_found", "unsupported")) "" else proof.getString("text"))
        return snapshot("succeeded").put("frames", JSONArray().put(JSONObject(frame.toString())))
            .put("answer", result.getString("answer")).put("evidence_text", result.getString("evidence_text")).put("result", result)
    }

    internal data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)
    internal class HttpFixture(private val ignoreDisconnect: Boolean = false, private val handle: (Request) -> Pair<Int, JSONObject>) : AutoCloseable {
        val server = ServerSocket(0)
        val base = URI("http://127.0.0.1:${server.localPort}/")
        val failure = AtomicReference<Throwable?>()
        private val runner = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val next = input.read()
                            check(next >= 0) { "Incomplete request" }
                            if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
                            bytes.write(next)
                        }
                    }
                    val first = line()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var count = 0
                    while (count < body.size) {
                        val read = input.read(body, count, body.size - count)
                        check(read > 0); count += read
                    }
                    val (code, response) = handle(Request(first, headers, body))
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed && !(ignoreDisconnect && error is java.net.SocketException)) failure.set(error)
            }
        }
        override fun close() { server.close(); runner.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }

    @Test fun createAndRawJpegUploadUseExactContractAndDoNotTreat202AsSuccess() {
        var count = 0
        HttpFixture { request ->
            assertEquals("Bearer secret", request.headers["authorization"])
            when (count++) {
                0 -> {
                    assertTrue(request.line.startsWith("POST /api/v1/vision/tasks "))
                    val json = JSONObject(request.body.toString(Charsets.UTF_8))
                    assertEquals(task, json.getString("task_id")); assertEquals(question, json.getString("question"))
                    assertEquals(1, json.getInt("expected_frames"))
                    201 to snapshot()
                }
                1 -> {
                    assertTrue(request.line.startsWith("PUT /api/v1/vision/tasks/$task/frames/f0 "))
                    assertEquals("image/jpeg", request.headers["content-type"])
                    assertArrayEquals(jpeg, request.body)
                    202 to snapshot("failed").put("error_code", "image_quality")
                }
                else -> { assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$task ")); 200 to answer() }
            }
        }.use { server ->
            val client = OcrApiClient(); val ticket = client.ticket()
            assertEquals("awaiting_frame", client.create(server.base, "secret", task, question, ticket).getString("status"))
            assertEquals("failed", client.upload(server.base, "secret", task, question, jpeg, ticket).getString("status"))
            val parsed = OcrProtocol.answer(client.get(server.base, "secret", task, question, ticket), task, question, sha)
            assertEquals(sha, parsed.sha256); assertTrue(parsed.display().contains("识别原文"))
        }
        assertEquals(3, count)
    }

    @Test fun healthRequiresOcrCapabilityAndReadyInsteadOfOnlyStatusOk() {
        for (health in listOf(JSONObject().put("status", "ok"), JSONObject().put("ready", false).put("capabilities", JSONObject().put("ocr", true)),
            JSONObject().put("ready", true).put("capabilities", JSONObject().put("ocr", false)))) {
            HttpFixture { 200 to health }.use { server ->
                val c = OcrApiClient()
                assertThrows(IllegalStateException::class.java) { c.ready(server.base, "", c.ticket()) }
            }
        }
    }

    @Test fun authConflictRedirectAndOverloadNeverBecomeAnswers() {
        for (code in listOf(401, 409, 302, 429, 503)) HttpFixture { code to JSONObject() }.use { server ->
            val c = OcrApiClient()
            val error = assertThrows(java.io.IOException::class.java) { c.get(server.base, "bad", task, question, c.ticket()) }
            assertTrue(error.message!!.contains("HTTP $code"))
        }
    }

    @Test fun wrongTaskQuestionHashFrameAndInconsistentAnswerAreRejected() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("task_id", "old") }, { it.put("question", "旧问题") }, { it.put("expected_frames", 2) },
            { it.getJSONArray("frames").getJSONObject(0).put("frame_id", "old") },
            { it.getJSONObject("result").getJSONArray("frames").getJSONObject(0).put("sha256", "a".repeat(64)) },
            { it.put("answer", "另一个回答") },
            { it.getJSONObject("result").put("review_required", false) },
            { it.getJSONObject("result").getJSONObject("fields").getJSONArray("生产日期").getJSONObject(0)
                .getJSONArray("evidence").getJSONObject(0).put("frame_id", "old") }
        )
        for (change in changes) assertThrows(IllegalArgumentException::class.java) {
            OcrProtocol.answer(answer().also(change), task, question, sha)
        }
        assertThrows(IllegalArgumentException::class.java) { OcrProtocol.answer(answer(), task, question, "b".repeat(64)) }
    }

    @Test fun allAnswerTypesPreserveReviewAndDoNotChooseConflictingCandidates() {
        for (type in listOf("ocr_text", "field_candidate", "conflicting_candidates", "not_found", "unsupported")) {
            val parsed = OcrProtocol.answer(answer(type), task, question, sha)
            assertEquals(type, parsed.type)
            val display = parsed.display()
            when (type) {
                "field_candidate" -> assertTrue(display.contains("核对原图"))
                "conflicting_candidates" -> assertTrue(display.contains("不自动选择"))
                "not_found" -> assertTrue(display.contains("重新发起"))
                "unsupported" -> assertFalse(display.contains("识别原文"))
                else -> assertTrue(display.contains("识别原文"))
            }
        }
    }

    @Test fun urlsAndServiceIdsRejectDocsPublicHttpAndHardwareOnlyIdentifiers() {
        assertEquals("https://example.test/", OcrProtocol.endpoint("https://example.test", false).toString())
        assertEquals("http://192.168.1.8:8000/", OcrProtocol.endpoint("http://192.168.1.8:8000", true, true).toString())
        for (value in listOf("https://example.test/docs", "https://user:pass@example.test", "http://example.test", "http://8.8.8.8", "https://example.test/?token=bad"))
            assertThrows(IllegalArgumentException::class.java) { OcrProtocol.endpoint(value, true, true) }
        assertThrows(IllegalArgumentException::class.java) { OcrProtocol.endpoint("http://192.168.1.8", false) }
        for (id in listOf("boot:a.001", "a".repeat(65), "../old")) assertThrows(IllegalArgumentException::class.java) { OcrProtocol.checkId(id) }
        OcrProtocol.checkId(OcrProtocol.newId())
        assertTrue(OcrProtocol.supports("念一下眼前文字")); assertFalse(OcrProtocol.supports("这是什么东西"))
        assertEquals("读一下药盒", OcrProtocol.normalizeQuestion("读一读药盒"))
    }

    @Test fun cancellationInvalidatesQueuedTicketsAndDisconnectsAnInFlightRequest() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        HttpFixture(ignoreDisconnect = true) { entered.countDown(); release.await(3, TimeUnit.SECONDS); 200 to snapshot() }.use { server ->
            val c = OcrApiClient(); val ticket = c.ticket(); val done = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            thread(isDaemon = true) { try { c.get(server.base, "", task, question, ticket) } catch (e: Throwable) { error.set(e) } finally { done.countDown() } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            c.cancelAll()
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNotNull(error.get())
            assertThrows(IllegalStateException::class.java) { c.get(server.base, "", task, question, ticket) }
            release.countDown()
        }
    }

    @Test fun workflowCancellationDiscardsAlreadyPostedSuccess() {
        val delivered = LinkedBlockingQueue<() -> Unit>()
        HttpFixture { 200 to answer() }.use { server ->
            val workflow = OcrWorkflow { delivered.add(it) }
            try {
                var finished = false
                workflow.start(server.base, "", task, question, null, sha, true, {}, {}, { finished = true })
                val callback = delivered.poll(4, TimeUnit.SECONDS) ?: error("No callback")
                workflow.cancel(); callback()
                assertFalse(finished)
            } finally { workflow.close() }
        }
    }

    @Test fun workflowStopsOn202FailedWithoutReturningAnAnswer() {
        val delivered = LinkedBlockingQueue<() -> Unit>()
        var calls = 0
        HttpFixture {
            when (calls++) {
                0 -> 200 to JSONObject().put("ready", true).put("capabilities", JSONObject().put("ocr", true))
                1 -> 201 to snapshot()
                else -> 202 to snapshot("failed").put("error_code", "image_quality").put("message", "请重拍")
            }
        }.use { server ->
            val workflow = OcrWorkflow { delivered.add(it) }
            try {
                var outcome: Result<OcrProtocol.Answer>? = null
                workflow.start(server.base, "", task, question, jpeg, sha, false, { fail("No hardware capture for local image") }, {}, { outcome = it })
                val deadline = System.nanoTime() + 4_000_000_000L
                while (outcome == null && System.nanoTime() < deadline)
                    delivered.poll(1, TimeUnit.SECONDS)?.invoke()
                assertNotNull("Workflow must deliver the terminal failure", outcome)
                assertTrue(outcome!!.isFailure); assertTrue(outcome!!.exceptionOrNull()!!.message!!.contains("image_quality"))
                assertEquals(3, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun hardwareWorkflowCreatesBeforeCaptureAndPollsTheSameTaskToSuccess() {
        val delivered = LinkedBlockingQueue<() -> Unit>()
        val phases = mutableListOf<String>()
        var calls = 0
        HttpFixture { request ->
            when (calls++) {
                0 -> 200 to JSONObject().put("ready", true).put("capabilities", JSONObject().put("ocr", true))
                1 -> { assertTrue(request.line.startsWith("POST ")); 201 to snapshot() }
                2 -> { assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$task ")); 200 to snapshot("running") }
                else -> 200 to answer()
            }
        }.use { server ->
            val workflow = OcrWorkflow { delivered.add(it) }
            try {
                var outcome: Result<OcrProtocol.Answer>? = null
                workflow.start(server.base, "", task, question, null, null, false,
                    { phases.add("capture") }, { phases.add(it) }, { outcome = it })
                val deadline = System.nanoTime() + 6_000_000_000L
                while (outcome == null && System.nanoTime() < deadline)
                    delivered.poll(3, TimeUnit.SECONDS)?.invoke()
                assertTrue(outcome!!.isSuccess); assertEquals(sha, outcome!!.getOrThrow().sha256)
                assertTrue(phases.first().contains("检查模型"))
                assertTrue(phases.indexOf("capture") > phases.indexOfFirst { it.contains("创建本次任务") })
                assertTrue(phases.any { it.contains("等待") })
                assertEquals(4, calls)
            } finally { workflow.close() }
        }
    }
}
