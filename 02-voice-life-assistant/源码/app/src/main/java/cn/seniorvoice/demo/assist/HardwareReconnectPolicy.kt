package cn.seniorvoice.demo.assist

/** User intent survives a service recreation; only an explicit pause revokes it. */
class HardwareReconnectPolicy(enabled: Boolean, automatic: Boolean) {
    var enabled = enabled; private set
    var automatic = automatic; private set
    var running = false; private set
    private var failures = 0

    fun enable() { enabled = true }
    fun pause() { enabled = false; failures = 0 }
    fun serviceStarted() { running = true }
    fun serviceStopped() { running = false }
    fun automatic(value: Boolean) { automatic = value; failures = 0 }
    fun ready() { failures = 0 }

    fun maySchedule(hasRememberedPeer: Boolean): Boolean =
        running && enabled && automatic && hasRememberedPeer

    fun nextDelay(): Long {
        val index = failures
        failures = minOf(failures + 1, DELAYS.lastIndex)
        return DELAYS[index]
    }

    companion object {
        private val DELAYS = longArrayOf(1000, 2000, 5000, 10000, 20000, 30000)
        fun mayRestore(enabled: Boolean, hasRememberedPeer: Boolean, permissionGranted: Boolean): Boolean =
            enabled && hasRememberedPeer && permissionGranted
    }
}
