package cn.seniorvoice.demo.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.seniorvoice.demo.setup.PermissionRequirements

/** Restore a connection the user already enabled, after reboot or an in-place APK update. */
class HardwareRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val settings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (!HardwareReconnectPolicy.mayRestore(
                settings.getBoolean("background_connection_enabled", false),
                !settings.getString("ble_last_address", "").isNullOrBlank(),
                PermissionRequirements.bluetoothGranted(context))) return
        IntegrationLog.initialize(context.filesDir)
        IntegrationLog.event("BLE_SERVICE", "restore_requested")
        runCatching { context.startForegroundService(Intent(context, VoiceForegroundService::class.java)) }
            .onFailure {
                // Some manufacturers require a separate user-managed auto-start setting.
                IntegrationLog.event("BLE_SERVICE", "restore_requires_foreground")
            }
    }
}
