package com.bagent.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.bagent.integration.*
import com.mobileagent.app.api.*
import kotlinx.coroutines.*

/** Host-owned lifecycle. Feed host BLE/ASR results here; callback text goes to host TTS/UI. */
class BAssist(service: AccessibilityService, screenshot: suspend () -> Bitmap?, config: ApiConfig, listener: AssistListener,
              apiClient: VlmApiClient = OpenAiCompatibleClient(config), guard: () -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val port = AndroidAssistPort(service, screenshot, apiClient, scope, guard)
    private val engine = BAssistEngine(port, scope, listener, SystemClock::elapsedRealtime)
    val currentPrompt: AssistPrompt? get() = engine.currentPrompt
    fun startObserving() { main(); engine.startObserving() }
    fun stopObserving() { main(); engine.stopObserving() }
    fun onBHelpRequested(taskId: String) { main(); engine.onBHelpRequested(taskId) }
    fun onBSpeechResult(taskId: String, promptId: String, sourceButton: String, text: String) {
        main(); engine.onBSpeechResult(taskId, promptId, sourceButton, text)
    }
    fun onATaskStarted() { main(); engine.onATaskStarted() }
    fun onATaskFinished() { main(); engine.onATaskFinished() }
    fun cancelAssist() { main(); engine.cancelAssist() }
    fun close() { main(); engine.stopObserving(); scope.cancel() }
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        main()
        val kind = when(event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> RecentEvent.Kind.CLICK
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RecentEvent.Kind.PAGE
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> RecentEvent.Kind.OTHER
            else -> return
        }
        val page = runCatching { port.pageSignature() }.getOrNull() ?: return
        val source = event.source
        val control = try { source?.let { "${event.packageName}|${it.viewIdResourceName}|${it.text}|${it.contentDescription}" }.orEmpty() } finally { source?.recycle() }
        engine.onEvent(RecentEvent(SystemClock.elapsedRealtime(), kind, page, control, "${event.eventType}:${event.text.joinToString().take(120)}"))
    }
    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) { "BAssist entry points require Main" } }
}
