package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions for the pre-consent keyguard gate. A locked device must not
 * open a MediaProjection sheet nobody can confirm for 120 s.
 */
class CaptureUnlockPolicyTest {

    @Test
    fun `an unlocked device proceeds without touching the stored pin`() {
        assertEquals(
            ConsentUnlockAction.PROCEED,
            CaptureUnlockPolicy.decide(keyguardLocked = false, hasStoredPin = false)
        )
        assertEquals(
            ConsentUnlockAction.PROCEED,
            CaptureUnlockPolicy.decide(keyguardLocked = false, hasStoredPin = true)
        )
    }

    @Test
    fun `a locked device with a stored pin attempts the stored pin`() {
        assertEquals(
            ConsentUnlockAction.TRY_STORED_PIN,
            CaptureUnlockPolicy.decide(keyguardLocked = true, hasStoredPin = true)
        )
    }

    @Test
    fun `a locked device without a stored pin is blocked immediately`() {
        assertEquals(
            ConsentUnlockAction.BLOCK,
            CaptureUnlockPolicy.decide(keyguardLocked = true, hasStoredPin = false)
        )
    }

    @Test
    fun `no-pin error tells the operator exactly what to do`() {
        val message = CaptureUnlockPolicy.lockedError(hasStoredPin = false)
        assertTrue("must name the fixing command", message.contains("unlock <pin>"))
        assertTrue("must explain the consequence", message.contains("while locked"))
        assertFalse("must not pretend a PIN is stored", message.contains("stored PIN"))
    }

    @Test
    fun `stored-pin failure is a different actionable error`() {
        val message = CaptureUnlockPolicy.lockedError(hasStoredPin = true)
        assertTrue(message.contains("stored-PIN"))
        assertTrue(message.contains("unlock <pin>"))
        assertFalse(message.contains("no PIN is stored"))
    }
}
