package cn.seniorvoice.demo.nextaction.domain

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One trigger -> one capture -> at most one prediction -> at most one executor call -> stop. */
class SingleStepCoordinator(
    private val observationSource: ObservationSource,
    private val predictionService: PredictionService,
    private val actionExecutor: ActionExecutor,
    private val clock: AgentClock,
    private val listener: (AgentDiagnostic) -> Unit = {},
    private val policy: ActionPolicy = ActionPolicy(),
    private val timeoutMs: Long = 20000,
    private val debounceMs: Long = 750,
    private val mockPackageName: String = "cn.seniorvoice.demo",
    @Suppress("UNUSED_PARAMETER") mockActivityClassName: String = "cn.seniorvoice.demo.nextaction.NextActionPracticeActivity"
) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "next-action-step").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "next-action-deadline").apply { isDaemon = true } }
    private val cancellationWorker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "next-action-cancel").apply { isDaemon = true } }
    private val gate = ExecutionGate(clock)
    private val generator = CandidateGenerator(policy)
    private val resolver = TargetResolver()
    private var current: Session? = null
    private var closed = false
    private var lastAcceptedAt: Long? = null

    private class Session(
        val requestId: String, val source: TriggerSource, val startedMs: Long,
        val deadlineMs: Long, val permit: ExecutionGate.Permit
    ) {
        var state = AgentState.CAPTURING
        var packageName: String? = null
        var candidates: List<Candidate> = emptyList()
        var decision: Decision? = null
        var predictionElapsedMs: Long? = null
        var task: Future<*>? = null
        var timeout: ScheduledFuture<*>? = null
    }

    init { require(timeoutMs > 0 && debounceMs >= 0) }

    val isBusy: Boolean get() = synchronized(lock) { current != null }
    /** True while one logical request is active (including capture/predict/validate/execute). */
    val isActive: Boolean get() = isBusy

    fun trigger(source: TriggerSource): TriggerResult = synchronized(lock) {
        if (closed) return TriggerResult(false, null, "Agent 已关闭")
        if (current != null) return TriggerResult(false, current?.requestId, "上一项请求仍在进行，未重复触发")
        val now = clock.nowMs()
        if (lastAcceptedAt?.let { now - it < debounceMs } == true) return TriggerResult(false, null, "触发过快，已去抖")
        val id = UUID.randomUUID().toString()
        val deadline = if (Long.MAX_VALUE - now < timeoutMs) Long.MAX_VALUE else now + timeoutMs
        val session = Session(id, source, now, deadline, gate.createPermit(id, deadline))
        current = session
        lastAcceptedAt = now
        emit(session, "正在采集当前页面")
        session.timeout = scheduler.schedule({ expire(session) }, timeoutMs, TimeUnit.MILLISECONDS)
        session.task = worker.submit { runStep(session) }
        TriggerResult(true, id, "已触发一次 AI 补全")
    }

    fun cancel(reason: String = "用户取消") {
        val session = synchronized(lock) {
            val active = current ?: return
            terminateLocked(active, "已取消：$reason")
            active
        }
        session.task?.cancel(true)
        cancelPrediction(session.requestId)
    }

    private fun expire(session: Session) {
        val expired = synchronized(lock) {
            if (current !== session) false
            else { terminateLocked(session, "请求超过 ${timeoutMs}ms，已终止；迟到结果不会执行"); true }
        }
        if (expired) {
            session.task?.cancel(true)
            cancelPrediction(session.requestId)
        }
    }

    private fun cancelPrediction(requestId: String) {
        runCatching { cancellationWorker.execute { runCatching { predictionService.cancel(requestId) } } }
    }

    private fun runStep(session: Session) {
        try {
            if (!isLive(session)) return
            // Own defensive copies: a platform callback must not mutate a request already sent for prediction.
            val raw = observationSource.capture(session.requestId)
            val captured = raw.copy(
                snapshot = raw.snapshot.copy(nodes = raw.snapshot.nodes.toList()),
                screenshot = raw.screenshot.copy(jpeg = raw.screenshot.jpeg.copyOf()),
                history = raw.history.toList()
            )
            if (!isLive(session)) return
            validateObservation(captured, session)
            val snapshot = captured.snapshot
            val candidates = generator.generate(snapshot)
            synchronized(lock) {
                if (current !== session) return
                session.packageName = snapshot.packageName
                session.candidates = candidates
            }
            if (!transition(session, AgentState.CAPTURING, AgentState.PREDICTING, "正在预测唯一一个安全下一步")) return
            val predictionStartedMs = clock.nowMs()
            val response = try {
                predictionService.predict(ObservationRequest(session.requestId, captured, candidates))
            } finally {
                val predictionElapsedMs = (clock.nowMs() - predictionStartedMs).coerceAtLeast(0)
                synchronized(lock) {
                    // A late completion must not alter a cancelled/expired request or a newer session.
                    if (current === session) session.predictionElapsedMs = predictionElapsedMs
                }
            }
            if (!isLive(session)) return
            if (!transition(session, AgentState.PREDICTING, AgentState.VALIDATING, "正在本地复核预测")) return
            val selected = validateResponse(response, session, snapshot)
            synchronized(lock) { if (current === session) session.decision = response.decision }
            if (selected.action == ActionType.NO_OP) {
                complete(session, "模型选择不操作：${response.decision.reason}", ExecutionResult(false, true, "NO_OP"))
                return
            }
            // This worker check is repeated in the Android executor immediately before performAction.
            val latest = observationSource.currentSnapshot()
            require(resolver.isSamePage(snapshot, latest) && observationSource.currentRevision() == snapshot.revision) { "页面已变化，本次不操作" }
            require(policy.allows(selected, latest)) { "目标已消失、不可操作或未通过本地风险策略" }
            if (!transition(session, AgentState.VALIDATING, AgentState.EXECUTING, "仅尝试执行这一个动作")) return
            val execution = actionExecutor.execute(ExecutionRequest(session.requestId, snapshot, selected, session.deadlineMs, session.permit))
            if (!isLive(session)) return
            require(!execution.attempted || session.permit.wasClaimed) { "执行器未取得单次许可；已终止，不重试" }
            complete(session, execution.message, execution)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) {
                complete(session, "运行环境错误，已终止")
                throw error
            }
            complete(session, error.message?.take(300) ?: "请求失败，已终止，不重试")
        }
    }

    private fun validateObservation(captured: CapturedObservation, session: Session) {
        val snapshot = captured.snapshot
        require(snapshot.captureId.isNotBlank() && snapshot.packageName.isNotBlank() && snapshot.uiFingerprint.isNotBlank()) { "快照缺少身份信息" }
        require(snapshot.nodes.isNotEmpty() && snapshot.nodes.size <= AgentLimits.MAX_NODES) { "UI 节点为空或超过 ${AgentLimits.MAX_NODES} 个" }
        require(snapshot.nodes.map { it.nodeKey }.distinct().size == snapshot.nodes.size) { "UI 节点标识不唯一" }
        require(snapshot.screenWidth > 0 && snapshot.screenHeight > 0 && snapshot.windowId >= 0) { "页面尺寸或窗口无效" }
        require(snapshot.capturedAtMs >= session.startedMs && snapshot.capturedAtMs <= clock.nowMs()) { "采集时间戳无效或快照过期" }
        require(captured.screenshot.jpeg.size in 4..AgentLimits.MAX_JPEG_BYTES &&
            captured.screenshot.jpeg[0] == 0xff.toByte() && captured.screenshot.jpeg[1] == 0xd8.toByte()) { "截图为空、不是 JPEG 或超过大小限制" }
        require(captured.screenshot.width > 0 && captured.screenshot.height > 0) { "截图尺寸无效" }
        require(captured.history.size <= AgentLimits.MAX_HISTORY) { "操作历史超过 ${AgentLimits.MAX_HISTORY} 条" }
        require(policy.allowsObservation(snapshot)) { "当前页面包含敏感信息，未上传、未操作" }
        require(observationSource.currentRevision() == snapshot.revision) { "采集期间页面变化，本次不操作" }
        if (predictionService.isMock || session.source == TriggerSource.MOCK_BUTTON) {
            require(snapshot.packageName == mockPackageName && snapshot.nodes.any {
                it.viewId == AgentLimits.PRACTICE_ROOT_ID && it.visible && it.enabled
            }) { "Mock 只允许在专用练习页面使用，不能操作其他页面" }
        }
    }

    private fun validateResponse(response: PredictionResponse, session: Session, snapshot: UiSnapshot): Candidate {
        require(response.schemaVersion == AgentLimits.SCHEMA_VERSION && response.requestId == session.requestId && response.captureId == snapshot.captureId) { "模型响应版本、请求或采集标识不匹配" }
        val decision = response.decision
        require(decision.confidence.isFinite() && decision.confidence in 0.0..1.0 && decision.reason.length <= 1000) { "模型置信度或理由无效" }
        val candidate = session.candidates.singleOrNull { it.candidateId == decision.candidateId } ?: error("模型返回非法 candidate_id")
        require(candidate.action == decision.action) { "模型动作与候选动作不一致" }
        require(candidate.action == ActionType.NO_OP || decision.confidence >= 0.75) { "模型不够确定，本次不操作" }
        require(policy.allows(candidate, snapshot)) { "候选动作未通过本地风险策略" }
        if (predictionService.isMock || session.source == TriggerSource.MOCK_BUTTON) {
            require(candidate.action in setOf(ActionType.TAP, ActionType.NO_OP)) { "Mock 仅允许练习页面安全点击或不操作" }
        }
        return candidate
    }

    private fun isLive(session: Session): Boolean = synchronized(lock) {
        if (closed || current !== session) false
        else if (clock.nowMs() >= session.deadlineMs) {
            terminateLocked(session, "请求已超时，结果不会执行")
            cancelPrediction(session.requestId)
            false
        } else true
    }

    private fun transition(session: Session, expected: AgentState, next: AgentState, message: String): Boolean = synchronized(lock) {
        if (!isLive(session)) return false
        check(session.state == expected) { "非法 Agent 状态转换：${session.state} -> $next" }
        session.state = next
        emit(session, message)
        true
    }

    private fun complete(session: Session, message: String, result: ExecutionResult? = null) = synchronized(lock) {
        if (current === session) terminateLocked(session, message, result)
    }

    private fun terminateLocked(session: Session, message: String, result: ExecutionResult? = null) {
        session.permit.cancel()
        session.timeout?.cancel(false)
        session.state = AgentState.FINISHED
        emit(session, message, result)
        current = null
        session.state = AgentState.IDLE
        emit(session, "已结束：$message；等待下一次手动触发", result)
    }

    /** Observability must not throw into the action pipeline or retain screenshots in diagnostics. */
    private fun emit(session: Session, message: String, result: ExecutionResult? = null) {
        runCatching { listener(AgentDiagnostic(session.state, session.source, session.requestId, message,
            session.packageName, session.candidates, session.decision,
            (clock.nowMs() - session.startedMs).coerceAtLeast(0), result, session.predictionElapsedMs)) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        cancel("Agent 关闭")
        worker.shutdownNow()
        scheduler.shutdownNow()
        cancellationWorker.shutdown()
    }
}
