package cn.seniorvoice.demo.nextaction.observation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import cn.seniorvoice.demo.nextaction.domain.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AccessibilityBridge(private val service: NextActionAccessibilityService) : ObservationSource {
    private val parser = UiTreeParser()
    private val main = Handler(Looper.getMainLooper())
    private val snapshotter = ScreenSnapshotter(service)

    override fun capture(requestId: String): CapturedObservation {
        check(service.collectionAllowed()) { "请先启用 AI 补全并同意本次屏幕采集" }
        val first = currentSnapshot()
        PrivacyFilter.reason(first)?.let { error(it) }
        val image = snapshotter.capture()
        val current = currentSnapshot()
        check(TargetResolver().isSamePage(first, current) && first.revision == current.revision) { "截图期间页面发生变化，本次取消" }
        return CapturedObservation(first, image, service.history.snapshot())
    }

    override fun currentSnapshot(): UiSnapshot = onMain {
        check(service.collectionAllowed()) { "屏幕采集会话已关闭" }
        check(!service.isDeviceLocked()) { "请解锁手机后再触发" }
        val root = service.rootInActiveWindow ?: error("未读到当前窗口控件，请回到普通页面再试")
        try {
            val packageName = root.packageName?.toString().orEmpty()
            check(PrivacyFilter.packageAllowed(packageName)) { "敏感或系统应用，不采集此页面" }
            check(service.safeActiveWindow(root.windowId)) { "弹窗、多窗口或非普通页面不参与本次预测" }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(WindowManager::class.java)).defaultDisplay.getRealMetrics(metrics)
            parser.parse(root, service.revision.get(), SystemClock.elapsedRealtime(), metrics.widthPixels, metrics.heightPixels)
                .also { snapshot -> PrivacyFilter.reason(snapshot)?.let { error(it) } }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    override fun currentRevision(): Long = service.revision.get()

    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(Callable { block() })
        main.post(task)
        return try { task.get(2500, TimeUnit.MILLISECONDS) }
        catch (error: Exception) { task.cancel(false); throw IllegalStateException("无障碍页面采集超时或失败", error) }
    }

    fun <T> withFreshRoot(block: (AccessibilityNodeInfoHolder) -> T): T = onMain {
        val root = service.rootInActiveWindow ?: error("目标窗口已消失")
        try { block(AccessibilityNodeInfoHolder(root, parser)) }
        finally { @Suppress("DEPRECATION") root.recycle() }
    }
    data class AccessibilityNodeInfoHolder(val root: android.view.accessibility.AccessibilityNodeInfo, val parser: UiTreeParser)
}
