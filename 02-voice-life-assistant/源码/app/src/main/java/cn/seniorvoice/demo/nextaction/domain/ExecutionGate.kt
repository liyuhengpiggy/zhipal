package cn.seniorvoice.demo.nextaction.domain

/** Cancellation, expiry and claiming share one monitor; a permit can be consumed only once. */
class ExecutionGate(private val clock: AgentClock) {
    fun createPermit(requestId: String, deadlineMs: Long): Permit = Permit(requestId, deadlineMs, clock)

    class Permit internal constructor(val requestId: String, val deadlineMs: Long, private val clock: AgentClock) {
        private var cancelled = false
        private var claimed = false
        @Synchronized fun claim(): Boolean {
            if (cancelled || claimed || clock.nowMs() >= deadlineMs) return false
            claimed = true
            return true
        }
        @Synchronized fun cancel() { cancelled = true }
        val isValid: Boolean @Synchronized get() = !cancelled && !claimed && clock.nowMs() < deadlineMs
        val wasClaimed: Boolean @Synchronized get() = claimed
    }
}
