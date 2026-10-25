package cn.seniorvoice.demo.assist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.seniorvoice.demo.BuildConfig
import com.bagent.android.BAssist
import com.bagent.integration.*
import com.mobileagent.app.api.ApiConfig
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.domain.UiSnapshot
import cn.seniorvoice.demo.nextaction.observation.AccessibilityBridge
import cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Host B routing, dwell detection and one-step Agent lifecycle, serialized on Main. */
class BAssistController(private val service: NextActionAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val bridge = AccessibilityBridge(service)
    private val vision = VisionClient()
    private var detector = PageDwellDetector(AssistRuntime.dwellSeconds * 1000L)
    private var overlay: LinearLayout? = null
    private var aContactOverlay: LinearLayout? = null
    private var expectedPage: String? = null
    private var expectedPackage: String? = null
    private var busy = false
    private var serial = 0L
    private var future: Future<*>? = null
    private var closed = false
    private var agent: BAssist? = null
    private var agentPrompt: AssistPrompt? = null
    private var suppressAgentEvents = false
    private var executing = false
    private var goalQuestion = "需要我的帮助吗？"
    private var agentResult = ""
    private var agentEventId = ""
    private var agentRecording: RecordingEnvelope? = null
    private val timeout = Runnable { cancel("本次帮助已超时") }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (service.isDeviceLocked()) {
                if (AssistRuntime.session.bState != AssistSession.BState.OBSERVING) cancel("锁屏，结束本次帮助")
                detector.reset()
            } else if (!AssistRuntime.screenAllowed || NextActionRuntime.isBusy || AssistRuntime.session.aTask != null ||
                (overlay != null && AssistRuntime.session.bState == AssistSession.BState.OBSERVING)) {
                detector.reset()
            } else if (!busy && AssistRuntime.session.mayObserve(now())) sample()
            main.postDelayed(this, 2000)
        }
    }
    init { main.postDelayed(tick, 2000) }
    private fun now() = SystemClock.elapsedRealtime()

    fun event(event: AccessibilityEvent) {
        // Expected page changes after our one confirmed click belong to reflection.
        if (executing) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            AssistRuntime.session.bState != AssistSession.BState.OBSERVING &&
            event.packageName?.toString()?.let { it != service.packageName && it != expectedPackage } == true)
            cancel("页面已切换，结束本次帮助")
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            AssistRuntime.session.bState in setOf(AssistSession.BState.WAITING, AssistSession.BState.RECORDING, AssistSession.BState.THINKING))
            cancel("页面已滚动，请重新观察")
    }

    private fun sample() {
        val token = AssistRuntime.session.generation
        launch {
            val captured = bridge.capture("observe-${UUID.randomUUID()}")
            check(captured.snapshot.packageName != service.packageName) { "助手自身页面不触发帮助" }
            val pixels = fingerprint(captured.screenshot.jpeg)
            main.post {
                if (closed || !AssistRuntime.screenAllowed || !AssistRuntime.session.current(token) ||
                    !AssistRuntime.session.mayObserve(now())) return@post
                val stable = detector.sample(pageKey(captured.snapshot), pixels, now())
                AssistRuntime.update("b_status", if (stable) "页面持续停留，正在判断是否需要帮助" else "B 正在观察手机页面")
                if (stable) review(captured.snapshot, captured.screenshot.jpeg)
            }
        }
    }

    private fun review(snapshot: UiSnapshot, jpeg: ByteArray) {
        val token = AssistRuntime.session.check(now()) ?: return
        val requestTicket = vision.ticket()
        expectedPage = pageKey(snapshot); expectedPackage = snapshot.packageName
        if (AssistRuntime.bAgentEnabled) {
            startAgent(token)
            return
        }
        main.removeCallbacks(timeout); main.postDelayed(timeout, 60000)
        launch {
            val response = vision.call(AssistRuntime.baseUrl, AssistRuntime.apiToken, jpeg, expectedEpoch = requestTicket)
            VisionClient.scene(response) // Mock and failed cloud results never count as observed evidence.
            main.post {
                if (!valid(token) || !samePage()) { if (valid(token)) cancel("页面已变化，旧判断已清理"); return@post }
                val assistance = response.optJSONObject("analysis")?.optJSONArray("suggested_assistance")
                if (assistance == null || assistance.length() == 0) { cancel("本页暂未发现可提供的帮助"); return@post }
                ask(token, "需要我的帮助吗？")
            }
        }
    }

    private fun ask(token: Long, question: String, prompt: String = UUID.randomUUID().toString(), remainingMs: Long = 45000) {
        if (!valid(token)) return
        if (!AssistRuntime.session.offer(token, prompt)) return
        show(question, "按眼镜 B 按钮录音，告诉我你的想法。", "不需要") { cancel("用户暂不需要帮助") }
        if (!valid(token) || AssistRuntime.session.promptId != prompt) return
        AssistRuntime.send(JSONObject().put("v", 2).put("type", "assist_prompt").put("prompt_id", prompt)
            .put("task_id", "b-$prompt").put("question", question))
        main.removeCallbacks(timeout); main.postDelayed(timeout, remainingMs.coerceAtLeast(1))
        AssistRuntime.update("b_status", question)
        AssistRuntime.speakB(question)
        IntegrationLog.event("B", "prompt", mapOf("prompt_id" to prompt, "task_id" to "b-$prompt"))
    }

    fun recordingStarted() {
        AssistRuntime.stopSpeaking()
        show("正在接收 B 回答", "请对眼镜说话，结束录音后等待识别。", "取消") { cancel("用户取消 B") }
        main.removeCallbacks(timeout); main.postDelayed(timeout, 90000)
    }

    fun reply(envelope: RecordingEnvelope, token: Long, text: String) {
        if (!AssistRuntime.session.think(token, envelope.promptId.orEmpty())) return
        main.removeCallbacks(timeout)
        val bound = agentPrompt
        if (agent != null) {
            if (bound == null || bound.promptId != envelope.promptId || envelope.taskId != "b-${bound.promptId}") {
                cancel("B 回答不属于当前问题，已停止"); return
            }
            agentEventId = envelope.eventId
            agentRecording = envelope
            if (classifyReply(text) == ReplyKind.DECLINE) { cancel("好的，暂时不打扰你"); return }
            if (bound.kind == PromptKind.GOAL && classifyReply(text) == ReplyKind.ACCEPT) {
                goalQuestion = "你想完成什么？请说，例如：我想打开通讯录。"
                agent?.onBHelpRequested(bound.taskId)
                return
            }
            hide()
            val currentAgent = agent
            // Let the accessibility overlay disappear before collecting fresh evidence.
            main.postDelayed({
                if (!valid(token) || agent !== currentAgent || agentPrompt != bound) return@postDelayed
                if (now() >= bound.expiresAtMs) { cancel("B 确认已超时，请重新观察"); return@postDelayed }
                executing = bound.kind == PromptKind.ACTION && text.trim().trim('。', '！', '!', '？', '?', '，', ',', ' ') == "对"
                main.postDelayed(timeout, 180000)
                AssistRuntime.update("b_status", if (executing) "正在核对页面，最多执行一次点击" else "正在根据当前页面分析你的需求")
                currentAgent?.onBSpeechResult(bound.taskId, bound.promptId, "B", text)
            }, 400)
            return
        }
        when (classifyReply(text)) {
            ReplyKind.DECLINE -> cancel("好的，暂时不打扰你")
            ReplyKind.ACCEPT -> ask(token, "你想完成什么？")
            ReplyKind.GOAL -> {
                hide()
                main.postDelayed(timeout, 65000)
                AssistRuntime.update("b_status", "B 已识别：$text；正在结合当前页面判断")
                val checkSerial = serial
                val requestTicket = vision.ticket()
                main.postDelayed({
                    if (!valid(token) || serial != checkSerial) return@postDelayed
                    launch {
                        val captured = bridge.capture("b-${UUID.randomUUID()}")
                        check(pageKey(captured.snapshot) == expectedPage) { "页面已变化，请重新观察" }
                        // Existing /analyze accepts both the real phone screenshot and the user's text.
                        val response = vision.call(AssistRuntime.baseUrl, AssistRuntime.apiToken, captured.screenshot.jpeg, text, requestTicket)
                        val answer = VisionClient.answer(response)
                        main.post {
                            if (!valid(token) || !samePage()) { if (valid(token)) cancel("页面已变化，旧建议已清理"); return@post }
                            if (!AssistRuntime.session.result(token)) return@post
                            show("帮助建议", "你的问题：$text\n\n$answer\n\n本次只提供建议，请按建议操作手机。", "关闭") { cancel("本次帮助结束") }
                            if (!valid(token)) return@post
                            AssistRuntime.update("b_result", answer, text)
                            AssistRuntime.speakB(answer)
                            AssistRuntime.recordBResult(answer, text, envelope)
                            AssistRuntime.sendResult(envelope.eventId, "assist_advice", answer, envelope)
                            main.postDelayed(timeout, 60000)
                        }
                    }
                }, 400)
            }
        }
    }

    private fun startAgent(token: Long) {
        try {
            val endpoint = BAgentConfig.validate(AssistRuntime.bAgentUrl, AssistRuntime.bAgentModel,
                AssistRuntime.bAgentToken, AssistRuntime.bAgentLan, BuildConfig.DEBUG)
            goalQuestion = "需要我的帮助吗？"; agentResult = ""; agentEventId = ""; agentRecording = null; executing = false
            val listener = object : AssistListener {
                override fun onPrompt(prompt: AssistPrompt) {
                    if (suppressAgentEvents || !valid(token)) return
                    agentPrompt = prompt
                    if (prompt.kind == PromptKind.GOAL && prompt.text.startsWith("好的，没有执行")) goalQuestion = prompt.text
                    ask(token, if (prompt.kind == PromptKind.GOAL) goalQuestion else prompt.text,
                        prompt.promptId, prompt.expiresAtMs - now())
                }
                override fun onEvent(event: AssistEvent) {
                    if (suppressAgentEvents || !valid(token)) return
                    if (event.type in setOf("speech_ignored", "analysis_failed", "execution_result", "stopped"))
                        IntegrationLog.event("B_AGENT", event.type, mapOf("event_id" to agentEventId,
                            "prompt_id" to agentPrompt?.promptId, "task_id" to agentPrompt?.taskId))
                    when (event.type) {
                        "speech_ignored" -> agentPrompt?.let {
                            ask(token, if (it.kind == PromptKind.ACTION) "${it.text}\n请明确说“对”，或说“不是这个”。" else "请说出目标，例如：我想打开通讯录。",
                                it.promptId, it.expiresAtMs - now())
                        }
                        "analysis_failed" -> agentResult = "未生成可执行的明确操作，请核对视觉模型配置，或换个说法再试。"
                        "execution_result" -> agentResult = when {
                            event.detail.startsWith("A:") -> "已执行一次点击；模型检查显示这一步有进展，请核对手机页面。"
                            event.detail.startsWith("B:") -> "已点击一次，但模型判断页面不符合预期；已停止，请手动检查。"
                            event.detail.startsWith("C:") -> "已点击一次，但模型未观察到预期变化；已停止。"
                            event.detail.startsWith("not_dispatched:") -> "页面或操作校验未通过，没有点击。请重新发起帮助。"
                            else -> "已尝试一次点击，但未能验证结果；已停止，请核对手机页面。"
                        }
                        "stopped" -> {
                            val recording = agentRecording
                            val message = agentResult.ifBlank { when (event.detail) {
                                "stale_page" -> "页面已变化，旧操作已清理，没有点击。"
                                "timeout" -> "本次 B 确认已超时，没有执行旧操作。"
                                else -> "本次 B 帮助已停止。"
                            } }
                            cancel(message)
                            if (AssistRuntime.screenAllowed && AssistRuntime.session.aTask == null) {
                                if (agentEventId.isNotBlank()) AssistRuntime.sendResult(agentEventId, "assist_step_result", message, recording)
                                show("B 帮助结果", message, "关闭") { hide() }
                                AssistRuntime.recordBResult(message, "B Agent 单步帮助", recording)
                                AssistRuntime.speakB(message)
                                val shown = overlay
                                main.postDelayed({
                                    if (!closed && overlay === shown && AssistRuntime.session.aTask == null &&
                                        AssistRuntime.session.bState == AssistSession.BState.OBSERVING) hide()
                                }, 15000)
                            }
                        }
                    }
                }
            }
            agent = BAssist(service, screenshot = { BAgentScreenshot.capture(service) { agentAllowed(token) } },
                config = ApiConfig(endpoint = endpoint, apiKey = AssistRuntime.bAgentToken, model = AssistRuntime.bAgentModel, maxSteps = 1),
                listener = listener,
                apiClient = ScreenAssistApiClient(ApiConfig(endpoint = endpoint, apiKey = AssistRuntime.bAgentToken,
                    model = AssistRuntime.bAgentModel, maxSteps = 1), service.history, correlation = {
                    agentRecording?.let { mapOf("session_id" to it.sessionId, "task_id" to it.taskId,
                        "event_id" to it.eventId, "prompt_id" to it.promptId, "button" to it.button) } ?: emptyMap()
                }), guard = {
                    check(agentAllowed(token)) { "B 已暂停或当前页面不允许操作" }
                    check(bridge.currentSnapshot().packageName != service.packageName) { "助手自身页面不执行操作" }
                })
            agent?.startObserving()
            agent?.onBHelpRequested("agent-${UUID.randomUUID()}")
        } catch (_: Exception) { cancel("B Agent 配置不可用，请在设置中填写视觉模型地址、模型名和令牌") }
    }

    private fun agentAllowed(token: Long) = valid(token) && !NextActionRuntime.isBusy && agent != null

    private fun valid(token: Long) = !closed && AssistRuntime.screenAllowed && AssistRuntime.session.current(token) && !service.isDeviceLocked()
    private fun samePage() = runCatching { pageKey(bridge.currentSnapshot()) == expectedPage }.getOrDefault(false)

    private fun launch(block: () -> Unit) {
        val id = ++serial; busy = true
        future = worker.submit {
            try { block() }
            catch (_: Exception) {
                main.post {
                    if (!closed && id == serial) {
                        if (AssistRuntime.session.bState != AssistSession.BState.OBSERVING) cancel("本次页面分析不可用，已停止；请稍后再试")
                        else detector.reset()
                    }
                }
            } finally { main.post { if (id == serial) busy = false } }
        }
    }

    /** Called after beginA has already invalidated the session token. */
    fun preempt() {
        AssistRuntime.stopSpeaking()
        IntegrationLog.event("B", "invalidated")
        suppressAgentEvents = true
        try {
            if (AssistRuntime.session.aTask != null) agent?.onATaskStarted()
            agent?.close()
        } finally { agent = null; agentPrompt = null; agentRecording = null; executing = false; suppressAgentEvents = false }
        serial++; busy = false; future?.cancel(true); vision.cancelAll(); detector.reset()
        expectedPage = null; expectedPackage = null; hide(); main.removeCallbacks(timeout)
    }
    fun cancel(reason: String) {
        val prompt = AssistRuntime.session.promptId
        AssistRuntime.cancelBRecording()
        AssistRuntime.session.clearB(now())
        preempt()
        if (prompt != null) AssistRuntime.send(JSONObject().put("v", 2).put("type", "assist_cancel").put("prompt_id", prompt))
        AssistRuntime.update("b_status", reason)
    }
    fun reconfigure() { cancel("B 配置已更新"); detector = PageDwellDetector(AssistRuntime.dwellSeconds * 1000L) }
    fun showAResult(text: String, title: String = "A 处理结果", persistent: Boolean = false): Boolean {
        if (closed || service.isDeviceLocked()) return false
        val displayed = show(title, text, "知道了") { hide() }
        main.removeCallbacks(timeout)
        // A notices are separate from B state and cannot turn into a B reply.
        val shown = overlay
        if (!persistent) main.postDelayed({
            if (overlay === shown && AssistRuntime.session.bState == AssistSession.BState.OBSERVING) hide()
        }, 20000)
        return displayed
    }
    fun showAContactChoices(body: String, choices: List<Pair<String, () -> Unit>>, decline: () -> Unit): Boolean {
        return showAActions("请确认拨号联系人", body, choices + ("取消拨号" to decline))
    }
    fun showAActions(title: String, body: String, choices: List<Pair<String, () -> Unit>>): Boolean {
        if (closed || service.isDeviceLocked() || AssistRuntime.session.aTask == null) return false
        val displayed = showPanel(title, body, choices)
        if (displayed) aContactOverlay = overlay
        return displayed
    }
    fun showPermissionRepair(reason: String): Boolean {
        if (closed || service.isDeviceLocked()) return false
        return showPanel("需要完成一次设置", reason, listOf(
            "一键修复权限" to {
                hide()
                runCatching { cn.seniorvoice.demo.setup.PermissionRecovery.pendingIntent(service, reason).send() }
                    .onFailure { cn.seniorvoice.demo.setup.PermissionRecovery.report(service, reason) }
            },
            "稍后" to { hide() }
        ))
    }
    fun clearAContactPrompt() {
        if (aContactOverlay != null && overlay === aContactOverlay) hide()
        aContactOverlay = null
    }
    private fun show(title: String, body: String, button: String, close: () -> Unit): Boolean =
        showPanel(title, body, listOf(button to close)).also { if (!it) cancel("无法显示帮助窗口") }

    private fun showPanel(title: String, body: String, buttons: List<Pair<String, () -> Unit>>): Boolean {
        hide()
        val wm = service.getSystemService(WindowManager::class.java)
        val density = service.resources.displayMetrics.density
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            setBackgroundColor(Color.WHITE)
            addView(TextView(service).apply { text = title; textSize = 23f; setTextColor(Color.rgb(30, 65, 55)) })
            addView(object : ScrollView(service) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = (service.resources.displayMetrics.heightPixels * 0.55).toInt()
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                addView(TextView(service).apply {
                    text = body; textSize = 20f; setTextColor(Color.DKGRAY); setPadding(0, 14, 0, 12)
                    setTextIsSelectable(true)
                })
            })
            buttons.forEach { (label, action) ->
                addView(Button(service).apply { text = label; setOnClickListener { action() } })
            }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        return runCatching { wm.addView(panel, params); overlay = panel; true }
            .getOrDefault(false)
    }
    private fun hide() {
        overlay?.let { runCatching { service.getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; aContactOverlay = null
    }
    fun close() { cancel("无障碍服务已断开"); closed = true; main.removeCallbacksAndMessages(null); worker.shutdownNow() }

    companion object {
        private fun pageKey(snapshot: UiSnapshot): String = buildString {
            append(snapshot.packageName).append(':').append(snapshot.windowId).append(':').append(snapshot.screenWidth).append('x').append(snapshot.screenHeight)
            snapshot.nodes.filter { it.visible }.forEach {
                append('|').append(it.viewId).append(':').append(it.className).append(':').append(it.bounds)
                    .append(':').append(it.label.replace(Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), "时钟"))
            }
        }
        private fun fingerprint(jpeg: ByteArray): IntArray {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("截图解码失败")
            var crop: Bitmap? = null; var small: Bitmap? = null
            try {
                val top = (bitmap.height * 0.08).toInt(); val height = (bitmap.height * 0.84).toInt().coerceAtLeast(1)
                crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                small = Bitmap.createScaledBitmap(crop, 32, 32, true)
                val colors = IntArray(1024); small.getPixels(colors, 0, 32, 0, 0, 32, 32)
                return IntArray(1024) { i -> (Color.red(colors[i]) * 30 + Color.green(colors[i]) * 59 + Color.blue(colors[i]) * 11) / 100 }
            } finally { if (small !== crop) small?.recycle(); if (crop !== bitmap) crop?.recycle(); bitmap.recycle() }
        }
    }
}
