package cn.seniorvoice.demo.assist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import cn.seniorvoice.demo.BuildConfig

class IntegrationDiagnosticsActivity : Activity() {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        page.addView(TextView(this).apply { text = "${BuildConfig.VERSION_NAME} 联调日志\n仅记录真实发生的调用和状态；不包含密钥、识别文字、音频或截图。未出现某项日志不代表该项通过。"; textSize = 18f })
        page.addView(Button(this).apply { text = "导出日志（JSONL）"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "assistant-${BuildConfig.VERSION_NAME}-integration.jsonl")
            }, 81)
        } })
        val log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        page.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(Button(this).apply { text = "刷新"; setOnClickListener { refresh(log) } })
        setContentView(page); refresh(log)
    }
    private fun refresh(view: TextView) { worker.execute {
        val value = IntegrationLog.read().takeLast(64000)
        runOnUiThread { if (!isDestroyed) view.text = value }
    } }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 81 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(IntegrationLog.read().toByteArray(Charsets.UTF_8))
            } ?: error("无法写入") }.isSuccess
            runOnUiThread { if (!isDestroyed) Toast.makeText(this, if (ok) "日志已导出" else "导出失败", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
