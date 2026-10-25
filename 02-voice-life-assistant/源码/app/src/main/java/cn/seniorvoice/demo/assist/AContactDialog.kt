package cn.seniorvoice.demo.assist

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

/** Main-thread A clarification. The runtime rechecks task, expiry and saved number on selection. */
object AContactDialog {
    private var host = WeakReference<Activity>(null)
    private var window: AlertDialog? = null
    private data class Prompt(val title: String, val message: String,
                              val choices: List<Pair<String, () -> Unit>>, val decline: () -> Unit, val cancelLabel: String)
    private var prompt: Prompt? = null
    fun attach(activity: Activity) { host = WeakReference(activity); present() }
    fun detach(activity: Activity) {
        if (host.get() !== activity) return
        host.clear(); dismissWindow()
    }
    fun clear() { prompt = null; dismissWindow() }
    fun show(title: String, message: String, choices: List<Pair<String, () -> Unit>>, decline: () -> Unit,
             cancelLabel: String = "取消拨号"): Boolean {
        clear(); prompt = Prompt(title, message, choices, decline, cancelLabel); return present()
    }
    fun deliveredElsewhere() { clear() }
    private fun dismissWindow() { window?.setOnCancelListener(null); window?.dismiss(); window = null }
    private fun present(): Boolean {
        if (window?.isShowing == true) return true
        val activity = host.get() ?: return false
        val offered = prompt ?: return false
        if (activity.isFinishing || activity.isDestroyed || activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        return runCatching {
            val padding = (20 * activity.resources.displayMetrics.density).toInt()
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
                addView(TextView(activity).apply { text = offered.message; textSize = 19f })
                offered.choices.forEach { (label, select) ->
                    addView(Button(activity).apply {
                        text = label
                        setOnClickListener { if (prompt === offered) { clear(); select() } }
                    })
                }
            }
            window = AlertDialog.Builder(activity).setTitle(offered.title)
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton(offered.cancelLabel) { _, _ -> if (prompt === offered) { clear(); offered.decline() } }
                .create().also {
                    it.setOnCancelListener { if (prompt === offered) { clear(); offered.decline() } }
                    it.setCanceledOnTouchOutside(false)
                    it.show()
                }
            true
        }.getOrElse { dismissWindow(); false }
    }
}
