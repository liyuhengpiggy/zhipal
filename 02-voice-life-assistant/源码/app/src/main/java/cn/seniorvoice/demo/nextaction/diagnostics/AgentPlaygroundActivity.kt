package cn.seniorvoice.demo.nextaction.diagnostics

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import cn.seniorvoice.demo.R
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.trigger.AppButtonTrigger

/** No forms, accounts or personal data. Mock can click only the uniquely named practice target. */
class AgentPlaygroundActivity : Activity() {
    private val trigger = AppButtonTrigger()
    private var clicks = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); NextActionRuntime.initialize(this)
        clicks = savedInstanceState?.getInt("clicks") ?: 0
        val page = LinearLayout(this).apply {
            id = R.id.next_action_practice_root
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            orientation = LinearLayout.VERTICAL
            setPadding(30, 50, 30, 30)
        }
        fun label(value: String, size: Float = 18f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 15, 0, 15); page.addView(this) }
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() }; page.addView(this) }
        label("AI 单步安全练习", 26f)
        label("这是本 App 的无个人信息练习页。先在 AI 设置启用模拟模式、允许本次采集并开启系统无障碍服务。")
        label("点下方模拟触发后等3秒。应只点击一次“测试下一页”，随后停止。再次触发时没有下一页按钮，应为 NO_OP。")
        val count = label("已点击次数：$clicks")
        button("3 秒后模拟补全一次") {
            val accepted = trigger.schedule(true) { result -> if (!result.accepted) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show() }
            if (!accepted) Toast.makeText(this, "已有请求或倒计时", Toast.LENGTH_SHORT).show()
        }
        val target = button(if (clicks == 0) "测试下一页" else "本次练习已完成") {
            clicks++; count.text = "已点击次数：$clicks；本次动作已结束"
        }.apply { id = R.id.next_action_practice_next; isEnabled = clicks == 0 }
        target.setOnClickListener { clicks++; count.text = "已点击次数：$clicks；本次动作已结束"; target.text = "本次练习已完成"; target.isEnabled = false }
        button("重置练习页面") { trigger.cancel(); clicks = 0; count.text = "已点击次数：0"; target.text = "测试下一页"; target.isEnabled = true }
        button("取消当前请求") { trigger.cancel() }
        button("返回 AI 设置") { finish() }
        setContentView(ScrollView(this).apply { addView(page) })
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("clicks", clicks); super.onSaveInstanceState(outState) }
}
