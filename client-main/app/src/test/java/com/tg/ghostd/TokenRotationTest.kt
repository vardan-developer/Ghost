package com.tg.ghostd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRotationTest {

    @Test fun parsesRotationToken() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(
            token,
            TokenRotation.parse("""{"type":"auth_rotate","token":"$token"}""")
        )
    }

    @Test fun rejectsMalformedOrUnrelatedMessages() {
        assertNull(TokenRotation.parse("not json"))
        assertNull(TokenRotation.parse("""{"type":"cmd","token":"0123456789abcdef0123456789abcdef"}"""))
        assertNull(TokenRotation.parse("""{"type":"auth_rotate"}"""))
        assertNull(TokenRotation.parse("""{"type":"auth_rotate","token":"short"}"""))
        assertNull(TokenRotation.parse("""{"type":"auth_rotate","token":"Z123456789abcdef0123456789abcdef"}"""))
    }

    @Test fun buildsAcknowledgements() {
        val ok = JSONObject(TokenRotation.ack(true))
        assertEquals("auth_rotate_ack", ok.getString("type"))
        assertTrue(ok.getBoolean("ok"))

        val failed = JSONObject(TokenRotation.ack(false))
        assertEquals("auth_rotate_ack", failed.getString("type"))
        assertFalse(failed.getBoolean("ok"))
    }
}
