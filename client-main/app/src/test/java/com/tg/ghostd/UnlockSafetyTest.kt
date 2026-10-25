package com.tg.ghostd

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests: PIN shape, verified-unlock state, and failure envelopes. */
class UnlockSafetyTest {

    @Test
    fun `numeric and alphanumeric pins of reasonable length are accepted`() {
        assertNull(CommandHandler.UnlockSafety.validatePinShape("1234"))
        assertNull(CommandHandler.UnlockSafety.validatePinShape("12345678"))
        assertNull(CommandHandler.UnlockSafety.validatePinShape("Abc12345!"))
        assertNull(CommandHandler.UnlockSafety.validatePinShape("a".repeat(32)))
    }

    @Test
    fun `blank, short and overlong pins are rejected`() {
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("")!!.contains("4-32"))
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("123")!!.contains("4-32"))
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("a".repeat(33))!!.contains("4-32"))
    }

    @Test
    fun `spaces and control characters are rejected before any keyguard input`() {
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("1234 5678")!!.contains("printable"))
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("1234\n5678")!!.contains("printable"))
        assertTrue(CommandHandler.UnlockSafety.validatePinShape("1234\t")!!.contains("printable"))
    }

    @Test
    fun `both keyguard signals must be clear for a confirmed unlock`() {
        assertTrue(CommandHandler.UnlockSafety.isUnlocked(false, false))
        assertFalse(CommandHandler.UnlockSafety.isUnlocked(true, false))
        assertFalse(CommandHandler.UnlockSafety.isUnlocked(false, true))
        assertFalse(CommandHandler.UnlockSafety.isUnlocked(true, true))
    }

    @Test
    fun `sidekick failure keeps its diagnostics but never forwards a pin`() {
        val sidekick = JSONObject()
            .put("ok", false)
            .put("error", "unlock not confirmed")
            .put("pin", "s3cret")
            .put("typed", true)
            .put("steps", JSONArray().put("swipe_up").put("keypad"))
        val failure = CommandHandler.UnlockSafety.failureFrom(sidekick, "verify", "fallback")
        assertFalse(failure.getBoolean("ok"))
        assertEquals("unlock not confirmed", failure.getString("error"))
        assertEquals("verify", failure.getString("stage"))
        assertTrue(failure.getBoolean("typed"))
        assertEquals(2, failure.getJSONArray("steps").length())
        assertFalse("the PIN must never be copied into a result", failure.has("pin"))
    }

    @Test
    fun `missing sidekick error uses the actionable fallback`() {
        val failure = CommandHandler.UnlockSafety.failureFrom(null, "type", "could not enter the PIN")
        assertFalse(failure.getBoolean("ok"))
        assertEquals("could not enter the PIN", failure.getString("error"))
        assertEquals("type", failure.getString("stage"))
        assertFalse(failure.has("pin"))
    }

    @Test
    fun `an unverified unlock produces a failed wire envelope`() {
        val failure = CommandHandler.UnlockSafety.failureFrom(
            null, "verify",
            "unlock not confirmed: the keyguard is still locked after PIN entry"
        )
        val envelope = CommandHandler.resultEnvelope(failure)
        assertFalse(envelope.getBoolean("ok"))
        assertEquals("error", envelope.getString("status"))
        assertEquals(failure, envelope.getJSONObject("result"))
    }

    @Test
    fun `confirmed unlock keeps an ok envelope`() {
        val ok = JSONObject()
            .put("ok", true)
            .put("unlocked", true)
            .put("verified", true)
            .put("typed", true)
        val envelope = CommandHandler.resultEnvelope(ok)
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("ok", envelope.getString("status"))
    }

    // ------------------------------------------------------- screenshots

    @Test
    fun `only a throttled screenshot failure is retried`() {
        val throttled = JSONObject()
            .put("ok", false)
            .put("error", "throttled: wait ~400ms and retry")
        assertTrue(CommandHandler.ScreenshotPolicy.shouldRetryOnce(throttled))

        val upper = JSONObject().put("ok", false).put("error", "THROTTLED")
        assertTrue(CommandHandler.ScreenshotPolicy.shouldRetryOnce(upper))
    }

    @Test
    fun `other screenshot failures and successes are not retried`() {
        assertFalse(
            CommandHandler.ScreenshotPolicy.shouldRetryOnce(
                JSONObject().put("ok", false)
                    .put("error", "accessibility access lost — re-enable the service")
            )
        )
        assertFalse(
            CommandHandler.ScreenshotPolicy.shouldRetryOnce(
                JSONObject().put("ok", false).put("error", "screenshot timeout")
            )
        )
        assertFalse(
            CommandHandler.ScreenshotPolicy.shouldRetryOnce(
                JSONObject().put("ok", false)
            )
        )
        assertFalse(
            CommandHandler.ScreenshotPolicy.shouldRetryOnce(
                JSONObject().put("ok", true).put("error", "throttled")
            )
        )
    }
}
