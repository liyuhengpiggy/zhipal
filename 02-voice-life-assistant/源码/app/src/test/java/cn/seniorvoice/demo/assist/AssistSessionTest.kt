package cn.seniorvoice.demo.assist

import cn.seniorvoice.demo.BleIncomingStream
import cn.seniorvoice.demo.DeviceEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssistSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun waiting(): Pair<AssistSession, Long> {
        val session = AssistSession()
        val token = session.check(0)!!
        assertTrue(session.offer(token, "prompt-1"))
        return session to token
    }

    @Test fun aPreemptsEveryBStageAndRejectsLateCallbacks() {
        for (stage in AssistSession.BState.entries) {
            val session = AssistSession()
            val token = session.generation
            if (stage != AssistSession.BState.OBSERVING) session.check(0)
            if (stage in setOf(AssistSession.BState.WAITING, AssistSession.BState.RECORDING, AssistSession.BState.THINKING, AssistSession.BState.RESULT)) session.offer(token, "old")
            if (stage in setOf(AssistSession.BState.RECORDING, AssistSession.BState.THINKING, AssistSession.BState.RESULT)) session.record("old")
            if (stage in setOf(AssistSession.BState.THINKING, AssistSession.BState.RESULT)) session.think(token, "old")
            if (stage == AssistSession.BState.RESULT) session.result(token)
            assertEquals(stage, session.bState)
            session.beginA("a-1", 500)
            assertNull(session.promptId)
            assertFalse(session.current(token))
            assertFalse(session.offer(token, "late"))
            assertNull(session.record("old"))
            assertFalse(session.think(token, "old"))
            assertFalse(session.result(token))
            assertFalse(session.mayObserve(999999))
        }
    }

    @Test fun finishingOldADoesNotReleaseNewA() {
        val s = AssistSession()
        s.beginA("a-1", 0); s.beginA("a-2", 1); s.finishA("a-1", 2)
        assertEquals("a-2", s.aTask)
        s.finishA("a-2", 3)
        assertFalse(s.mayObserve(5002)); assertTrue(s.mayObserve(5003))
        assertNull(s.promptId); assertEquals(AssistSession.BState.OBSERVING, s.bState)
    }

    @Test fun promptMustBeCurrentAndCanOnlyBeAnsweredOnce() {
        val (s, token) = waiting()
        assertNull(s.record("wrong")); assertFalse(s.think(token, "prompt-1"))
        assertEquals(token, s.record("prompt-1")); assertNull(s.record("prompt-1"))
        assertTrue(s.think(token, "prompt-1")); assertFalse(s.think(token, "prompt-1"))
        assertTrue(s.offer(token, "prompt-2"))
        assertNull(s.record("prompt-1")); assertEquals(token, s.record("prompt-2"))
        assertTrue(s.think(token, "prompt-2")); assertTrue(s.result(token)); assertFalse(s.result(token))
        assertNull(s.promptId)
    }

    @Test fun cancellationAndReconfigurationInvalidateAllPreviousTokens() {
        val (s, token) = waiting()
        s.clearB(10)
        assertFalse(s.current(token)); assertFalse(s.mayObserve(60009)); assertTrue(s.mayObserve(60010))
        assertNull(s.record("prompt-1"))
    }

    @Test fun stationaryFramesRequireWholeDwellAndThreeSamples() {
        val d = PageDwellDetector(dwellMs = 4000)
        assertFalse(d.sample("page", intArrayOf(100, 100), 0))
        assertFalse(d.sample("page", intArrayOf(101, 100), 2000))
        assertTrue(d.sample("page", intArrayOf(102, 99), 4000))
    }

    @Test fun adjacentSimilarityCannotHideSlowContinuousScrolling() {
        val d = PageDwellDetector(dwellMs = 8000)
        for (i in 0..20) assertFalse(d.sample("page", intArrayOf(80 + i * 3), i * 2000L))
    }

    @Test fun differentPageGapAndResetEachStartANewDwell() {
        val d = PageDwellDetector(dwellMs = 4000)
        val image = intArrayOf(50)
        d.sample("one", image, 0); d.sample("one", image, 2000)
        assertFalse(d.sample("two", image, 4000))
        assertFalse(d.sample("two", image, 12000))
        assertFalse(d.sample("two", image, 14000)); assertTrue(d.sample("two", image, 16000))
        d.reset(); assertFalse(d.sample("two", image, 18000))
    }

    @Test fun refusalIsNotConfusedWithGoalContainingNegativeWord() {
        assertEquals(ReplyKind.DECLINE, classifyReply("不 需要。"))
        assertEquals(ReplyKind.ACCEPT, classifyReply("好的！"))
        assertEquals(ReplyKind.GOAL, classifyReply("我不需要这个，想返回首页"))
        assertEquals(ReplyKind.GOAL, classifyReply("我要找乘车码"))
    }

    private fun metadata(button: String = "B", purpose: String = "assist_reply", prompt: String? = "prompt-1"): DeviceEvent {
        val data = JSONObject().put("source_button", button).put("purpose", purpose).put("task_id", "b-prompt-1")
            .put("session_id", "boot-1").put("format", "wav").put("size", 32044)
        if (prompt != null) data.put("prompt_id", prompt)
        return DeviceEvent.parse(JSONObject().put("v", 2).put("type", "button_start").put("event_id", "event-1").put("data", data).toString())
    }

    @Test fun v2RecordingPreservesTaskPromptAndButton() {
        val e = RecordingEnvelope.parse(metadata())
        assertEquals("event-1", e.eventId); assertEquals("B", e.button)
        assertEquals("b-prompt-1", e.taskId); assertEquals("prompt-1", e.promptId); assertEquals(32044L, e.size)
        val returned = e.correlation()
        assertEquals("boot-1", returned.getString("session_id"))
        assertEquals(e.eventId, returned.getString("event_id")); assertEquals(e.taskId, returned.getString("task_id"))
        assertEquals(e.promptId, returned.getString("prompt_id")); assertEquals("B", returned.getString("source_button"))
    }
    @Test fun aRecordingAllowsNoPromptOrExactEchoButRejectsMalformedPrompt() {
        assertNull(RecordingEnvelope.parse(metadata("A", "command", null)).promptId)
        assertEquals("a-current", RecordingEnvelope.parse(metadata("A", "command", "a-current")).promptId)
        for (prompt in listOf("../old", "a\nnew", "x".repeat(97)))
            assertThrows(IllegalArgumentException::class.java) { RecordingEnvelope.parse(metadata("A", "command", prompt)) }
    }
    @Test fun pcmMetadataPreservesButtonAndRawSizeWithWavOutputSize() {
        val event = metadata(); event.data.put("format", "pcm_s16le").put("size", 32000)
        val e = RecordingEnvelope.parse(event)
        assertEquals("B", e.button); assertEquals("boot-1", e.sessionId)
        assertEquals(32000L, e.size); assertEquals(32044L, e.expectedWavSize)
        event.data.remove("source_button")
        assertThrows(IllegalArgumentException::class.java) { RecordingEnvelope.parse(event) }
    }

    @Test fun missingButtonOrBPromptAndMixedPurposesAreRejected() {
        for (event in listOf(metadata("", "command"), metadata(prompt = null), metadata("B", "command"), metadata("A", "assist_reply")))
            assertThrows(IllegalArgumentException::class.java) { RecordingEnvelope.parse(event) }
    }

    @Test fun invalidMetadataSizeSessionOrAudioFormatCannotBeRouted() {
        for ((key, value) in listOf("size" to -1, "size" to 10485761, "session_id" to "", "task_id" to "../a", "format" to "mp3")) {
            val event = metadata(); event.data.put(key, value)
            assertThrows(IllegalArgumentException::class.java) { RecordingEnvelope.parse(event) }
        }
    }

    @Test fun streamSignalsRecordingStartBeforeFileCompletion() {
        val order = mutableListOf<String>()
        val stream = BleIncomingStream(temporary.newFolder(), { order.add("meta") }, { order.add("file"); it.delete() }, { fail(it) }, { order.add("start") })
        val data = ByteArray(364)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(data.size - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(320)
        }
        stream.accept("{\"v\":2,\"id\":\"a-1\",\"type\":\"button_start\"}\n".toByteArray())
        stream.accept(data.copyOfRange(0, 12))
        assertEquals(listOf("meta", "start"), order)
        stream.accept(data.copyOfRange(12, data.size))
        assertEquals(listOf("meta", "start", "file"), order)
    }

    @Test fun mockedOrEmptyVisionNeverCountsAsRealPageUnderstanding() {
        for (json in listOf("{\"status\":\"mock\",\"mode\":\"mock\"}", "{\"status\":\"ok\",\"mode\":\"cloud\",\"analysis\":{}}"))
            assertThrows(IllegalStateException::class.java) { VisionClient.scene(JSONObject(json)) }
        assertEquals("页面", VisionClient.scene(JSONObject("{\"status\":\"ok\",\"mode\":\"cloud\",\"analysis\":{\"scene\":\"页面\"}}")))
    }

    @Test fun sharedLatestPhotoCannotBeUsedAsTaskPhoto() {
        val client = VisionClient()
        for (location in listOf("/camera/latest", "/camera/latest/?t=1", "/camera/%6catest", "/camera/frame/../latest"))
            assertThrows(IllegalArgumentException::class.java) { client.downloadFrame(VisionClient.DEFAULT_BASE, location) }
    }

    @Test fun cancelledQueuedNetworkWorkCannotStartANewConnection() {
        val client = VisionClient()
        val old = client.ticket()
        client.cancelAll()
        // Invalid URL would fail differently if a cancelled queued request reached URL handling.
        assertThrows(IllegalStateException::class.java) { client.call("invalid", "", byteArrayOf(1, 2, 3, 4), expectedEpoch = old) }
        assertThrows(IllegalStateException::class.java) { client.downloadFrame("invalid", "invalid", old) }
    }
}
