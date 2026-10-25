package cn.seniorvoice.demo.nextaction

import cn.seniorvoice.demo.nextaction.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SingleStepCoordinatorTest {
    private class TestClock : AgentClock {
        val time = AtomicLong(1000)
        override fun nowMs() = time.get()
    }
    private fun node(key: String = "node-1", label: String = "下一页", scrollable: Boolean = false,
                     viewId: String? = "sample:id/next", clickable: Boolean = !scrollable) =
        UiNodeSnapshot(key, if (scrollable) "android.widget.ScrollView" else "android.widget.Button", viewId,
            label, Bounds(10, 30, 250, 150), true, true, clickable, scrollable, false, false)

    private fun snapshot(nodes: List<UiNodeSnapshot> = listOf(node())) = UiSnapshot(
        "capture-1", "com.example.sample", 7, 1000, "fingerprint-1", 10, 1080, 1920, nodes
    )
    private class Source(var snapshot: UiSnapshot) : ObservationSource {
        var beforeCapture: (() -> Unit)? = null
        override fun capture(requestId: String): CapturedObservation {
            beforeCapture?.invoke()
            return CapturedObservation(snapshot, ScreenImage(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), 540, 960), emptyList())
        }
        override fun currentSnapshot() = snapshot
        override fun currentRevision() = snapshot.revision
    }
    private class Predictor(private val select: (ObservationRequest) -> Decision) : PredictionService {
        val calls = AtomicInteger()
        val cancels = AtomicInteger()
        var mock = false
        override val isMock get() = mock
        var beforeDecision: (() -> Unit)? = null
        var transform: (PredictionResponse) -> PredictionResponse = { it }
        override fun predict(request: ObservationRequest): PredictionResponse {
            calls.incrementAndGet()
            beforeDecision?.invoke()
            return transform(PredictionResponse(1, request.requestId, request.captured.snapshot.captureId, select(request)))
        }
        override fun cancel(requestId: String) { cancels.incrementAndGet() }
    }
    private fun decision(request: ObservationRequest, action: ActionType) = request.candidates.first { it.action == action }.let {
        Decision(action, it.candidateId, 0.98, "根据当前页面与操作历史选择一个安全动作")
    }
    private class Executor : ActionExecutor {
        val calls = AtomicInteger()
        val actions = mutableListOf<ActionType>()
        var fail = false
        var beforeClaim: (() -> Unit)? = null
        override fun execute(request: ExecutionRequest): ExecutionResult {
            calls.incrementAndGet()
            beforeClaim?.invoke()
            if (!request.permit.claim()) return ExecutionResult(false, false, "许可无效，未操作")
            synchronized(actions) { actions.add(request.candidate.action) }
            return ExecutionResult(true, !fail, if (fail) "执行失败，不重试" else "单次操作已提交")
        }
    }
    private fun awaitIdle(coordinator: SingleStepCoordinator) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (coordinator.isBusy && System.nanoTime() < deadline) Thread.sleep(2)
        assertFalse("请求应结束并释放互斥状态", coordinator.isBusy)
    }
    private fun waitFor(latch: CountDownLatch) = assertTrue("工作线程应及时到达测试屏障", latch.await(2, TimeUnit.SECONDS))
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        var finished = false
        while (!finished) try { latch.await(); finished = true } catch (_: InterruptedException) { /* simulate a late response from uncooperative I/O */ }
    }

    @Test fun tapBackAndBothScrollDirectionsEachPerformExactlyOneDecisionAndOneAction() {
        for (action in listOf(ActionType.TAP, ActionType.BACK, ActionType.SCROLL_UP, ActionType.SCROLL_DOWN)) {
            val source = Source(snapshot(listOf(node(), node("scroll", "列表", true, "sample:id/list"))))
            val predictor = Predictor { decision(it, action) }
            val executor = Executor()
            SingleStepCoordinator(source, predictor, executor, TestClock()).use { agent ->
                assertTrue(agent.trigger(TriggerSource.APP_BUTTON).accepted)
                awaitIdle(agent)
                assertEquals(1, predictor.calls.get())
                assertEquals(1, executor.calls.get())
                assertEquals(listOf(action), executor.actions)
                Thread.sleep(20)
                assertEquals("结束后不能自动预测下一步", 1, predictor.calls.get())
            }
        }
    }

    @Test fun noOpNeverInvokesExecutorAndStateMachineFinishesAtIdle() {
        val states = mutableListOf<AgentState>()
        val predictor = Predictor { decision(it, ActionType.NO_OP) }
        val executor = Executor()
        SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock(), listener = { synchronized(states) { states.add(it.state) } }).use { agent ->
            agent.trigger(TriggerSource.BLE)
            awaitIdle(agent)
            assertEquals(1, predictor.calls.get())
            assertEquals(0, executor.calls.get())
            assertEquals(listOf(AgentState.CAPTURING, AgentState.PREDICTING, AgentState.VALIDATING, AgentState.FINISHED, AgentState.IDLE), states)
        }
    }

    @Test fun simultaneousAndDebouncedTriggersDoNotCreateAdditionalPredictions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = TestClock()
        val predictor = Predictor { decision(it, ActionType.TAP) }.apply {
            beforeDecision = { entered.countDown(); awaitIgnoringInterrupt(release) }
        }
        val executor = Executor()
        SingleStepCoordinator(Source(snapshot()), predictor, executor, clock).use { agent ->
            assertTrue(agent.trigger(TriggerSource.BLE).accepted)
            waitFor(entered)
            val accepted = AtomicInteger()
            val threads = (1..12).map { Thread { if (agent.trigger(TriggerSource.APP_BUTTON).accepted) accepted.incrementAndGet() }.apply { start() } }
            threads.forEach { it.join(1000) }
            assertEquals(0, accepted.get())
            release.countDown()
            awaitIdle(agent)
            assertFalse(agent.trigger(TriggerSource.APP_BUTTON).accepted)
            assertEquals(1, predictor.calls.get())
            assertEquals(1, executor.calls.get())
        }
    }

    @Test fun deadlineInvalidatesLateUninterruptibleNetworkResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val predictor = Predictor { decision(it, ActionType.TAP) }.apply {
            beforeDecision = { entered.countDown(); awaitIgnoringInterrupt(release); returned.countDown() }
        }
        val executor = Executor()
        SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock(), timeoutMs = 200).use { agent ->
            agent.trigger(TriggerSource.BLE)
            waitFor(entered)
            awaitIdle(agent)
            release.countDown()
            waitFor(returned)
            Thread.sleep(30)
            assertEquals(1, predictor.calls.get())
            assertEquals(0, executor.calls.get())
        }
    }

    @Test fun userPageChangeCancellationDiscardsLateResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val source = Source(snapshot())
        val predictor = Predictor { decision(it, ActionType.TAP) }.apply {
            beforeDecision = { entered.countDown(); awaitIgnoringInterrupt(release); returned.countDown() }
        }
        val executor = Executor()
        SingleStepCoordinator(source, predictor, executor, TestClock()).use { agent ->
            agent.trigger(TriggerSource.APP_BUTTON)
            waitFor(entered)
            source.snapshot = source.snapshot.copy(revision = 11, uiFingerprint = "another")
            agent.cancel("用户手动改变页面")
            release.countDown()
            waitFor(returned)
            Thread.sleep(20)
            assertFalse(agent.isActive)
            assertEquals(0, executor.calls.get())
        }
    }

    @Test fun changedFingerprintOrVanishedNodeRejectsExecutionEvenWithoutCancelCallback() {
        for (change in listOf<(UiSnapshot) -> UiSnapshot>(
            { it.copy(uiFingerprint = "new-page") }, { it.copy(nodes = emptyList()) }, { it.copy(packageName = "com.example.other") }
        )) {
            val source = Source(snapshot())
            val predictor = Predictor { decision(it, ActionType.TAP) }.apply { beforeDecision = { source.snapshot = change(source.snapshot) } }
            val executor = Executor()
            SingleStepCoordinator(source, predictor, executor, TestClock()).use { agent ->
                agent.trigger(TriggerSource.BLE)
                awaitIdle(agent)
                assertEquals(0, executor.calls.get())
            }
        }
    }

    @Test fun invalidIdMismatchedActionAndCrossRequestResponseCannotExecute() {
        val transforms = listOf<(PredictionResponse) -> PredictionResponse>(
            { it.copy(decision = it.decision.copy(candidateId = "fabricated")) },
            { it.copy(decision = it.decision.copy(action = ActionType.BACK)) },
            { it.copy(requestId = "another-request") }, { it.copy(captureId = "another-capture") },
            { it.copy(schemaVersion = 99) }, { it.copy(decision = it.decision.copy(confidence = Double.NaN)) }
        )
        for (change in transforms) {
            val predictor = Predictor { decision(it, ActionType.TAP) }.apply { transform = change }
            val executor = Executor()
            SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock()).use { agent ->
                agent.trigger(TriggerSource.APP_BUTTON)
                awaitIdle(agent)
                assertEquals(1, predictor.calls.get())
                assertEquals(0, executor.calls.get())
            }
        }
    }

    @Test fun failedExecutionNeverRetriesOrChoosesAnotherCandidate() {
        val predictor = Predictor { decision(it, ActionType.TAP) }
        val executor = Executor().apply { fail = true }
        SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock()).use { agent ->
            agent.trigger(TriggerSource.BLE)
            awaitIdle(agent)
            assertEquals(1, predictor.calls.get())
            assertEquals(1, executor.calls.get())
            assertEquals(1, executor.actions.size)
        }
    }

    @Test fun cancellationAtFinalExecutorBoundaryPreventsPlatformAction() {
        val predictor = Predictor { decision(it, ActionType.TAP) }
        val executor = Executor()
        SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock()).use { agent ->
            executor.beforeClaim = { agent.cancel("最终执行前页面变化") }
            agent.trigger(TriggerSource.BLE)
            awaitIdle(agent)
            assertEquals(1, executor.calls.get())
            assertTrue(executor.actions.isEmpty())
        }
    }

    @Test fun clockExpiryBeforeClaimPreventsActionEvenWhenTimerHasNotRun() {
        val clock = TestClock()
        val predictor = Predictor { decision(it, ActionType.TAP) }
        val executor = Executor().apply { beforeClaim = { clock.time.set(22000) } }
        SingleStepCoordinator(Source(snapshot()), predictor, executor, clock).use { agent ->
            agent.trigger(TriggerSource.APP_BUTTON)
            awaitIdle(agent)
            assertTrue(executor.actions.isEmpty())
        }
    }

    @Test fun sensitiveScreenAndEmptyUiStopBeforeAnyModelCall() {
        for (nodes in listOf(emptyList(), listOf(node(label = "支付密码")), listOf(node().copy(password = true)))) {
            val predictor = Predictor { decision(it, ActionType.TAP) }
            val executor = Executor()
            SingleStepCoordinator(Source(snapshot(nodes)), predictor, executor, TestClock()).use { agent ->
                agent.trigger(TriggerSource.APP_BUTTON)
                awaitIdle(agent)
                assertEquals(0, predictor.calls.get())
                assertEquals(0, executor.calls.get())
            }
        }
    }

    @Test fun mockCannotPredictOrExecuteOnThirdPartyPage() {
        val predictor = Predictor { decision(it, ActionType.TAP) }.apply { mock = true }
        val executor = Executor()
        SingleStepCoordinator(Source(snapshot()), predictor, executor, TestClock()).use { agent ->
            agent.trigger(TriggerSource.MOCK_BUTTON)
            awaitIdle(agent)
            assertEquals(0, predictor.calls.get())
            assertEquals(0, executor.calls.get())
        }
    }

    @Test fun mockSafeClickWorksOnlyWithDedicatedPracticeRootAndPackage() {
        val root = node("practice-root", "练习界面", viewId = AgentLimits.PRACTICE_ROOT_ID, clickable = false)
        val source = Source(snapshot(listOf(root, node(label = "测试下一页"))).copy(packageName = "cn.seniorvoice.demo"))
        val predictor = Predictor { decision(it, ActionType.TAP) }.apply { mock = true }
        val executor = Executor()
        SingleStepCoordinator(source, predictor, executor, TestClock()).use { agent ->
            agent.trigger(TriggerSource.MOCK_BUTTON)
            awaitIdle(agent)
            assertEquals(listOf(ActionType.TAP), executor.actions)
        }
    }

    @Test fun captureAndPredictionExceptionsReleaseBusyStateAndListenerCannotBreakPipeline() {
        val source = Source(snapshot())
        val predictor = Predictor { decision(it, ActionType.TAP) }
        val clock = TestClock()
        val executor = Executor()
        SingleStepCoordinator(source, predictor, executor, clock, listener = { error("broken diagnostic sink") }).use { agent ->
            source.beforeCapture = { error("capture failed") }
            agent.trigger(TriggerSource.BLE)
            awaitIdle(agent)
            clock.time.set(2000)
            source.snapshot = source.snapshot.copy(capturedAtMs = 2000)
            source.beforeCapture = null
            predictor.beforeDecision = { error("network failed") }
            assertTrue(agent.trigger(TriggerSource.APP_BUTTON).accepted)
            awaitIdle(agent)
            assertEquals(1, predictor.calls.get())
            assertEquals(0, executor.calls.get())
        }
    }

    @Test fun finalIdleDiagnosticRetainsFailureReasonAfterStateIsReleased() {
        val diagnostics = mutableListOf<AgentDiagnostic>()
        val source = Source(snapshot()).apply { beforeCapture = { error("截图失败：安全窗口") } }
        val predictor = Predictor { decision(it, ActionType.NO_OP) }
        val executor = Executor()
        SingleStepCoordinator(source, predictor, executor, TestClock(), listener = { diagnostics.add(it) }).use { agent ->
            agent.trigger(TriggerSource.APP_BUTTON)
            awaitIdle(agent)
            val last = diagnostics.last()
            assertEquals(AgentState.IDLE, last.state)
            assertTrue(last.message.contains("截图失败：安全窗口"))
            assertEquals(0, predictor.calls.get())
            assertEquals(0, executor.calls.get())
        }
    }

    @Test fun predictionDurationIsSeparateFromTotalAndRetainedForSuccessAndFailure() {
        for (predictionFails in listOf(false, true)) {
            val clock = TestClock()
            val diagnostics = mutableListOf<AgentDiagnostic>()
            val source = Source(snapshot().copy(capturedAtMs = 1050)).apply {
                beforeCapture = { clock.time.set(1050) }
            }
            val predictor = Predictor { decision(it, ActionType.NO_OP) }.apply {
                beforeDecision = {
                    clock.time.set(1367)
                    if (predictionFails) error("prediction failed")
                }
            }
            SingleStepCoordinator(source, predictor, Executor(), clock, listener = { diagnostics.add(it) }).use { agent ->
                agent.trigger(TriggerSource.APP_BUTTON)
                awaitIdle(agent)
                assertEquals(1, predictor.calls.get())
                assertEquals(listOf(AgentState.FINISHED, AgentState.IDLE), diagnostics.takeLast(2).map { it.state })
                for (terminal in diagnostics.takeLast(2)) {
                    assertEquals(367L, terminal.elapsedMs)
                    assertEquals(317L, terminal.predictionElapsedMs)
                }
                assertTrue(diagnostics.filter { it.state == AgentState.CAPTURING || it.state == AgentState.PREDICTING }
                    .all { it.predictionElapsedMs == null })
            }
        }
    }
}
