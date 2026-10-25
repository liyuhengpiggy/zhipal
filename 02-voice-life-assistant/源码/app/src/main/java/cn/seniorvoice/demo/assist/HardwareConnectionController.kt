package cn.seniorvoice.demo.assist

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.seniorvoice.demo.BleClient
import cn.seniorvoice.demo.BleSettings
import cn.seniorvoice.demo.setup.PermissionRequirements
import java.util.UUID

/** One remembered peripheral, one connection attempt, and bounded retries independent of the screen. */
class HardwareConnectionController(
    context: Context,
    private val ble: BleClient,
    private val status: (String) -> Unit
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = HardwareReconnectPolicy(
        preferences.getBoolean("background_connection_enabled", false),
        preferences.getBoolean("ble_auto_reconnect", true))
    private var switching = false
    private var automaticAttempt = false
    private val retry = Runnable { reconnect() }

    val enabled get() = lifecycle.enabled
    val autoReconnect get() = lifecycle.automatic
    val rememberedName get() = preferences.getString("ble_last_name", "").orEmpty()
    val rememberedAddress get() = preferences.getString("ble_last_address", "").orEmpty()

    fun serviceStarted() {
        lifecycle.serviceStarted()
        if (enabled) schedule(0)
    }

    fun serviceStopped() {
        lifecycle.serviceStopped()
        main.removeCallbacks(retry)
        ble.stopScan()
        switching = true
        try { ble.disconnect() } finally { switching = false }
        automaticAttempt = false
    }

    fun enable() {
        lifecycle.enable()
        preferences.edit().putBoolean("background_connection_enabled", true).apply()
        schedule(0)
    }

    fun pause() {
        lifecycle.pause()
        preferences.edit().putBoolean("background_connection_enabled", false).apply()
        main.removeCallbacks(retry)
        ble.stopScan()
        switching = true
        try { ble.disconnect() } finally { switching = false }
        automaticAttempt = false
        IntegrationLog.event("BLE_SERVICE", "user_paused")
        status("后台连接已暂停；需要时可在设备页恢复")
    }

    fun setAutoReconnect(value: Boolean) {
        preferences.edit().putBoolean("ble_auto_reconnect", value).apply()
        lifecycle.automatic(value)
        main.removeCallbacks(retry)
        if (value) schedule(0)
        else if (automaticAttempt && !ble.isReady && ble.isConnecting) {
            switching = true
            try { ble.disconnect() } finally { switching = false }
            automaticAttempt = false
        }
        if (!ble.isReady) status(if (value) "已开启自动重连；等待已记住的硬件" else "已关闭自动重连，可在设备页手动连接")
    }

    fun connect(device: BluetoothDevice, configuration: BleSettings) {
        enable()
        main.removeCallbacks(retry)
        lifecycle.ready()
        automaticAttempt = false
        switching = true
        try {
            // Switching peripherals invalidates any A/B work from the old link as a real disconnect does.
            if (ble.isReady || ble.isConnecting) ble.disconnect()
            ble.connect(device, configuration)
        } finally { switching = false }
        if (!ble.isReady && !ble.isConnecting) schedule(lifecycle.nextDelay())
    }

    fun scanningStarted() { main.removeCallbacks(retry) }
    fun scanningStopped() { if (!switching) schedule(1000) }

    fun ready() {
        lifecycle.ready()
        automaticAttempt = false
        main.removeCallbacks(retry)
    }

    fun disconnected() {
        automaticAttempt = false
        if (!switching && lifecycle.maySchedule(rememberedAddress.isNotBlank())) schedule(lifecycle.nextDelay())
    }

    fun bluetoothChanged(enabled: Boolean) {
        main.removeCallbacks(retry)
        if (enabled) {
            lifecycle.ready()
            schedule(500)
        } else {
            ble.stopScan()
            switching = true
            try { ble.disconnect() } finally { switching = false }
            automaticAttempt = false
            if (this.enabled) status("手机蓝牙已关闭；打开后自动重连已记住的硬件")
        }
    }

    private fun schedule(delay: Long) {
        main.removeCallbacks(retry)
        if (lifecycle.maySchedule(rememberedAddress.isNotBlank()) && !ble.isReady && !ble.isConnecting)
            main.postDelayed(retry, delay)
    }

    private fun reconnect() {
        if (!lifecycle.maySchedule(rememberedAddress.isNotBlank()) || ble.isReady || ble.isConnecting) return
        if (ble.isScanning) { schedule(15000); return }
        if (!PermissionRequirements.bluetoothGranted(app)) {
            status("请在首次准备中允许附近设备权限，之后自动连接")
            return
        }
        if (PermissionRequirements.bluetoothState(app) != PermissionRequirements.BluetoothState.ENABLED) {
            status("等待手机蓝牙开启；硬件打开并广播后会自动重连")
            return
        }
        val configuration = runCatching {
            BleSettings(
                UUID.fromString(preferences.getString("ble_service", BleClient.DEFAULT_SERVICE)),
                UUID.fromString(preferences.getString("ble_notify", BleClient.DEFAULT_NOTIFY)),
                UUID.fromString(preferences.getString("ble_write", BleClient.DEFAULT_WRITE))
            )
        }.getOrElse { status("设备 UUID 配置无效，请在高级联调设置中核对"); return }
        IntegrationLog.event("BLE", "reconnect_attempt")
        switching = true
        try { automaticAttempt = ble.connectSaved(configuration) } finally { switching = false }
        if (!automaticAttempt) schedule(lifecycle.nextDelay())
    }
}
