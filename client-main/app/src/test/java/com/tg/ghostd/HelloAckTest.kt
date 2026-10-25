package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloAckTest {

    // --- parsing ---

    @Test fun parsesApprovedAckWithToken() {
        val tok = "a".repeat(32)
        val ack = HelloAck.parse(
            """{"type":"hello_ack","status":"approved","token":"$tok"}"""
        )
        assertNotNull(ack)
        assertTrue(ack!!.isApproved)
        assertFalse(ack.isPending)
        assertFalse(ack.isDenied)
        assertEquals(tok, ack.token)
    }

    @Test fun parsesPendingAck() {
        val ack = HelloAck.parse(
            """{"type":"hello_ack","status":"pending","token":"0123456789abcdef0123456789abcdef"}"""
        )
        assertNotNull(ack)
        assertTrue(ack!!.isPending)
        assertFalse(ack.isApproved)
        assertFalse(ack.isDenied)
    }

    @Test fun parsesDeniedAck() {
        val ack = HelloAck.parse(
            """{"type":"hello_ack","status":"denied","token":"0123456789abcdef0123456789abcdef"}"""
        )
        assertNotNull(ack)
        assertTrue(ack!!.isDenied)
        assertFalse(ack.isApproved)
    }

    @Test fun missingTokenIsNull() {
        val ack = HelloAck.parse("""{"type":"hello_ack","status":"approved"}""")
        assertNotNull(ack)
        assertNull(ack!!.token)
        // keep the previously stored token in that case (caller logic)
    }

    @Test fun emptyTokenIsNull() {
        val ack = HelloAck.parse(
            """{"type":"hello_ack","status":"approved","token":""}"""
        )
        assertNotNull(ack)
        assertNull(ack!!.token)
    }

    @Test fun ignoresNonAckMessages() {
        assertNull(HelloAck.parse("""{"type":"cmd","action":"shell"}"""))
        assertNull(HelloAck.parse("""{"type":"ping"}"""))
        assertNull(HelloAck.parse("""{"type":"result","status":"ok"}"""))
    }

    @Test fun ignoresGarbageAndWrongTypes() {
        assertNull(HelloAck.parse("not json at all"))
        assertNull(HelloAck.parse(""))
        assertNull(HelloAck.parse("null"))
        assertNull(HelloAck.parse("""{"no_type":true}"""))
    }

    @Test fun unknownStatusIsNeitherApprovedPendingNorDenied() {
        val ack = HelloAck.parse("""{"type":"hello_ack","status":"weird"}""")
        assertNotNull(ack)
        assertFalse(ack!!.isApproved)
        assertFalse(ack.isPending)
        assertFalse(ack.isDenied)
        assertEquals("weird", ack.status)
    }

    // --- hello message construction ---

    @Test fun helloIncludesEnrollKey() {
        val msg = org.json.JSONObject(
            HelloAck.buildHello("dev-1", "X Y", "13", 33, "0.2.9", 88, "sekret", null)
        )
        assertEquals("hello", msg.getString("type"))
        assertEquals("sekret", msg.getString("enroll_key"))
        assertEquals("dev-1", msg.getString("device_id"))
    }

    @Test fun helloIncludesStoredTokenWhenPresent() {
        val msg = org.json.JSONObject(
            HelloAck.buildHello("dev-1", "X Y", "13", 33, "0.2.9", 88, "sekret", "tok123")
        )
        assertEquals("tok123", msg.getString("token"))
    }

    @Test fun helloOmitsTokenWhenAbsent() {
        val msg = org.json.JSONObject(
            HelloAck.buildHello("dev-1", "X Y", "13", 33, "0.2.9", 88, "sekret", null)
        )
        assertFalse(msg.has("token"))
    }

    @Test fun roundTripHelloToAck() {
        // a token received in an ack is re-sent on the next hello
        val ack = HelloAck.parse(
            """{"type":"hello_ack","status":"approved","token":"0123456789abcdef0123456789abcdef"}"""
        )!!
        val hello = org.json.JSONObject(
            HelloAck.buildHello("dev-1", "X Y", "13", 33, "0.2.9", 88, "k", ack.token)
        )
        assertEquals("0123456789abcdef0123456789abcdef", hello.getString("token"))
    }
}
