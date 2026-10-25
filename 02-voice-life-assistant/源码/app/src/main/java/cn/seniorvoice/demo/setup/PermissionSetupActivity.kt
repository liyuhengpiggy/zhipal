package cn.seniorvoice.demo.setup

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One user action starts a sequence; every OS authorization still needs the user's own approval. */
class PermissionSetupActivity : Activity() {
    private lateinit var progress: TextView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var primary: Button
    private lateinit var finishButton: Button
    private val main = Handler(Looper.getMainLooper())
    private var flow = PermissionSetupFlow()
    private var resumed = false
    private var pendingAdvance = false
    private var runtimeInFlight = false
    private var externalInFlight = false
    private var leftForSettings = false
    private val requestedThisRun = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val waiting = savedInstanceState.getString("waiting")?.let { value ->
                PermissionStep.entries.firstOrNull { it.name == value }
            }
            flow = PermissionSetupFlow(savedInstanceState.getBoolean("active"), waiting)
            pendingAdvance = savedInstanceState.getBoolean("advance")
            runtimeInFlight = savedInstanceState.getBoolean("runtime")
            externalInFlight = savedInstanceState.getBoolean("external")
            leftForSettings = savedInstanceState.getBoolean("left")
            requestedThisRun.addAll(savedInstanceState.getStringArrayList("requested").orEmpty())
        } else if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            flow.start()
            pendingAdvance = true
        }
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildPage()
        message.text = savedInstanceState?.getString("message") ?: intent.getStringExtra(EXTRA_REASON)?.take(240)
            ?: "点一次下方按钮，助手会按顺序带你完成设置。系统弹窗仍需你亲自选择“允许”。"
        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh()
        // Normally onActivityResult handles this. Some vendor Settings pages omit that callback.
        if (externalInFlight && leftForSettings) main.post {
            if (resumed && externalInFlight && leftForSettings) returnedFromSettings()
        } else if (pendingAdvance && flow.waitingFor == null) main.post { advance() }
    }

    override fun onPause() {
        resumed = false
        if (externalInFlight) leftForSettings = true
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("active", flow.active)
        outState.putString("waiting", flow.waitingFor?.name)
        outState.putBoolean("advance", pendingAdvance)
        outState.putBoolean("runtime", runtimeInFlight)
        outState.putBoolean("external", externalInFlight)
        outState.putBoolean("left", leftForSettings)
        outState.putString("message", message.text.toString())
        outState.putStringArrayList("requested", ArrayList(requestedThisRun))
        super.onSaveInstanceState(outState)
    }

    private fun buildPage() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            setBackgroundColor(BACKGROUND)
            fitsSystemWindows = true
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("首次使用 / 修复权限", 16f, GREEN))
        content.addView(text("跟着提示\n一次准备好", 30f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(text("已经允许的项目会直接跳过。每完成一个系统设置，返回这里后会自动检查并继续下一步。", 17f))

        card(content).apply {
            progress = text("", 22f, GREEN).also { addView(it) }
            message = text("", 18f).also { it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; addView(it) }
            primary = button("一键开始准备", true) {
                if (!flow.active && flow.waitingFor == null) {
                    requestedThisRun.clear()
                    flow.start()
                    advance()
                }
            }.also { addView(it, buttonLayout()) }
        }
        card(content).apply {
            addView(text("当前准备情况", 21f).apply { setTypeface(typeface, Typeface.BOLD) })
            status = text("", 17f).also { addView(it) }
            addView(text("设备权限用于接收眼镜录音；电话权限用于你确认后的拨号；通知用于后台进度和修复提醒。旧版 Android 的 BLE 扫描还需要位置权限及位置开关。", 15f, MUTED))
            addView(text("无障碍用于读取当前页面和执行已确认的一步操作。开启无障碍不会代替 B 的本次截图同意，也不会绕过操作确认。", 15f, MUTED))
        }
        card(content).apply {
            addView(text("后台接收 · 可选检查", 21f).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(text("华为手机建议在“设置 → 应用和服务 → 应用启动管理”找到本助手，允许自启动、关联启动和后台活动。系统强行停止应用后，需要你重新打开一次。", 16f, MUTED))
            addView(button("查看后台运行设置", false) {
                AlertDialog.Builder(this@PermissionSetupActivity).setTitle("允许助手在后台接收")
                    .setMessage("这是可选设置，不影响前面的授权。\n\n华为：应用启动管理 → 本助手 → 手动管理，允许自启动、关联启动和后台活动。\n\n若系统页面不同，可请家人帮忙找到本助手的电池或启动管理。")
                    .setPositiveButton("打开系统设置") { _, _ ->
                        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { runCatching { startActivity(appSettings()) } }
                    }.setNegativeButton("返回", null).show()
            }, buttonLayout())
            addView(text("眼镜录音不需要手机麦克风；联系人保存在本 App，不需要读取通讯录。拒绝或取消任何一步都会暂停引导，不会反复打开系统页面。", 15f, MUTED))
        }
        finishButton = button("稍后设置，返回助手", false) { finishSetup() }
        page.addView(finishButton, buttonLayout())
        setContentView(page)
    }

    private fun refresh() {
        val ready = PermissionRequirements.readiness(this)
        fun item(allowed: Boolean, label: String) = "${if (allowed) "✓" else "待设置"}  $label"
        status.text = listOf(
            item(PermissionRequirements.bluetoothGranted(this), "蓝牙连接权限"),
            item(PermissionRequirements.phoneGranted(this), "拨打电话权限"),
            item(ready.bluetooth, "手机蓝牙开关"),
            if (Build.VERSION.SDK_INT < 31) item(ready.location, "系统位置信息（旧版蓝牙扫描）") else null,
            item(ready.notifications, "通知和后台进度"),
            item(ready.accessibility, "无障碍页面帮助"),
        ).filterNotNull().joinToString("\n")
        val complete = PermissionSetupPlan.next(ready) == PermissionStep.COMPLETE
        progress.text = when {
            complete -> "准备完成"
            flow.waitingFor != null -> stepTitle(flow.waitingFor!!)
            flow.active -> "正在检查下一步…"
            else -> "准备好后，按下方按钮"
        }
        primary.text = when {
            complete -> "全部准备好了"
            flow.active -> "请完成当前系统提示"
            else -> "一键开始 / 继续准备"
        }
        primary.isEnabled = !flow.active && flow.waitingFor == null && !complete
        finishButton.text = if (complete) "完成，返回助手" else "稍后设置，返回助手"
    }

    private fun advance() {
        if (!resumed || isFinishing || isDestroyed) { pendingAdvance = flow.active; return }
        pendingAdvance = false
        val step = flow.next(PermissionRequirements.readiness(this)) ?: return
        when (step) {
            PermissionStep.COMPLETE -> {
                message.text = "已经准备完成。回到助手即可连接眼镜、接收指令；已保存的设备会自动尝试重连。"
                PermissionRequirements.markSetupSeen(this)
                PermissionRecovery.clear(this)
                refresh()
            }
            PermissionStep.UNAVAILABLE -> stop("暂时无法读取蓝牙，或本机不支持 BLE。请确认手机蓝牙可用后再试，也可以先返回使用其他功能。")
            PermissionStep.PERMISSIONS -> requestRuntimePermissions()
            PermissionStep.BLUETOOTH -> launchSettings(step, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "请在系统提示中允许打开蓝牙。")
            PermissionStep.LOCATION -> launchSettings(step, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "请打开系统“位置信息”开关，然后返回这里。旧版 Android 扫描眼镜时需要此开关。")
            PermissionStep.NOTIFICATIONS -> launchSettings(step, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName), "请允许本助手发送通知，然后返回这里，方便在其他 App 中查看接收进度和失败提醒。")
            PermissionStep.ACCESSIBILITY -> launchSettings(step, accessibilitySettings(),
                "请选择“语音生活助手 A/B 页面帮助”，打开开关并确认系统说明，然后返回这里。",
                fallback = accessibilityListSettings())
        }
    }

    private fun requestRuntimePermissions() {
        val missing = PermissionRequirements.missingPermissions(this)
        if (missing.isEmpty()) { pendingAdvance = true; main.post { advance() }; return }
        val requestable = missing.filterNot { permission ->
            permission in requestedThisRun || (PermissionRequirements.hasRequested(this, permission) &&
                !shouldShowRequestPermissionRationale(permission))
        }
        if (requestable.isEmpty()) {
            launchSettings(PermissionStep.PERMISSIONS, appSettings(), "系统不会再弹出部分授权框。请进入“权限”，允许设备/位置、电话及通知（若显示），然后返回这里。")
            return
        }
        flow.await(PermissionStep.PERMISSIONS)
        runtimeInFlight = true
        requestedThisRun.addAll(requestable)
        message.text = "请按系统提示允许设备（旧版为位置）、电话和通知权限。拒绝任意一项后，本次引导会暂停。"
        refresh()
        try { requestPermissions(requestable.toTypedArray(), RUNTIME_PERMISSIONS) }
        catch (_: RuntimeException) { stop("系统授权框未能打开。可以稍后再试，或从手机设置找到本助手的权限。") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RUNTIME_PERMISSIONS || !runtimeInFlight) return
        runtimeInFlight = false
        PermissionRequirements.markRequested(this, permissions.take(grantResults.size))
        val accepted = permissions.isNotEmpty() && grantResults.size == permissions.size &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        flow.returned(PermissionStep.PERMISSIONS, accepted)
        if (!accepted) stop("本次授权已取消或未全部允许，设置已暂停。其他功能仍可使用；愿意时再点“一键开始 / 继续准备”。")
        else { pendingAdvance = true; refresh(); main.post { advance() } }
    }

    private fun launchSettings(step: PermissionStep, target: Intent, instruction: String, fallback: Intent? = null) {
        flow.await(step)
        externalInFlight = true
        leftForSettings = false
        message.text = instruction
        refresh()
        try { startActivityForResult(target, EXTERNAL_SETTINGS) }
        catch (_: RuntimeException) {
            if (fallback != null) {
                try { startActivityForResult(fallback, EXTERNAL_SETTINGS); return }
                catch (_: RuntimeException) { /* Vendor Settings may omit the detail destination. */ }
            }
            stop("未能打开系统设置。请稍后重试，或请家人在系统设置中找到本助手。")
        }
    }

    @Deprecated("Platform callback keeps this Activity independent of AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXTERNAL_SETTINGS && externalInFlight) returnedFromSettings()
    }

    private fun returnedFromSettings() {
        externalInFlight = false
        leftForSettings = false
        val step = flow.waitingFor ?: return
        val ready = PermissionRequirements.readiness(this)
        val completed = when (step) {
            PermissionStep.PERMISSIONS -> ready.permissions
            PermissionStep.BLUETOOTH -> ready.bluetooth
            PermissionStep.LOCATION -> ready.location
            PermissionStep.NOTIFICATIONS -> ready.notifications
            PermissionStep.ACCESSIBILITY -> ready.accessibility
            else -> false
        }
        flow.returned(step, completed)
        if (!completed) stop("这一步还没有开启，设置已暂停，不会再次自动打开系统页面。需要时点“一键开始 / 继续准备”即可。")
        else { pendingAdvance = true; refresh(); main.post { advance() } }
    }

    private fun stop(instruction: String) {
        flow.stop()
        pendingAdvance = false
        runtimeInFlight = false
        externalInFlight = false
        leftForSettings = false
        message.text = instruction
        refresh()
    }

    private fun finishSetup() {
        stop(if (PermissionRequirements.setupComplete(this)) "准备完成。" else "已暂停设置。")
        PermissionRequirements.markSetupSeen(this)
        PermissionRecovery.clear(this)
        setResult(if (PermissionRequirements.setupComplete(this)) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    @Deprecated("Platform callback keeps this Activity independent of AndroidX")
    override fun onBackPressed() { finishSetup() }

    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    private fun accessibilityComponent() = ComponentName(packageName,
        "cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService")
    private fun accessibilitySettings(): Intent {
        // Some releases expose a detail page; Huawei builds may only expose the service list.
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, accessibilityComponent())
        return if (detail.resolveActivity(packageManager) != null) detail else accessibilityListSettings()
    }
    private fun accessibilityListSettings(): Intent {
        val key = accessibilityComponent().flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
    }
    private fun stepTitle(step: PermissionStep) = when (step) {
        PermissionStep.PERMISSIONS -> "第 1 步 · 允许基础权限"
        PermissionStep.BLUETOOTH -> "第 2 步 · 打开手机蓝牙"
        PermissionStep.LOCATION -> "第 3 步 · 允许旧版蓝牙扫描"
        PermissionStep.NOTIFICATIONS -> "第 4 步 · 开启通知提醒"
        PermissionStep.ACCESSIBILITY -> "第 5 步 · 开启页面帮助"
        else -> "检查设置"
    }
    private fun text(value: String, size: Float, color: Int = FOREGROUND) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(7), 0, dp(7))
    }
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(16)); background = rounded(-1, 18)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }
    private fun button(value: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 18f; isAllCaps = false; minHeight = dp(56)
        setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor(if (primary) -1 else GREEN)
        backgroundTintList = null; background = rounded(if (primary) GREEN else 0xFFE9F2EE.toInt(), 14)
        setOnClickListener { action() }
    }
    private fun buttonLayout() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    companion object {
        const val EXTRA_AUTO_START = "cn.seniorvoice.demo.setup.AUTO_START"
        const val EXTRA_REASON = "cn.seniorvoice.demo.setup.REASON"
        private const val RUNTIME_PERMISSIONS = 740
        private const val EXTERNAL_SETTINGS = 741
        private val GREEN = 0xFF286249.toInt()
        private val FOREGROUND = 0xFF20382E.toInt()
        private val MUTED = 0xFF5E6C64.toInt()
        private val BACKGROUND = 0xFFF4F7F2.toInt()
    }
}
