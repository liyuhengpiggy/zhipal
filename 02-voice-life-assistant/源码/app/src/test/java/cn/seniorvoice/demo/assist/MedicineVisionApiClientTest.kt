package cn.seniorvoice.demo.assist

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MedicineVisionApiClientTest {
    private val id = "_ocr-20261024"
    private val question = "这个药盒的生产日期是什么？"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private fun state(status: String, task: String = id) = JSONObject().put("task_id", task).put("status", status)
    private fun success(task: String = id) = state("succeeded", task)
        .put("answer", "生产日期识别候选：2026/08/12，请对照原图确认。")
        .put("evidence_text", "生产日期：2026/08/12")
        .put("result", JSONObject().put("answer_type", "field_candidate").put("review_required", true))

    @Test fun documentedOcrShapeSupportsTwoFramesAndQuestionPatchWithoutUploadTicketOrHashes() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            if (calls == 0) assertNull(request.headers["authorization"])
            else assertEquals("Bearer phone-server-token", request.headers["authorization"])
            when (calls++) {
                0 -> {
                    assertTrue(request.line.startsWith("GET /health "))
                    200 to JSONObject().put("status", "ok").put("ready", true)
                        .put("capabilities", JSONObject().put("ocr", true).put("object_recognition", true))
                }
                1 -> {
                    assertTrue(request.line.startsWith("POST /api/v1/vision/tasks "))
                    val body = JSONObject(request.body.toString(Charsets.UTF_8))
                    assertEquals(id, body.getString("task_id"))
                    assertEquals("event-001", body.getString("record_id"))
                    assertEquals("A", body.getString("source_button"))
                    assertEquals("medicine_ocr", body.getString("task_type"))
                    assertEquals(2, body.getInt("expected_frames"))
                    assertFalse(body.has("mode")); assertFalse(body.has("question"))
                    201 to state("awaiting_question")
                }
                2, 3 -> {
                    val frame = if (calls == 3) "front" else "back"
                    assertTrue(request.line.startsWith("PUT /api/v1/vision/tasks/$id/frames/$frame "))
                    assertEquals(id, request.headers["x-task-id"])
                    assertEquals(frame, request.headers["x-frame-id"])
                    assertEquals("image/jpeg", request.headers["content-type"])
                    assertArrayEquals(jpeg, request.body)
                    202 to JSONObject() // Upload acknowledgement need not be a full task snapshot.
                }
                4 -> {
                    assertTrue(request.line.startsWith("PATCH /api/v1/vision/tasks/$id "))
                    assertEquals(question, JSONObject(request.body.toString(Charsets.UTF_8)).getString("question"))
                    200 to state("queued").put("question", question).put("expected_frames", 2)
                }
                else -> {
                    assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id "))
                    200 to success() // Exact documented shape: root answer and evidence, no frame hashes.
                }
            }
        }.use { server ->
            val client = MedicineVisionApiClient(ServerConfig(server.base), "phone-server-token")
            val ticket = client.ticket()
            assertTrue(client.requireReady(ticket).ready)
            assertEquals("awaiting_question", client.createOcr(id, "", ticket, "event-001", 2).status)
            client.uploadOcr(id, "front", jpeg, ticket)
            client.uploadOcr(id, "back", jpeg, ticket)
            client.patchQuestion(id, question, ticket)
            val answer = client.awaitOcr(id, ticket)
            assertTrue(answer.reviewRequired)
            assertTrue(answer.display().contains("核对原图"))
            assertEquals("生产日期：2026/08/12", answer.evidence)
            assertEquals(6, calls)
        }
    }

    @Test fun unprovidedCreateResponseSchemaIsConfirmedByGetInsteadOfInventedUploadFields() {
        var calls = 0
        OcrIntegrationTest.HttpFixture { request ->
            when (calls++) {
                0 -> { assertTrue(request.line.startsWith("POST ")); 201 to JSONObject() }
                else -> {
                    assertTrue(request.line.startsWith("GET /api/v1/vision/tasks/$id "))
                    200 to state("awaiting_frame")
                }
            }
        }.use { server ->
            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
            assertEquals("awaiting_frame", c.createOcr(id, question, c.ticket()).status)
            assertEquals(2, calls)
        }
    }

    @Test fun objectUsesItsOwnRouteAndActualJpegDataUrl() {
        OcrIntegrationTest.HttpFixture { request ->
            assertTrue(request.line.startsWith("POST /api/v1/object-recognition "))
            assertEquals("Bearer token", request.headers["authorization"])
            val body = JSONObject(request.body.toString(Charsets.UTF_8))
            assertEquals(id, body.getString("task_id")); assertEquals(question, body.getString("question"))
            val images = body.getJSONArray("images")
            assertEquals(1, images.length())
            val image = images.getString(0)
            assertTrue(image.startsWith("data:image/jpeg;base64,"))
            assertArrayEquals(jpeg, Base64.getDecoder().decode(image.substringAfter(',')))
            200 to state("succeeded").put("model", "Qwen/Qwen3-VL-8B-Instruct")
                .put("answer", "画面中可以确认有一个药盒。")
                .put("evidence_text", "由图片视觉模型生成；请以原图核对。")
                .put("review_required", true)
        }.use { server ->
            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
            assertTrue(c.recognizeObject(id, question, listOf(jpeg), c.ticket()).text.contains("药盒"))
        }
    }

    @Test fun wrongTaskIdsNeverBecomeOcrOrObjectAnswersAndUploadAcksCannotChangeTasks() {
        for (method in listOf("create", "get", "upload", "patch", "object")) {
            OcrIntegrationTest.HttpFixture { 200 to success("other-task") }.use { server ->
                val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                val ticket = c.ticket()
                assertThrows(IllegalArgumentException::class.java) {
                    when (method) {
                        "create" -> c.createOcr(id, question, ticket)
                        "get" -> c.getOcr(id, ticket)
                        "upload" -> c.uploadOcr(id, "f0", jpeg, ticket)
                        "patch" -> c.patchQuestion(id, question, ticket)
                        else -> c.recognizeObject(id, question, listOf(jpeg), ticket)
                    }
                }
            }
        }
    }

    @Test fun presentQuestionAndFrameCountMustMatchButUnpromisedFieldsAreOptional() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("question", "旧问题") }, { it.put("expected_frames", 4) },
            { it.put("source_button", "B") }, { it.put("task_type", "object") }
        )) {
            var calls = 0
            OcrIntegrationTest.HttpFixture {
                if (calls++ == 0) 201 to state("awaiting_frame") else 200 to success().also(change)
            }.use { server ->
                val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                val ticket = c.ticket()
                c.createOcr(id, question, ticket)
                assertThrows(IllegalArgumentException::class.java) { c.getOcr(id, ticket) }
            }
        }
    }

    @Test fun degradedAndNotReadyHealthNeverPermitCreatingNewTasks() {
        for (value in listOf(
            JSONObject().put("status", "degraded").put("ready", true),
            JSONObject().put("status", "ok").put("ready", false),
            JSONObject().put("status", "ok").put("ready", true)
        )) OcrIntegrationTest.HttpFixture {
            200 to JSONObject(value.toString()).put("capabilities", JSONObject().put("ocr", value.optString("status") != "ok" || !value.optBoolean("ready")))
        }.use { server ->
            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
            val error = assertThrows(MedicineVisionApiClient.ApiException::class.java) { c.requireReady(c.ticket()) }
            assertEquals(503, error.httpStatus)
        }
    }

    @Test fun healthRequiresLiteralBooleanReadinessAndCapabilityRatherThanCoercibleValues() {
        for (field in listOf("ready", "ocr")) {
            for (invalid in listOf("true", "TRUE", 1, JSONObject.NULL, JSONObject())) {
                val response = JSONObject().put("status", "ok").put("ready", true)
                    .put("capabilities", JSONObject().put("ocr", true))
                if (field == "ready") response.put(field, invalid)
                else response.getJSONObject("capabilities").put(field, invalid)
                OcrIntegrationTest.HttpFixture { 200 to response }.use { server ->
                    val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                    val error = assertThrows(MedicineVisionApiClient.ApiException::class.java) {
                        c.requireReady(c.ticket())
                    }
                    assertEquals(503, error.httpStatus)
                }
            }
        }
    }

    @Test fun ocrAndObjectAnswersRejectNonStringTextInEitherRootOrNestedResult() {
        for (method in listOf("ocr", "object")) {
            for (field in listOf("answer", "evidence_text")) {
                for (nested in listOf(false, true)) {
                    for (invalid in listOf(JSONObject.NULL, 20260812, true, JSONObject(), JSONArray())) {
                        val response = success()
                        (if (nested) response.getJSONObject("result") else response).put(field, invalid)
                        OcrIntegrationTest.HttpFixture { 200 to response }.use { server ->
                            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                            val error = assertThrows(IllegalArgumentException::class.java) {
                                if (method == "ocr") c.getOcr(id, c.ticket())
                                else c.recognizeObject(id, question, listOf(jpeg), c.ticket())
                            }
                            assertTrue(error.message.orEmpty().contains(field))
                        }
                    }
                }
            }
        }
    }

    @Test fun reviewMetadataRejectsNonBooleanValuesEvenWhenTheOtherLocationIsValid() {
        for (nested in listOf(false, true)) {
            for (invalid in listOf("false", "true", 0, JSONObject.NULL, JSONObject(), JSONArray())) {
                val response = success().put("review_required", false)
                (if (nested) response.getJSONObject("result") else response).put("review_required", invalid)
                OcrIntegrationTest.HttpFixture { 200 to response }.use { server ->
                    val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                    val error = assertThrows(IllegalArgumentException::class.java) { c.getOcr(id, c.ticket()) }
                    assertTrue(error.message.orEmpty().contains("review_required"))
                }
            }
        }
    }

    @Test fun missingReviewDefaultsToRequiredAndAnyLiteralTrueKeepsReviewRequired() {
        val cases = listOf(
            JSONObject().put("answer", "药盒") to true,
            JSONObject().put("answer", "药盒").put("review_required", false) to false,
            JSONObject().put("result", JSONObject().put("answer", "药盒").put("review_required", false)) to false,
            JSONObject().put("answer", "药盒").put("review_required", false)
                .put("result", JSONObject().put("review_required", true)) to true,
            JSONObject().put("answer", "药盒").put("review_required", true)
                .put("result", JSONObject().put("review_required", false)) to true
        )
        for ((fields, expected) in cases) {
            fields.put("task_id", id).put("status", "succeeded")
            OcrIntegrationTest.HttpFixture { 200 to fields }.use { server ->
                val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                val answer = requireNotNull(c.getOcr(id, c.ticket()).answer)
                assertEquals("药盒", answer.text)
                assertEquals(expected, answer.reviewRequired)
            }
        }
    }

    @Test fun presentResultMustBeAJsonObject() {
        for (invalid in listOf(JSONObject.NULL, "answer", 1, true, JSONArray())) {
            OcrIntegrationTest.HttpFixture { 200 to success().put("result", invalid) }.use { server ->
                val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
                assertThrows(IllegalArgumentException::class.java) { c.getOcr(id, c.ticket()) }
            }
        }
    }

    @Test fun errorsUseStructuredCodesAndDoNotLeakServerEchoedTokenOrCreateAutomaticRetries() {
        for ((status, code) in listOf(401 to "unauthorized", 404 to "task_not_found", 409 to "frame_conflict",
            413 to "image_too_large", 415 to "unsupported_media_type", 422 to "invalid_request", 429 to "queue_full", 503 to "model_unavailable")) {
            var calls = 0
            OcrIntegrationTest.HttpFixture {
                calls++
                status to JSONObject().put("error", JSONObject().put("code", code).put("message", "bad secret-token"))
            }.use { server ->
                val c = MedicineVisionApiClient(ServerConfig(server.base), "secret-token")
                val error = assertThrows(MedicineVisionApiClient.ApiException::class.java) { c.getOcr(id, c.ticket()) }
                assertEquals(status, error.httpStatus); assertEquals(code, error.code)
                assertFalse(error.message!!.contains("secret-token")); assertEquals(1, calls)
                if (status == 503) assertTrue(error.message!!.contains("视觉服务暂时不可用"))
            }
        }
    }

    @Test fun terminalTaskFailureIsNotARecognizedAnswer() {
        OcrIntegrationTest.HttpFixture {
            200 to state("failed").put("error", JSONObject().put("code", "image_quality").put("message", "请重拍"))
        }.use { server ->
            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
            val error = assertThrows(MedicineVisionApiClient.ApiException::class.java) { c.awaitOcr(id, c.ticket()) }
            assertEquals("image_quality", error.code); assertTrue(error.message!!.contains("请重拍"))
        }
    }

    @Test fun expiredBudgetStopsBeforeAnotherRequestAndLimitsTheWholeTaskTo180Seconds() {
        val now = AtomicLong(0)
        val c = MedicineVisionApiClient(ServerConfig(java.net.URI("https://example.test/")), "token", now::get)
        val ticket = c.ticket()
        now.set(179_999_999_999L); c.check(ticket)
        now.set(180_000_000_000L)
        assertThrows(SocketTimeoutException::class.java) { c.health(ticket) }
        assertThrows(SocketTimeoutException::class.java) { c.check(ticket) }
    }

    @Test fun cancellingDisconnectsInFlightRequestAndInvalidatesPreviousTicket() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        OcrIntegrationTest.HttpFixture(ignoreDisconnect = true) {
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            200 to state("awaiting_frame")
        }.use { server ->
            val c = MedicineVisionApiClient(ServerConfig(server.base), "token")
            val ticket = c.ticket(); val completed = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            thread(isDaemon = true) {
                try { c.getOcr(id, ticket) } catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            c.cancel()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancellationException)
            assertThrows(CancellationException::class.java) { c.getOcr(id, ticket) }
            release.countDown()
        }
    }

    @Test fun invalidIdsAndIncompleteJpegFailLocally() {
        val c = MedicineVisionApiClient(ServerConfig(java.net.URI("https://example.test/")), "token")
        val ticket = c.ticket()
        for (bad in listOf("", "a".repeat(65), "../other", "hardware:a.1", "任务"))
            assertThrows(IllegalArgumentException::class.java) { c.getOcr(bad, ticket) }
        assertThrows(IllegalArgumentException::class.java) { c.uploadOcr(id, "f0", jpeg.copyOf(4), ticket) }
        assertThrows(IllegalArgumentException::class.java) { c.recognizeObject(id, question, emptyList(), ticket) }
    }
}
