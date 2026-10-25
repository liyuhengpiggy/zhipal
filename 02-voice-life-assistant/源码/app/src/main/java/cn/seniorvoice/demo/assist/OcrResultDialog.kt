package cn.seniorvoice.demo.assist

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

/** Main-thread presentation only; OCR task validation stays in AssistRuntime. */
object OcrResultDialog {
    private var host = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private var pending: Pair<String, String>? = null

    fun attach(activity: Activity) {
        host = WeakReference(activity)
        present()
    }

    fun detach(activity: Activity) {
        if (host.get() !== activity) return
        host.clear()
        dismissWindow()
    }

    fun show(title: String, message: String): Boolean {
        clear()
        pending = title to message
        return present()
    }

    /** A background accessibility window has taken responsibility for this result. */
    fun deliveredElsewhere() { clear() }

    fun clear() {
        pending = null
        dismissWindow()
    }

    private fun dismissWindow() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun present(): Boolean {
        if (dialog?.isShowing == true) return true
        val activity = host.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed ||
            activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val content = pending ?: return false
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            text = content.second
            textSize = 20f
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        return runCatching {
            dialog = AlertDialog.Builder(activity)
                .setTitle(content.first)
                .setView(ScrollView(activity).apply { addView(text) })
                .setPositiveButton("知道了") { _, _ -> }
                .create().also { window ->
                    window.setCanceledOnTouchOutside(false)
                    window.setOnDismissListener { pending = null; dialog = null }
                    window.show()
                }
            true
        }.getOrElse { dismissWindow(); false }
    }
}
