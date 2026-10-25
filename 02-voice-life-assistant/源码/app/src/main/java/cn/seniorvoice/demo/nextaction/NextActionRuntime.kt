package cn.seniorvoice.demo.nextaction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.seniorvoice.demo.nextaction.domain.*
import cn.seniorvoice.demo.nextaction.execution.AccessibilityActionExecutor
import cn.seniorvoice.demo.nextaction.network.AgentApiClient
import cn.seniorvoice.demo.nextaction.network.MockPredictionService
import cn.seniorvoice.demo.nextaction.observation.AccessibilityBridge
import cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService
import java.util.concurrent.CopyOnWriteArraySet

/** Process-local composition root. This Feature owns no database and never writes screen logs. */
object NextActionRuntime {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(AgentDiagnostic) -> Unit>()
    @Volatile private var service: NextActionAccessibilityService? = null
    @Volatile private var coordinator: SingleStepCoordinator? = null
    @Volatile private var sessionConsent = false
    @Volatile private var generation = 0L
    @Volatile var lastDiagnostic = AgentDiagnostic(AgentState.IDLE, null, null, "尚未触发 AI 补全")
        private set
    private val prefs get() = app.getSharedPreferences("next_action", Context.MODE_PRIVATE)
    val enabled get() = ::app.isInitialized && prefs.getBoolean("enabled", false)
    val mockMode get() = !::app.isInitialized || prefs.getBoolean("mock_mode", true)
    val endpoint get() = if (::app.isInitialized) prefs.getString("endpoint", "").orEmpty() else ""
    val token get() = if (::app.isInitialized) prefs.getString("token", "").orEmpty() else ""
    val serviceConnected get() = service != null
    val consentGranted get() = sessionConsent
    val isBusy get() = coordinator?.isBusy == true

    @Synchronized fun initialize(context: Context) { if (!::app.isInitialized) app = context.applicationContext }
    @Synchronized fun attach(value: NextActionAccessibilityService) { service = value; rebuild() }
    @Synchronized fun detach(value: NextActionAccessibilityService) {
        if (service !== value) return
        cancel("无障碍服务已断开"); coordinator?.close(); coordinator = null; service = null
        sessionConsent = false; generation++
    }
    @Synchronized fun configure(enabled: Boolean, mock: Boolean, endpoint: String, token: String) {
        cancel("配置发生变化")
        prefs.edit().putBoolean("enabled", enabled).putBoolean("mock_mode", mock)
            .putString("endpoint", endpoint).putString("token", token).apply()
        sessionConsent = false; service?.history?.clear(); rebuild()
    }
    fun setSessionConsent(accepted: Boolean) {
        sessionConsent = accepted && enabled
        if (!sessionConsent) { cancel("本次屏幕采集同意已关闭"); service?.history?.clear() }
    }
    fun collectionAllowed() = enabled && sessionConsent && service != null
    fun history() = service?.history?.snapshot().orEmpty()
    fun trigger(source: TriggerSource): TriggerResult {
        if (cn.seniorvoice.demo.assist.AssistRuntime.session.aTask != null) return TriggerResult(false, null, "A 正在运行，忽略后台补全")
        if (!enabled) return TriggerResult(false, null, "请先在 AI 补全设置启用此功能")
        if (service == null) return TriggerResult(false, null, "请在系统无障碍设置中开启语音生活助手服务")
        if (!sessionConsent) return TriggerResult(false, null, "尚未同意本次屏幕采集，请在 AI 补全设置勾选")
        if (android.os.Build.VERSION.SDK_INT < 30) return TriggerResult(false, null, "AI 截图需 Android 11 及以上")
        cn.seniorvoice.demo.assist.AssistRuntime.cancelB("已切换到手动单步调试")
        return coordinator?.trigger(source) ?: TriggerResult(false, null, "无障碍服务尚未就绪")
    }
    fun cancel(reason: String) { coordinator?.cancel(reason) }
    fun cancelBle() { if (lastDiagnostic.source == TriggerSource.BLE) cancel("BLE 已断开，本次取消") }
    fun onPageChanged() {
        if (isBusy && lastDiagnostic.state !in setOf(AgentState.EXECUTING, AgentState.FINISHED, AgentState.IDLE))
            cancel("用户操作或页面变化，本次旧预测已取消")
    }
    fun disableAccessibilityService() {
        setSessionConsent(false); prefs.edit().putBoolean("enabled", false).apply()
        service?.disableSelf()
    }
    fun addListener(listener: (AgentDiagnostic) -> Unit) { listeners.add(listener); listener(lastDiagnostic) }
    fun removeListener(listener: (AgentDiagnostic) -> Unit) { listeners.remove(listener) }
    @Synchronized private fun rebuild() {
        generation++; val currentGeneration = generation
        coordinator?.close(); coordinator = null
        val connected = service ?: return
        val bridge = AccessibilityBridge(connected)
        coordinator = SingleStepCoordinator(bridge,
            if (mockMode) MockPredictionService() else AgentApiClient(endpoint, token),
            AccessibilityActionExecutor(connected, bridge), AgentClock { SystemClock.elapsedRealtime() },
            listener = { diagnostic ->
                if (generation == currentGeneration) {
                    lastDiagnostic = diagnostic
                    main.post { if (generation == currentGeneration) listeners.forEach { runCatching { it(diagnostic) } } }
                }
            })
    }
}
