package cn.seniorvoice.demo.setup

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import cn.seniorvoice.demo.BuildConfig

/** Device permissions are checked live. Microphone and per-session screen consent stay separate. */
object PermissionRequirements {
    private const val PREFS = "permission_setup"
    private const val SEEN = "basic_setup_seen_v1"
    private const val DISMISSED = "dismissed_missing_v2"
    private const val ASKED_PREFIX = "asked_"

    enum class BluetoothState { ENABLED, DISABLED, NEED_PERMISSION, UNSUPPORTED, UNAVAILABLE }

    fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun basicPermissions(): List<String> = bluetoothPermissions() + Manifest.permission.CALL_PHONE +
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(context: Context): List<String> =
        basicPermissions().filterNot { isGranted(context, it) }

    fun bluetoothGranted(context: Context): Boolean =
        bluetoothPermissions().all { isGranted(context, it) }

    fun phoneGranted(context: Context): Boolean = isGranted(context, Manifest.permission.CALL_PHONE)

    fun notificationsGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)) &&
            (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true)

    /** This checks the OS toggle, not permission to send screenshots or execute a B action. */
    fun accessibilityGranted(context: Context): Boolean = try {
        val target = ComponentName(context.packageName,
            "cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService")
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info -> info.resolveInfo?.serviceInfo?.let { ComponentName(it.packageName, it.name) == target } == true } == true
    } catch (_: RuntimeException) { false }

    /** Reoffer after an update/newly missing requirement, once; never reset already granted rights. */
    fun shouldOfferSetup(context: Context): Boolean = PermissionSetupPlan.shouldOffer(
        missingSetupItems(context),
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DISMISSED, "").orEmpty(),
        BuildConfig.VERSION_CODE,
    )

    fun markSetupSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true)
            .putString(DISMISSED, PermissionSetupPlan.fingerprint(missingSetupItems(context), BuildConfig.VERSION_CODE)).apply()
    }

    fun setupComplete(context: Context): Boolean = PermissionSetupPlan.next(readiness(context)) == PermissionStep.COMPLETE

    fun missingNames(context: Context): List<String> = buildList {
        if (!bluetoothGranted(context)) add(if (Build.VERSION.SDK_INT >= 31) "蓝牙连接权限" else "蓝牙扫描的位置权限")
        if (!phoneGranted(context)) add("拨打电话权限")
        when (bluetoothState(context)) {
            BluetoothState.DISABLED -> add("手机蓝牙开关")
            BluetoothState.UNSUPPORTED -> add("BLE 硬件支持")
            BluetoothState.UNAVAILABLE -> add("蓝牙状态读取")
            else -> Unit
        }
        if (legacyLocationEnabled(context) == false) add("系统位置信息开关")
        if (!notificationsGranted(context)) add("通知提醒")
        if (!accessibilityGranted(context)) add("无障碍页面帮助")
    }

    fun permissionsSummary(context: Context): String = missingNames(context).let {
        if (it.isEmpty()) "必要权限和系统开关已准备好" else "需要设置：" + it.joinToString("、")
    }

    internal fun readiness(context: Context): PermissionReadiness {
        val bluetooth = bluetoothState(context)
        return PermissionReadiness(missingPermissions(context).isEmpty(),
            bluetooth == BluetoothState.ENABLED, legacyLocationEnabled(context) != false,
            notificationsGranted(context), accessibilityGranted(context),
            bluetooth != BluetoothState.UNSUPPORTED, bluetooth != BluetoothState.UNAVAILABLE)
    }

    private fun missingSetupItems(context: Context): List<String> = buildList {
        addAll(missingPermissions(context))
        when (bluetoothState(context)) {
            BluetoothState.DISABLED -> add("bluetooth_switch")
            BluetoothState.UNSUPPORTED -> add("ble_unsupported")
            BluetoothState.UNAVAILABLE -> add("bluetooth_unavailable")
            else -> Unit
        }
        if (legacyLocationEnabled(context) == false) add("legacy_location_switch")
        if (!notificationsGranted(context)) add("notifications")
        if (!accessibilityGranted(context)) add("accessibility")
    }

    internal fun hasRequested(context: Context, permission: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_PREFIX + permission, false)

    /** Call after a non-empty permission-result callback, for permissions with actual results only. */
    internal fun markRequested(context: Context, permissions: List<String>) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        permissions.forEach { editor.putBoolean(ASKED_PREFIX + it, true) }
        editor.apply()
    }

    fun bluetoothState(context: Context): BluetoothState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BluetoothState.UNSUPPORTED
        }
        if (Build.VERSION.SDK_INT >= 31 && !isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            return BluetoothState.NEED_PERMISSION
        }
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return BluetoothState.UNSUPPORTED
            if (adapter.isEnabled) BluetoothState.ENABLED else BluetoothState.DISABLED
        } catch (_: SecurityException) {
            BluetoothState.NEED_PERMISSION
        } catch (_: RuntimeException) {
            BluetoothState.UNAVAILABLE
        }
    }

    /** Older Android releases also require the system location switch for BLE discovery. */
    fun legacyLocationEnabled(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT >= 31) return null
        return try {
            val manager = context.getSystemService(LocationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
