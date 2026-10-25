package cn.seniorvoice.demo.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import cn.seniorvoice.demo.HomeActivity
import cn.seniorvoice.demo.setup.PermissionRequirements

/** Preserves a user-enabled connection across Activity navigation and system service recreation. */
class VoiceForegroundService : Service() {
    private var foregroundReady = false
    private var registered = false
    private var lastNotificationAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> AssistRuntime.hardwareConnection.bluetoothChanged(
                    PermissionRequirements.bluetoothState(this@VoiceForegroundService) == PermissionRequirements.BluetoothState.ENABLED)
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> AssistRuntime.hardwareConnection.bluetoothChanged(false)
            }
        }
    }
    private val listener: (AssistUpdate) -> Unit = { value ->
        if (value.kind in setOf("audio", "voice_progress", "model_status", "a_result", "b_result", "status", "connection")) {
            val active = (value.kind == "model_status" && !AssistRuntime.asrReady) || AssistRuntime.isRecognizing ||
                AssistRuntime.session.aTask != null || AssistRuntime.session.bState in
                setOf(AssistSession.BState.RECORDING, AssistSession.BState.THINKING)
            if (active) wakeLock?.acquire(180000L) else releaseWakeLock()
        }
        val now = SystemClock.elapsedRealtime()
        if (foregroundReady && (value.kind in setOf("a_result", "b_result", "ble") ||
            (value.kind in setOf("audio", "voice_progress") && now - lastNotificationAt >= 1000))) {
            lastNotificationAt = now
            getSystemService(NotificationManager::class.java).notify(703, notification(value.message))
        }
    }
    override fun onCreate() {
        super.onCreate()
        AssistRuntime.initialize(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("glasses_connection", "眼镜连接与任务", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(703, notification("后台接收已开启；等待硬件连接"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(703, notification("后台接收已开启"))
            foregroundReady = true
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seniorvoice:hardware-task")
                .apply { setReferenceCounted(false) }
            AssistRuntime.addListener(listener)
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(bluetoothReceiver, filter)
            registered = true
        } catch (_: RuntimeException) {
            AssistRuntime.update("status", "请先授予蓝牙权限，再从助手前台启动后台连接")
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            AssistRuntime.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundReady || !AssistRuntime.hardwareConnection.enabled) { stopSelf(); return START_NOT_STICKY }
        AssistRuntime.serviceStarted()
        IntegrationLog.event("BLE_SERVICE", if (intent == null) "system_restored" else "started")
        return START_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceForegroundService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "glasses_connection").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("语音生活助手 · 后台接收").setContentText(message.take(160))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "暂停连接", stop).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() } }
    override fun onTaskRemoved(rootIntent: Intent?) {
        IntegrationLog.event("BLE_SERVICE", "activity_task_removed")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        foregroundReady = false
        if (registered) runCatching { unregisterReceiver(bluetoothReceiver) }
        registered = false
        AssistRuntime.removeListener(listener)
        AssistRuntime.serviceStopped()
        releaseWakeLock()
        IntegrationLog.event("BLE_SERVICE", "destroyed")
        super.onDestroy()
    }
}
