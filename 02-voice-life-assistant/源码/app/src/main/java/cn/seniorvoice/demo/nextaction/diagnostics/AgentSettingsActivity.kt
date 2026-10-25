package cn.seniorvoice.demo.nextaction.diagnostics

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.domain.AgentDiagnostic
import cn.seniorvoice.demo.nextaction.trigger.AppButtonTrigger
import java.net.URI

/** In-memory diagnostics only. Opening this screen is never part of automatic capture. */
class AgentSettingsActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var consent: CheckBox
    private val trigger = AppButtonTrigger()
    private val observer: (AgentDiagnostic) -> Unit = { render(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextActionRuntime.initialize(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 30, 24, 40) }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 12); page.addView(this) }
        fun button(value: String, block: () -> Unit) { page.addView(Button(this).apply { text = value; setOnClickListener { block() } }) }
        text("AI 单步补全 · 内部 Demo", 25f)
        text("每次触发只预测一次、最多执行一个安全导航动作，然后停止。已有 WAV、电话、抖音功能保持原流程。截图功能需 Android 11 及以上。")
        val enabled = CheckBox(this).apply { text = "启用 AI 单步补全"; isChecked = NextActionRuntime.enabled }; page.addView(enabled)
        val mock = CheckBox(this).apply { text = "模拟模式（只操作 App 内练习页，不上传）"; isChecked = NextActionRuntime.mockMode }; page.addView(mock)
        text("云端模式将当前 JPEG 截图、控件标签及最多20条操作类型发送到下方 HTTPS 服务。请仅在无个人信息的测试页面使用。检测到输入框、密码、支付等界面会拒绝采集；无法保证识别图片里的所有隐私。")
        val url = EditText(this).apply { hint = "https://你的服务/api/v1/next-action/predict"; setText(NextActionRuntime.endpoint); setSingleLine() }; page.addView(url)
        val token = EditText(this).apply { hint = "服务访问令牌（可选；不是 OpenAI API Key）"; setText(NextActionRuntime.token); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }; page.addView(token)
        button("保存配置（保存后重新勾选本次同意）") {
            val endpoint = url.text.toString().trim()
            val valid = mock.isChecked || runCatching { val uri = URI(endpoint); uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null }.getOrDefault(false)
            if (!valid || token.text.contains('\n') || token.text.contains('\r') || token.text.startsWith("sk-")) {
                Toast.makeText(this, "请填 HTTPS 预测地址；OpenAI 密钥只放 Python 后端", Toast.LENGTH_LONG).show()
            } else {
                trigger.cancel(); NextActionRuntime.configure(enabled.isChecked, mock.isChecked, endpoint, token.text.toString().trim())
                consent.isChecked = false; render(NextActionRuntime.lastDiagnostic)
            }
        }
        consent = CheckBox(this).apply {
            text = "我已了解：同意本次会话读取页面和近期操作；云端模式还会上传当前屏幕。取消勾选立即停止。"
            isChecked = NextActionRuntime.consentGranted
            setOnCheckedChangeListener { _, checked ->
                NextActionRuntime.setSessionConsent(checked)
                if (checked && !NextActionRuntime.consentGranted) { isChecked = false; Toast.makeText(this@AgentSettingsActivity, "请先启用并保存配置", Toast.LENGTH_SHORT).show() }
            }
        }; page.addView(consent)
        button("打开系统无障碍设置（需你主动开启）") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("打开安全练习页（模拟单次点击）") { startActivity(Intent(this, AgentPlaygroundActivity::class.java)) }
        button("3 秒后补全一次（立即切换到目标测试页面）") {
            if (!trigger.schedule(false) { if (!it.accepted) Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
                Toast.makeText(this, "已有请求或倒计时，请先结束", Toast.LENGTH_SHORT).show()
            else state.text = "3 秒后采集；现在切到普通测试页面。不要停留在含输入框的设置页。"
        }
        button("取消倒计时 / 当前请求") { trigger.cancel() }
        button("停用 AI 并关闭此无障碍服务") { trigger.cancel(); NextActionRuntime.disableAccessibilityService(); enabled.isChecked = false; consent.isChecked = false; render(NextActionRuntime.lastDiagnostic) }
        state = text("等待状态", 15f).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(page) })
        NextActionRuntime.addListener(observer)
    }
    private fun render(d: AgentDiagnostic) {
        if (!::state.isInitialized) return
        state.text = buildString {
            append("无障碍连接：").append(NextActionRuntime.serviceConnected).append("；本次同意：").append(NextActionRuntime.consentGranted)
            append("\n状态：").append(d.state).append("\n来源：").append(d.source ?: "无")
            append("\n当前包名：").append(d.packageName ?: "未采集").append("\n请求：").append(d.requestId ?: "无")
            append("\n总耗时：").append(d.elapsedMs).append(" ms")
            append("\n预测调用耗时：").append(d.predictionElapsedMs?.let { "$it ms" } ?: "尚未完成预测")
            append("\n结果：").append(d.message)
            d.decision?.let { append("\n选择：").append(it.action).append(" / ").append(it.candidateId).append("\n理由：").append(it.reason) }
            d.result?.let { append("\n执行尝试：").append(it.attempted).append("；系统接受：").append(it.succeeded).append("\n").append(it.message) }
            append("\n\n本次合法候选：")
            d.candidates.forEach { append("\n").append(it.candidateId).append(" ").append(it.action).append(" ").append(it.targetLabel) }
            append("\n\n近期操作（仅内存；不记录输入文字）：")
            NextActionRuntime.history().forEach { append("\n").append(it.sequence).append(" ").append(it.eventType).append(" ").append(it.packageName) }
            append("\n\n此面板不保存或展示原始截图。没有接通、点击结果或滚动终点的验证时，只报告提交结果。")
        }
    }
    override fun onResume() { super.onResume(); if (::state.isInitialized) render(NextActionRuntime.lastDiagnostic) }
    override fun onDestroy() { NextActionRuntime.removeListener(observer); super.onDestroy() }
}
