package cn.seniorvoice.demo.nextaction.observation

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.domain.TriggerSource
import java.util.concurrent.atomic.AtomicLong
import cn.seniorvoice.demo.assist.AssistRuntime

class NextActionAccessibilityService : AccessibilityService() {
    val revision = AtomicLong(0)
    val history = InteractionHistory(capacity = 80)
    private val shortcut = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            NextActionRuntime.trigger(TriggerSource.ACCESSIBILITY_BUTTON)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        NextActionRuntime.initialize(this)
        NextActionRuntime.attach(this)
        AssistRuntime.attach(this)
        accessibilityButtonController.registerAccessibilityButtonCallback(shortcut)
    }

    fun collectionAllowed() = NextActionRuntime.collectionAllowed() || AssistRuntime.screenAllowed || AssistRuntime.aScreenAllowed
    fun isDeviceLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun safeActiveWindow(windowId: Int): Boolean {
        val current = windows
        try {
            val active = current.firstOrNull { it.id == windowId } ?: return false
            return active.type == AccessibilityWindowInfo.TYPE_APPLICATION && active.isActive &&
                current.none { (it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ||
                    (it.id != windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION) }
        } finally { current.forEach { @Suppress("DEPRECATION") it.recycle() } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !collectionAllowed()) return
        AssistRuntime.pageEvent(event)
        val packageName = event.packageName?.toString().orEmpty()
        revision.incrementAndGet()
        NextActionRuntime.onPageChanged()
        if (isDeviceLocked() || packageName == this.packageName || !PrivacyFilter.packageAllowed(packageName)) return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_CHANGE"
            else -> return
        }
        val source = event.source
        try {
            if (source?.isPassword == true || source?.isEditable == true) return
            history.add(SystemClock.elapsedRealtime(), type, packageName, event.windowId)
        } finally { @Suppress("DEPRECATION") source?.recycle() }
    }

    override fun onInterrupt() { NextActionRuntime.cancel("无障碍服务被中断"); AssistRuntime.cancelAll("无障碍服务被中断") }
    override fun onUnbind(intent: Intent?): Boolean { release(); return super.onUnbind(intent) }
    override fun onDestroy() { release(); super.onDestroy() }
    private fun release() {
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcut) }
        NextActionRuntime.detach(this)
        AssistRuntime.detach(this)
        history.clear()
    }
}
