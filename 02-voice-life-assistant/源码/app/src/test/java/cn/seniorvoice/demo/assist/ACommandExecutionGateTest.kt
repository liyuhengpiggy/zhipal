package cn.seniorvoice.demo.assist

import org.junit.Assert.*
import org.junit.Test

class ACommandExecutionGateTest {
    @Test fun confirmationAuthorizesOnlyOneStep() {
        val gate = ACommandExecutionGate(1000, "original-page")
        assertTrue(gate.claim(999, "original-page"))
        assertFalse(gate.claim(999, "original-page"))
    }
    @Test fun stalePageCannotConsumeApproval() {
        val gate = ACommandExecutionGate(1000, "original-page")
        assertFalse(gate.claim(500, "another-page"))
        gate.invalidate()
        assertFalse(gate.claim(500, "original-page"))
    }
    @Test fun timeoutAtBoundaryDoesNotExecute() {
        assertFalse(ACommandExecutionGate(1000).claim(1000))
        assertFalse(ACommandExecutionGate(1000, "page").claim(1001, "page"))
    }
    @Test fun cancelledOfferNeverExecutes() {
        val gate = ACommandExecutionGate(1000)
        gate.invalidate()
        assertFalse(gate.claim(0))
    }
}
