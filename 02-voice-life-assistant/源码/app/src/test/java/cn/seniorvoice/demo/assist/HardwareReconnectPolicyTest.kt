package cn.seniorvoice.demo.assist

import org.junit.Assert.*
import org.junit.Test

class HardwareReconnectPolicyTest {
    @Test fun systemServiceRecreationKeepsUserIntentButDoesNotRunWithoutAService() {
        val policy = HardwareReconnectPolicy(enabled = true, automatic = true)
        policy.serviceStarted()
        assertTrue(policy.maySchedule(hasRememberedPeer = true))
        policy.serviceStopped()
        assertFalse(policy.maySchedule(hasRememberedPeer = true))
        assertTrue(HardwareReconnectPolicy.mayRestore(policy.enabled, true, true))
        policy.serviceStarted()
        assertTrue(policy.maySchedule(hasRememberedPeer = true))
    }

    @Test fun explicitPauseBlocksStickyAndBootRestorationUntilTheUserEnablesAgain() {
        val policy = HardwareReconnectPolicy(enabled = true, automatic = true)
        policy.serviceStarted()
        policy.pause()
        policy.serviceStopped()
        policy.serviceStarted()
        assertFalse(policy.maySchedule(hasRememberedPeer = true))
        assertFalse(HardwareReconnectPolicy.mayRestore(policy.enabled, true, true))
        policy.enable()
        assertTrue(policy.maySchedule(hasRememberedPeer = true))
    }

    @Test fun noRememberedDeviceOrRevokedPermissionNeverRestoresAnArbitraryBoard() {
        val policy = HardwareReconnectPolicy(enabled = true, automatic = true)
        policy.serviceStarted()
        assertFalse(policy.maySchedule(hasRememberedPeer = false))
        assertFalse(HardwareReconnectPolicy.mayRestore(true, false, true))
        assertFalse(HardwareReconnectPolicy.mayRestore(true, true, false))
        policy.automatic(false)
        assertFalse(policy.maySchedule(hasRememberedPeer = true))
    }

    @Test fun repeatedDisconnectsBackOffAndSuccessfulConnectionResetsDelay() {
        val policy = HardwareReconnectPolicy(enabled = true, automatic = true)
        assertEquals(listOf(1000L, 2000L, 5000L, 10000L, 20000L, 30000L), List(6) { policy.nextDelay() })
        repeat(1000) { assertEquals(30000L, policy.nextDelay()) }
        policy.ready()
        assertEquals(1000L, policy.nextDelay())
    }
}
