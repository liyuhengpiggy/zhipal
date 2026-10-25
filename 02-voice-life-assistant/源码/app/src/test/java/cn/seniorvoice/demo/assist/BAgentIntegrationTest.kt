package cn.seniorvoice.demo.assist

import com.bagent.integration.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BAgentIntegrationTest {
    @Test fun navigationAllowedButConsequentialAndUnlabelledTargetsBlocked() {
        assertTrue(BAgentActionPolicy.allowed("通讯录", "", "app:id/contacts"))
        assertTrue(BAgentActionPolicy.allowed("首页", "", ""))
        for (label in listOf("立即支付", "发送消息", "删除", "同意授权", "Submit"))
            assertFalse(BAgentActionPolicy.allowed(label, "", ""))
        assertFalse(BAgentActionPolicy.allowed("", "", "app:id/unknown"))
    }
    private class Port : SingleStepPort {
        var page = "page1"
        var clicks = 0
        var reflects = 0
        var captures = 0
        var gate: CompletableDeferred<Unit>? = null
        val live = mutableSetOf<String>()
        override suspend fun observe() = Observation("f${++captures}", page, "test.app", 1).also { live.add(it.token) }
        override suspend fun suggest(goal: String, history: List<RecentEvent>, screen: Observation): Suggestion {
            gate?.await()
            return Suggestion("点击通讯录", "one-click", "target")
        }
        override fun dispatchOnce(screen: Observation, suggestion: Suggestion): Deferred<Boolean> {
            clicks++; return CompletableDeferred(true)
        }
        override suspend fun reflect(before: Observation, after: Observation, suggestion: Suggestion, goal: String): String {
            reflects++; return "A:verified"
        }
        override fun release(screen: Observation) { live.remove(screen.token) }
    }
    private class Fixture(scope: CoroutineScope, timeout: Long = 45000) {
        val port = Port()
        val events = mutableListOf<AssistEvent>()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val engine = BAssistEngine(port, CoroutineScope(scope.coroutineContext + job), object : AssistListener {
            override fun onPrompt(prompt: AssistPrompt) {}
            override fun onEvent(event: AssistEvent) { events.add(event) }
        }, { System.nanoTime() / 1000000 }, timeout)
        init { engine.startObserving(); engine.onBHelpRequested("task") }
        suspend fun propose() {
            val p = engine.currentPrompt!!
            engine.onBSpeechResult("task", p.promptId, "B", "我想打开通讯录")
            yield()
            assertEquals(PromptKind.ACTION, engine.currentPrompt!!.kind)
        }
        fun confirm() { engine.onBSpeechResult("task", engine.currentPrompt!!.promptId, "B", "对") }
        fun close() { engine.stopObserving(); job.cancel() }
    }

    @Test fun confirmedStepDispatchesOnceReflectsAndReleases() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            repeat(3) { f.engine.onBSpeechResult("task", p.promptId, "B", "对") }
            delay(900)
            assertEquals(1, f.port.clicks); assertEquals(1, f.port.reflects)
            assertTrue(f.port.live.isEmpty()); assertNull(f.engine.currentPrompt)
            assertTrue(f.events.any { it.detail == "single_step_complete" })
        } finally { f.close() }
    }
    @Test fun aPreemptsBeforeDispatchAndNeverResumesOldConfirmation() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            f.confirm(); f.engine.onATaskStarted(); yield()
            f.engine.onATaskFinished()
            f.engine.onBSpeechResult("task", p.promptId, "B", "对"); yield()
            assertEquals(0, f.port.clicks); assertTrue(f.port.live.isEmpty())
        } finally { f.close() }
    }
    @Test fun aCancelsModelRequestAndLateModelCannotInstallPrompt() = runBlocking {
        val f = Fixture(this)
        try {
            f.port.gate = CompletableDeferred()
            val p = f.engine.currentPrompt!!
            f.engine.onBSpeechResult("task", p.promptId, "B", "我想打开通讯录"); yield()
            f.engine.onATaskStarted(); f.port.gate!!.complete(Unit); yield()
            assertNull(f.engine.currentPrompt); assertEquals(0, f.port.clicks); assertTrue(f.port.live.isEmpty())
        } finally { f.close() }
    }
    @Test fun changedPageNeverDispatches() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); f.port.page = "changed"; f.confirm(); yield()
            assertEquals(0, f.port.clicks); assertTrue(f.events.any { it.detail == "stale_page" })
            assertTrue(f.port.live.isEmpty())
        } finally { f.close() }
    }
    @Test fun wrongTaskPromptButtonAndAmbiguousSpeechNeverDispatch() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            f.engine.onBSpeechResult("old-task", p.promptId, "B", "对")
            f.engine.onBSpeechResult("task", "old-prompt", "B", "对")
            f.engine.onBSpeechResult("task", p.promptId, "A", "对")
            f.engine.onBSpeechResult("task", p.promptId, "B", "嗯嗯"); yield()
            assertEquals(0, f.port.clicks); assertEquals(p, f.engine.currentPrompt)
        } finally { f.close() }
    }
    @Test fun clarificationReleasesCandidateAndRequestsNewGoal() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            f.engine.onBSpeechResult("task", p.promptId, "B", "不是这个")
            assertEquals(PromptKind.GOAL, f.engine.currentPrompt!!.kind)
            assertNotEquals(p.promptId, f.engine.currentPrompt!!.promptId)
            assertTrue(f.port.live.isEmpty()); assertEquals(0, f.port.clicks)
        } finally { f.close() }
    }
    @Test fun refusalNeverDispatches() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            f.engine.onBSpeechResult("task", p.promptId, "B", "不用了"); yield()
            assertNull(f.engine.currentPrompt); assertTrue(f.port.live.isEmpty()); assertEquals(0, f.port.clicks)
        } finally { f.close() }
    }
    @Test fun modelSuggestionAloneNeverDispatchesEvenWhenWaiting() = runBlocking {
        val f = Fixture(this)
        try {
            f.propose(); delay(950)
            assertEquals(0, f.port.clicks); assertEquals(0, f.port.reflects)
            assertEquals(PromptKind.ACTION, f.engine.currentPrompt!!.kind)
        } finally { f.close() }
    }
    @Test fun expiryRejectsOldConfirmationAndReleases() = runBlocking {
        val f = Fixture(this, 50)
        try {
            f.propose(); val p = f.engine.currentPrompt!!
            delay(80)
            f.engine.onBSpeechResult("task", p.promptId, "B", "对"); yield()
            assertEquals(0, f.port.clicks); assertTrue(f.port.live.isEmpty())
        } finally { f.close() }
    }
    @Test fun httpIsDebugAndLanOptInOnlyForOcrAndAgent() {
        val lan = "http://192.168.1.17:8001"
        assertEquals("$lan/", OcrProtocol.endpoint(lan, true, true).toString())
        for ((optIn, debug) in listOf(false to true, true to false, false to false)) {
            assertThrows(IllegalArgumentException::class.java) { OcrProtocol.endpoint(lan, optIn, debug) }
            assertThrows(IllegalArgumentException::class.java) { BAgentConfig.validate("$lan/v1", "vision-model", "", optIn, debug) }
        }
        assertEquals("$lan/v1", BAgentConfig.validate("$lan/v1", "vision-model", "", true, true))
        assertThrows(IllegalArgumentException::class.java) { BAgentConfig.validate("http://example.com/v1", "m", "", true, true) }
        assertEquals("https://example.com/v1", BAgentConfig.validate("https://example.com/v1", "m", "", false, false))
        assertThrows(IllegalArgumentException::class.java) { BAgentConfig.validate("https://example.com/v1", "", "", false, false) }
        assertThrows(IllegalArgumentException::class.java) { BAgentConfig.validate("https://example.com/v1", "m", "bad\nsecret", false, false) }
    }
}
