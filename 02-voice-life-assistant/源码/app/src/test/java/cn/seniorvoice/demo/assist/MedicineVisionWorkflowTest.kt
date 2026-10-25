package cn.seniorvoice.demo.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MedicineVisionWorkflowTest {
    private val id = "v_workflow"
    private val question = "读一下药盒文字并告诉我这是什么物体"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private fun health() = JSONObject().put("status", "ok").put("ready", true)
        .put("capabilities", JSONObject().put("ocr", true).put("object_recognition", true))
    private fun state(status: String) = JSONObject().put("task_id", id).put("status", status)
    private fun success(text: String) = state("succeeded").put("answer", text).put("evidence_text", "证据：$text")
        .put("review_required", true)
    private fun pump(queue: LinkedBlockingQueue<() -> Unit>, complete: () -> Boolean) {
        val end = System.nanoTime() + 6_000_000_000L
        while (!complete() && System.nanoTime() < end) queue.poll(100, TimeUnit.MILLISECONDS)?.invoke()
        assertTrue("No workflow result within bounded test time", complete())
    }

    @Test fun localCombinedUploadsAndRecognizesExactlyTheSameJpeg() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            when (calls++) {
                0 -> { assertTrue(request.line.startsWith("GET /health ")); 200 to health() }
                1 -> {
                    assertTrue(request.line.startsWith("POST /api/v1/vision/tasks "))
                    assertEquals("event-01", JSONObject(request.body.toString(Charsets.UTF_8)).getString("record_id"))
                    201 to state("awaiting_frame")
                }
                2 -> {
                    assertTrue(request.line.startsWith("PUT /api/v1/vision/tasks/$id/frames/f0 "))
                    assertArrayEquals(jpeg, request.body); 202 to JSONObject()
                }
                3 -> { assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id ")); 200 to success("生产日期2026/08/12") }
                else -> {
                    assertTrue(request.line.startsWith("POST /api/v1/object-recognition "))
                    val image = JSONObject(request.body.toString(Charsets.UTF_8)).getJSONArray("images").getString(0)
                    assertArrayEquals(jpeg, Base64.getDecoder().decode(image.substringAfter(',')))
                    200 to success("这是一个药盒")
                }
            }
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, question, VisionMode.COMBINED,
                    jpeg, "event-01", created = { fail("Phone JPEG must not request a new hardware capture") },
                    progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                val answer = outcome!!.getOrThrow()
                assertTrue(answer.text.contains("生产日期2026/08/12")); assertTrue(answer.text.contains("这是一个药盒"))
                assertTrue(answer.reviewRequired); assertEquals(5, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun hardwareOcrCreatesBeforeIssuingExactAuthenticatedCaptureAndOnlyPollsAfterCapture() {
        var calls = 0
        var captured = false
        OcrIntegrationTest.HttpFixture { request ->
            when (calls++) {
                0 -> 200 to health()
                1 -> { assertFalse(captured); 201 to state("awaiting_frame") }
                else -> {
                    assertTrue(captured); assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id "))
                    200 to success("生产日期2026/08/12")
                }
            }
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, question, VisionMode.OCR,
                    created = { capture ->
                        captured = true
                        assertEquals(id, capture.taskId); assertEquals("f0", capture.frameId)
                        assertEquals(server.base.resolve("api/v1/vision/tasks/$id/frames/f0").toString(), capture.uploadUrl)
                        assertEquals("Bearer server-token", capture.headers.getString("Authorization"))
                        assertEquals(id, capture.headers.getString("X-Task-Id"))
                        assertEquals("f0", capture.headers.getString("X-Frame-Id"))
                    }, progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertTrue(outcome!!.isSuccess); assertEquals(3, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun hardwareObjectHasNoOcrUploadRouteAndAcceptsOnlyOneImageForCurrentTask() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            when (calls++) {
                0 -> 200 to health()
                else -> {
                    assertTrue(request.line.startsWith("POST /api/v1/object-recognition "))
                    val image = JSONObject(request.body.toString(Charsets.UTF_8)).getJSONArray("images").getString(0)
                    assertArrayEquals(jpeg, Base64.getDecoder().decode(image.substringAfter(',')))
                    200 to success("这是一个遥控器")
                }
            }
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, "这是什么物体", VisionMode.OBJECT,
                    created = { capture ->
                        assertNull(capture.uploadUrl); assertEquals(0, capture.headers.length())
                        assertFalse(workflow.provideFrame("old-task", jpeg))
                        assertTrue(workflow.provideFrame(id, jpeg))
                        assertFalse(workflow.provideFrame(id, jpeg))
                    }, progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertTrue(outcome!!.getOrThrow().text.contains("遥控器")); assertEquals(2, calls)
                assertFalse(workflow.provideFrame(id, jpeg))
            } finally { workflow.close() }
        }
    }

    @Test fun resumeQueriesOriginalOcrTaskAndNeverCreatesUploadsOrCaptures() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            if (calls++ == 0) 200 to health() else {
                assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id "))
                200 to success("恢复结果").put("question", question).put("record_id", "event-01")
            }
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, question, VisionMode.OCR,
                    recordId = "event-01", resume = true, created = { fail("Resume must not recapture") },
                    progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertEquals("恢复结果", outcome!!.getOrThrow().text); assertEquals(2, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun aNewTaskCannotReuseAnAlreadySucceededCreateResponse() {
        var calls = 0
        OcrIntegrationTest.HttpFixture {
            if (calls++ == 0) 200 to health() else 201 to success("旧照片答案")
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, question, VisionMode.OCR,
                    created = { fail("Stale result must not trigger capture") }, progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertTrue(outcome!!.isFailure)
                assertTrue(outcome!!.exceptionOrNull()!!.message!!.contains("已有答案")); assertEquals(2, calls)
            } finally { workflow.close() }
        }
    }

    @Test fun cancellingAnUndeliveredCaptureRejectsPostedCallbacksAndNewTaskCanComplete() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            when (calls++) {
                0, 2 -> 200 to health()
                1 -> 201 to state("awaiting_frame")
                else -> {
                    assertTrue(request.line.startsWith("POST /api/v1/object-recognition "))
                    200 to JSONObject().put("task_id", "new-task").put("status", "succeeded").put("answer", "新任务答案")
                }
            }
        }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val posts = AtomicInteger()
            val captureQueued = CountDownLatch(1)
            val workflow = MedicineVisionWorkflow {
                events.add(it)
                if (posts.incrementAndGet() == 4) captureQueued.countDown()
            }
            try {
                var captured = false; var oldFinished = false
                workflow.start(ServerConfig(server.base), "server-token", id, question, VisionMode.OCR,
                    created = { captured = true }, progress = {}, finished = { oldFinished = true })
                // Wait for the create reply and posted capture without consuming the callback queue.
                assertTrue(captureQueued.await(3, TimeUnit.SECONDS))
                workflow.cancel()
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", "new-task", "这是什么", VisionMode.OBJECT, jpeg,
                    created = { fail("Local new task does not use hardware") }, progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertFalse(captured); assertFalse(oldFinished)
                assertFalse(workflow.provideFrame(id, jpeg))
                assertEquals("新任务答案", outcome!!.getOrThrow().text)
            } finally { workflow.close() }
        }
    }

    @Test fun captureCallbackFailureIsDeliveredAndDoesNotLeaveWorkerWaitingFor180Seconds() {
        OcrIntegrationTest.HttpFixture { 200 to health() }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                var outcome: Result<MedicineVisionApiClient.Answer>? = null
                workflow.start(ServerConfig(server.base), "server-token", id, "这是什么", VisionMode.OBJECT,
                    created = { error("固件不支持本次照片回传") }, progress = {}, finished = { outcome = it })
                pump(events) { outcome != null }
                assertTrue(outcome!!.isFailure)
                assertEquals("固件不支持本次照片回传", outcome!!.exceptionOrNull()!!.message)
            } finally { workflow.close() }
        }
    }

    @Test fun cancellingWhileAwaitingHardwareFrameUnblocksTheSingleWorker() {
        OcrIntegrationTest.HttpFixture { 200 to health() }.use { server ->
            val events = LinkedBlockingQueue<() -> Unit>()
            val workflow = MedicineVisionWorkflow { events.add(it) }
            try {
                val captured = CountDownLatch(1)
                var finished = false
                workflow.start(ServerConfig(server.base), "server-token", id, "这是什么", VisionMode.OBJECT,
                    created = { captured.countDown() }, progress = {}, finished = { finished = true })
                pump(events) { captured.count == 0L }
                workflow.cancel()
                assertFalse(workflow.provideFrame(id, jpeg))
                val callback = events.poll(2, TimeUnit.SECONDS)
                callback?.invoke()
                assertFalse(finished)
            } finally { workflow.close() }
        }
    }
}
