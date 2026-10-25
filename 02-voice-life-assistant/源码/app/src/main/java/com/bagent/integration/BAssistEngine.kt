package com.bagent.integration

import kotlinx.coroutines.*
import java.util.UUID

/** Minimal replacement for upstream AgentLoop scheduling. All entry points and callbacks must
 * use one dispatcher (Android wrapper enforces Main). There is deliberately no execution loop. */
class BAssistEngine(
    private val port: SingleStepPort,
    private val scope: CoroutineScope,
    private val listener: AssistListener,
    private val now: () -> Long,
    private val timeoutMs: Long = 45_000L,
    private val detector: DifficultyDetector = DifficultyDetector()
) {
    private data class Pending(val prompt: AssistPrompt, val goal: String, val screen: Observation?, val step: Suggestion?)
    private var pending: Pending? = null
    private var work: Job? = null
    private var expiry: Job? = null
    private var epoch = 0L
    private var observing = false
    private var aActive = false
    private var task = ""
    private var lastGoal = ""
    val currentPrompt: AssistPrompt? get() = pending?.prompt

    fun startObserving() { observing = true; emit("observing") }
    fun stopObserving() { observing = false; detector.clear(); cancelAssist("observation_stopped") }
    fun onEvent(event: RecentEvent) {
        if (!observing || aActive) return
        val reason = detector.add(event)
        if (reason != null && pending == null && work?.isActive != true) {
            task = UUID.randomUUID().toString(); lastGoal = ""
            emit("difficulty_suspected", reason)
            askGoal("您可能遇到了操作困难，需要我帮您做什么？")
        }
    }
    fun onBHelpRequested(taskId: String) {
        if (!observing || aActive || taskId.isBlank()) { emit("ignored", "inactive_or_A_active"); return }
        invalidate(); task = taskId; lastGoal = ""
        askGoal("我在，请告诉我您想在当前页面做什么。")
    }
    fun onBSpeechResult(taskId: String, promptId: String, sourceButton: String, text: String) {
        val p = pending
        if (!observing || aActive || sourceButton != "B" || p == null ||
            p.prompt.taskId != taskId || p.prompt.promptId != promptId || now() >= p.prompt.expiresAtMs) {
            if (p != null && now() >= p.prompt.expiresAtMs) cancelAssist("timeout")
            emit("speech_ignored", "invalid_task_prompt_source_or_timeout", taskId, promptId); return
        }
        val utterance = text.trim().trim('。', '！', '!', '？', '?', '，', ',', ' ')
        when {
            utterance in setOf("不用了", "取消", "停止", "不用", "不需要", "不要", "不") -> {
                cancelAssist("user_cancelled"); emit("speech_handled", "cancelled", taskId, promptId)
            }
            utterance == "不是这个" -> {
                invalidate(); askGoal("好的，没有执行。请告诉我您具体想做什么。")
                emit("speech_handled", "clarification_required", taskId, promptId)
            }
            utterance == "对" -> {
                if (p.prompt.kind != PromptKind.ACTION || p.screen == null || p.step == null) {
                    emit("speech_ignored", "goal_required", taskId, promptId); return
                }
                // Consume BEFORE any suspension: duplicate prompt_id cannot schedule a second action.
                pending = null; expiry?.cancel(); expiry = null
                val generation = ++epoch
                val job = scope.launch(start = CoroutineStart.LAZY) { executeConfirmed(p, generation) }
                // Also release when cancellation happens before the coroutine body ever starts.
                job.invokeOnCompletion { port.release(p.screen) }
                work = job
                job.start()
            }
            isGoal(utterance) -> {
                invalidate(); lastGoal = utterance
                val generation = epoch
                val history = detector.recent()
                work = scope.launch {
                    var screen: Observation? = null
                    try {
                        screen = port.observe()
                        val step = port.suggest(utterance, history, screen)
                        ensureActiveGeneration(generation)
                        require(step.description.isNotBlank() && step.actionJson.isNotBlank() && step.targetKey.isNotBlank()) { "ambiguous_suggestion" }
                        val owned = screen
                        screen = null
                        install(Pending(prompt(PromptKind.ACTION, "${step.description}。要我只执行这一步吗？请说“对”确认。"), utterance, owned, step))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (generation == epoch) { emit("analysis_failed", e.message.orEmpty()); emit("stopped", "analysis_failed") } }
                    finally { screen?.let(port::release) }
                }
            }
            else -> emit("speech_ignored", "unrecognized_no_action", taskId, promptId)
        }
    }
    fun onATaskStarted() { aActive = true; detector.clear(); cancelAssist("A_started"); emit("paused", "A_active") }
    fun onATaskFinished() { aActive = false; emit("observing", "A_finished_no_auto_resume") }
    fun cancelAssist(reason: String = "cancelled") { invalidate(); lastGoal = ""; emit("stopped", reason) }

    private suspend fun executeConfirmed(p: Pending, generation: Long) {
        var fresh: Observation? = null
        var after: Observation? = null
        var dispatched = false
        try {
            fresh = port.observe()
            ensureActiveGeneration(generation)
            if (fresh.fingerprint != p.screen!!.fingerprint || fresh.packageName != p.screen.packageName || fresh.windowId != p.screen.windowId) {
                emit("stopped", "stale_page"); return
            }
            // No suspension between final gate and physical dispatch on the serialized dispatcher.
            ensureActiveGeneration(generation)
            val completion = port.dispatchOnce(fresh, p.step!!)
            dispatched = true
            emit("action_dispatched", p.step.description, p.prompt.taskId, p.prompt.promptId)
            check(completion.await()) { "platform_action_rejected" }
            delay(800)
            ensureActiveGeneration(generation)
            after = port.observe()
            val outcome = port.reflect(fresh, after, p.step, p.goal)
            ensureActiveGeneration(generation)
            emit("execution_result", outcome, p.prompt.taskId, p.prompt.promptId)
            emit("stopped", "single_step_complete", p.prompt.taskId, p.prompt.promptId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (generation == epoch) {
                emit("execution_result", if (dispatched) "dispatched_unverified:${e.message}" else "not_dispatched:${e.message}", p.prompt.taskId, p.prompt.promptId)
                emit("stopped", "execution_finished")
            }
        } finally {
            fresh?.let(port::release); after?.let(port::release)
        }
    }
    private fun ensureActiveGeneration(generation: Long) {
        if (aActive || !observing || generation != epoch) throw CancellationException("B invalidated")
    }
    private fun isGoal(text: String): Boolean = text.length in 3..500 &&
        listOf("我想", "我要", "帮我", "请", "打开", "返回", "找到", "查看", "点击", "输入", "怎么", "如何").any { text.startsWith(it) }
    private fun prompt(kind: PromptKind, text: String) = AssistPrompt(task, UUID.randomUUID().toString(), kind, text, now() + timeoutMs)
    private fun askGoal(text: String) = install(Pending(prompt(PromptKind.GOAL, text), lastGoal, null, null))
    private fun install(p: Pending) {
        pending = p
        expiry?.cancel()
        expiry = scope.launch { delay(timeoutMs); if (pending?.prompt?.promptId == p.prompt.promptId) cancelAssist("timeout") }
        listener.onPrompt(p.prompt)
    }
    private fun invalidate() {
        epoch++; work?.cancel(); work = null; expiry?.cancel(); expiry = null
        pending?.screen?.let(port::release); pending = null
    }
    private fun emit(type: String, detail: String = "", taskId: String = task, promptId: String = pending?.prompt?.promptId.orEmpty()) =
        listener.onEvent(AssistEvent(type, taskId, promptId, detail))
}
