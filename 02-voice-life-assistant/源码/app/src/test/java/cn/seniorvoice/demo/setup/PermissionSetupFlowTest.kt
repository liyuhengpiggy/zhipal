package cn.seniorvoice.demo.setup

import org.junit.Assert.*
import org.junit.Test

class PermissionSetupFlowTest {
    private val ready = PermissionReadiness(true, true, true, true, true)

    @Test fun `only missing steps are selected in the required order`() {
        var state = PermissionReadiness(false, false, false, false, false)
        assertEquals(PermissionStep.PERMISSIONS, PermissionSetupPlan.next(state))
        state = state.copy(permissions = true)
        assertEquals(PermissionStep.BLUETOOTH, PermissionSetupPlan.next(state))
        state = state.copy(bluetooth = true)
        assertEquals(PermissionStep.LOCATION, PermissionSetupPlan.next(state))
        state = state.copy(location = true)
        assertEquals(PermissionStep.NOTIFICATIONS, PermissionSetupPlan.next(state))
        state = state.copy(notifications = true)
        assertEquals(PermissionStep.ACCESSIBILITY, PermissionSetupPlan.next(state))
        assertEquals(PermissionStep.COMPLETE, PermissionSetupPlan.next(state.copy(accessibility = true)))
    }

    @Test fun `restored outstanding system page is never launched twice`() {
        val flow = PermissionSetupFlow(active = true, waitingFor = PermissionStep.ACCESSIBILITY)
        assertNull(flow.next(ready.copy(accessibility = false)))
        flow.start()
        assertNull(flow.next(ready.copy(accessibility = false)))
        assertTrue(flow.returned(PermissionStep.ACCESSIBILITY, completed = true))
        assertEquals(PermissionStep.COMPLETE, flow.next(ready))
        assertFalse(flow.active)
    }

    @Test fun `denial stops until a new explicit start`() {
        val flow = PermissionSetupFlow()
        flow.start()
        assertEquals(PermissionStep.PERMISSIONS, flow.next(ready.copy(permissions = false)))
        flow.await(PermissionStep.PERMISSIONS)
        assertTrue(flow.returned(PermissionStep.PERMISSIONS, completed = false))
        assertFalse(flow.active)
        assertNull(flow.next(ready.copy(permissions = false)))
        flow.start()
        assertEquals(PermissionStep.PERMISSIONS, flow.next(ready.copy(permissions = false)))
    }

    @Test fun `late callbacks cannot restart canceled setup`() {
        val flow = PermissionSetupFlow(active = true)
        flow.await(PermissionStep.BLUETOOTH)
        flow.stop()
        assertFalse(flow.returned(PermissionStep.BLUETOOTH, completed = true))
        assertFalse(flow.active)
        assertNull(flow.next(ready))
    }

    @Test fun `complete setup never interrupts and deferred missing items are offered once per update`() {
        assertFalse(PermissionSetupPlan.shouldOffer(emptyList(), "", 21))
        val missing = listOf("accessibility", "notifications")
        val dismissed = PermissionSetupPlan.fingerprint(missing, 21)
        assertFalse(PermissionSetupPlan.shouldOffer(missing.reversed(), dismissed, 21))
        assertTrue(PermissionSetupPlan.shouldOffer(missing, dismissed, 22))
        assertTrue(PermissionSetupPlan.shouldOffer(missing + "bluetooth_switch", dismissed, 21))
    }

    @Test fun `unavailable hardware stops instead of repeatedly opening settings`() {
        val flow = PermissionSetupFlow(active = true)
        assertEquals(PermissionStep.UNAVAILABLE, flow.next(ready.copy(hardwareAvailable = false)))
        assertFalse(flow.active)
        assertNull(flow.next(ready))
    }
}
