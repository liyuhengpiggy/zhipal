package cn.seniorvoice.demo.setup

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Error entry point. Background failures notify; only an explicit user gesture opens setup. */
object PermissionRecovery {
    private const val CHANNEL = "permission_recovery"
    private const val NOTIFICATION = 745
    private const val PREFS = "permission_recovery"
    private const val PENDING_REASON = "reason"
    private const val DEFAULT_REASON = "部分权限或系统开关尚未开启，助手会带你逐项检查。"
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = WeakHashMap<Activity, WeakReference<AlertDialog>>()
    private var lastNotificationAt = 0L
    private var lastNotificationReason = ""
    private var deferredUntil = 0L
    private var deferredReason = ""

    /** May be called from any thread; an Activity is used only while it has foreground focus. */
    fun report(context: Context, reason: String, foregroundActivity: Activity? = null) {
        val app = context.applicationContext
        val safeReason = clean(reason)
        val activity = foregroundActivity ?: context as? Activity
        main.post {
            if (isDeferred(safeReason)) return@post
            savePending(app, safeReason)
            if (activity != null && show(activity, safeReason)) return@post
            notifyRecovery(app, safeReason)
        }
    }

    /** Call on the main thread from a foreground UI. Repeated errors share a single dialog. */
    fun show(activity: Activity, reason: String = DEFAULT_REASON): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) { report(activity, reason, activity); return false }
        val safeReason = clean(reason)
        if (isDeferred(safeReason)) { clear(activity); return true }
        if (activity is PermissionSetupActivity) return true
        if (dialogs[activity]?.get()?.isShowing == true) return true
        if (!usable(activity) || !activity.window.decorView.hasWindowFocus()) {
            savePending(activity, safeReason)
            return false
        }
        val dialog = AlertDialog.Builder(activity).setTitle("需要完成一次权限设置")
            .setMessage("$safeReason\n\n点击“一键修复”后会按顺序检查。已经允许的会跳过；系统授权仍需你亲自确认。")
            .setPositiveButton("一键修复") { _, _ -> open(activity, safeReason, autoStart = true) }
            .setNegativeButton("稍后") { _, _ -> defer(activity, safeReason) }.create()
        dialogs[activity] = WeakReference(dialog)
        dialog.setOnDismissListener { dialogs.remove(activity) }
        dialog.setOnCancelListener { defer(activity, safeReason) }
        return try {
            dialog.show()
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_REASON).apply()
            true
        } catch (_: RuntimeException) {
            dialogs.remove(activity)
            savePending(activity, safeReason)
            false
        }
    }

    /** Use after the Home window gains focus to show failures missed while notifications were off. */
    fun showPending(activity: Activity): Boolean {
        val reason = pendingReason(activity) ?: return false
        return show(activity, reason)
    }

    fun pendingReason(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_REASON, null)

    /** User-click API; a service Context receives a notification instead of starting an Activity. */
    fun open(context: Context, reason: String = DEFAULT_REASON, autoStart: Boolean = false): Boolean {
        val activity = context as? Activity
        if (activity == null || !usable(activity) ||
            (!activity.window.decorView.hasWindowFocus() && dialogs[activity]?.get()?.isShowing != true)) {
            report(context, reason)
            return false
        }
        return try {
            activity.startActivity(recoveryIntent(activity, reason, autoStart))
            deferredUntil = 0L
            clear(context)
            true
        } catch (_: RuntimeException) { report(context, reason); false }
    }

    /** Safe to prepare in the background. Send only after an explicit notification/overlay click. */
    fun pendingIntent(context: Context, reason: String = DEFAULT_REASON, autoStart: Boolean = true): PendingIntent =
        PendingIntent.getActivity(context, NOTIFICATION, recoveryIntent(context, reason, autoStart),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Creating this Intent does not open anything; service callers must require a user gesture. */
    fun recoveryIntent(context: Context, reason: String = DEFAULT_REASON, autoStart: Boolean = true): Intent =
        Intent(context, PermissionSetupActivity::class.java)
            .putExtra(PermissionSetupActivity.EXTRA_REASON, clean(reason))
            .putExtra(PermissionSetupActivity.EXTRA_AUTO_START, autoStart)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_REASON).apply()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION) }
    }

    private fun usable(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed && !activity.isChangingConfigurations
    private fun isDeferred(reason: String): Boolean = reason == deferredReason && SystemClock.elapsedRealtime() < deferredUntil
    private fun defer(context: Context, reason: String) {
        // Automatic reconnect failures must not reopen the prompt immediately after “later”.
        deferredReason = reason
        deferredUntil = SystemClock.elapsedRealtime() + 60_000
        clear(context)
    }
    private fun savePending(context: Context, reason: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(PENDING_REASON, null) != reason) prefs.edit().putString(PENDING_REASON, reason).apply()
    }
    private fun notifyRecovery(context: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (reason == lastNotificationReason && now - lastNotificationAt < 30_000) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!PermissionRequirements.notificationsGranted(context)) return
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "权限修复提醒", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("知伴需要一次设置")
                .setContentText("点这里一键检查和修复权限")
                .setContentIntent(pendingIntent(context, reason)).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_ERROR).build())
            lastNotificationAt = now
            lastNotificationReason = reason
        } catch (_: RuntimeException) { /* Pending reason stays available in the foreground UI. */ }
    }
    private fun clean(reason: String): String = reason
        .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer [已隐藏]")
        .replace(Regex("(?i)\\bsk-[a-z0-9_-]+"), "[密钥已隐藏]")
        .replace(Regex("https?://\\S+"), "服务地址")
        .replace(Regex("[\\p{Cntrl}&&[^\\n]]"), " ")
        .trim().take(240).ifBlank { DEFAULT_REASON }
}
