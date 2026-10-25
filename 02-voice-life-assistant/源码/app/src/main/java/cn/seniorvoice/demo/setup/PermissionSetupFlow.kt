package cn.seniorvoice.demo.setup

/** Pure ordering and consent state; returning without completing a step stops the sequence. */
internal enum class PermissionStep { PERMISSIONS, BLUETOOTH, LOCATION, NOTIFICATIONS, ACCESSIBILITY, COMPLETE, UNAVAILABLE }

internal data class PermissionReadiness(
    val permissions: Boolean,
    val bluetooth: Boolean,
    val location: Boolean,
    val notifications: Boolean,
    val accessibility: Boolean,
    val hardwareAvailable: Boolean = true,
    val bluetoothReadable: Boolean = true,
)

internal object PermissionSetupPlan {
    fun next(value: PermissionReadiness): PermissionStep = when {
        !value.hardwareAvailable -> PermissionStep.UNAVAILABLE
        !value.permissions -> PermissionStep.PERMISSIONS
        !value.bluetoothReadable -> PermissionStep.UNAVAILABLE
        !value.bluetooth -> PermissionStep.BLUETOOTH
        !value.location -> PermissionStep.LOCATION
        !value.notifications -> PermissionStep.NOTIFICATIONS
        !value.accessibility -> PermissionStep.ACCESSIBILITY
        else -> PermissionStep.COMPLETE
    }

    fun shouldOffer(missing: List<String>, dismissed: String, version: Int): Boolean =
        missing.isNotEmpty() && fingerprint(missing, version) != dismissed

    fun fingerprint(missing: List<String>, version: Int): String =
        "v2:$version:" + missing.sorted().joinToString(",")
}

internal class PermissionSetupFlow(active: Boolean = false, waitingFor: PermissionStep? = null) {
    var active: Boolean = active || waitingFor != null
        private set
    var waitingFor: PermissionStep? = waitingFor
        private set

    fun start() { if (waitingFor == null) active = true }
    fun stop() { active = false; waitingFor = null }
    fun next(readiness: PermissionReadiness): PermissionStep? {
        if (!active || waitingFor != null) return null
        return PermissionSetupPlan.next(readiness).also {
            if (it == PermissionStep.COMPLETE || it == PermissionStep.UNAVAILABLE) active = false
        }
    }
    fun await(step: PermissionStep) {
        check(active && waitingFor == null)
        check(step != PermissionStep.COMPLETE && step != PermissionStep.UNAVAILABLE)
        waitingFor = step
    }
    fun returned(step: PermissionStep, completed: Boolean): Boolean {
        if (waitingFor != step) return false
        waitingFor = null
        active = completed
        return true
    }
}
