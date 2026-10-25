package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for keyguard recognition, keypad/submit selection and the
 * unlock result envelopes. No Android service is started.
 */
class UnlockPolicyTest {

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        className: String = "android.widget.TextView",
        bounds: String = "0,0,100,100",
        clickable: Boolean = false,
        editable: Boolean = false,
        viewId: String? = null,
        children: List<NodeData> = emptyList()
    ) = NodeData(
        id = id,
        text = text,
        desc = desc,
        className = className,
        bounds = bounds,
        clickable = clickable,
        focusable = true,
        editable = editable,
        viewId = viewId,
        children = children
    )

    private fun keypad(): List<NodeData> =
        ('0'..'9').mapIndexed { i, c -> node("root/$i", text = c.toString(), clickable = true) }

    // ------------------------------------------------------------ validation

    @Test
    fun `reasonable pin shapes are accepted`() {
        assertNull(UnlockPolicy.validatePinShape("1234"))
        assertNull(UnlockPolicy.validatePinShape("123456"))
        assertNull(UnlockPolicy.validatePinShape("Abc12345!"))
        assertNull(UnlockPolicy.validatePinShape("a".repeat(32)))
    }

    @Test
    fun `blank, short and overlong pins are rejected`() {
        assertTrue(UnlockPolicy.validatePinShape("")!!.contains("4-32"))
        assertTrue(UnlockPolicy.validatePinShape("12")!!.contains("4-32"))
        assertTrue(UnlockPolicy.validatePinShape("a".repeat(33))!!.contains("4-32"))
    }

    @Test
    fun `whitespace is rejected so it cannot be typed into a field`() {
        assertTrue(UnlockPolicy.validatePinShape("12 34")!!.contains("printable"))
        assertTrue(UnlockPolicy.validatePinShape("1234\n")!!.contains("printable"))
    }

    // ----------------------------------------------------- surface safety

    @Test
    fun `an unlocked device is never a keyguard input surface`() {
        val nodes = listOf(node("root", editable = true))
        assertFalse(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "com.android.systemui.keyguard.KeyguardPasswordView",
                keyguardLocked = false,
                nodes = nodes
            )
        )
    }

    @Test
    fun `an arbitrary app is refused even when it has an editable field`() {
        assertFalse(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.example.chat",
                "com.example.chat.MainActivity",
                keyguardLocked = true,
                nodes = listOf(node("root", editable = true))
            )
        )
    }

    @Test
    fun `systemui keyguard with a pin field is recognized`() {
        assertTrue(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "com.android.systemui.keyguard.KeyguardPINView",
                keyguardLocked = true,
                nodes = listOf(node("root", editable = true))
            )
        )
    }

    @Test
    fun `a pin-like field is recognized even when the window class is generic`() {
        assertTrue(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "android.view.ViewRootImpl",
                keyguardLocked = true,
                nodes = listOf(
                    node(
                        "root",
                        editable = true,
                        viewId = "com.android.systemui:id/pin_entry"
                    )
                )
            )
        )
    }

    @Test
    fun `a systemui search field that is not pin-like is refused`() {
        assertFalse(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "com.android.systemui.qs.QSContainerImpl",
                keyguardLocked = true,
                nodes = listOf(
                    node(
                        "root",
                        editable = true,
                        className = "android.widget.EditText",
                        viewId = "com.android.systemui:id/search_field"
                    )
                )
            )
        )
    }

    @Test
    fun `a keyguard-class window is recognized even before nodes settle`() {
        assertTrue(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "com.android.systemui.keyguard.KeyguardSecurityContainer",
                keyguardLocked = true,
                nodes = listOf(node("root", text = "9:41"))
            )
        )
    }

    @Test
    fun `a systemui window that is not pin entry is refused`() {
        // e.g. quick settings/notification shade: right package, no keyguard
        // class marker and no field/keypad.
        assertFalse(
            UnlockPolicy.isRecognizedKeyguardSurface(
                "com.android.systemui",
                "com.android.systemui.qs.QSContainerImpl",
                keyguardLocked = true,
                nodes = listOf(node("root", text = "Wi-Fi"), node("root/0", text = "Bluetooth"))
            )
        )
    }

    @Test
    fun `keypad detection requires at least four single digits`() {
        val nodes = keypad()
        assertEquals(10, UnlockPolicy.keypadDigits(nodes).size)
        assertTrue(UnlockPolicy.pinEntryVisible(nodes))

        val partial = nodes.take(3) + node("root/9x", text = "12")
        assertFalse(UnlockPolicy.pinEntryVisible(partial))
    }

    @Test
    fun `editable field marks pin entry visible`() {
        assertTrue(UnlockPolicy.pinEntryVisible(listOf(node("root", editable = true))))
        assertFalse(UnlockPolicy.pinEntryVisible(listOf(node("root", text = "Swipe up"))))
    }

    // ------------------------------------------------------------- submit

    @Test
    fun `submit control prefers a recognized resource id`() {
        val nodes = listOf(
            node("root/0", text = "OK", clickable = true),
            node("root/1", viewId = "com.android.systemui:id/key_enter", clickable = true)
        )
        assertEquals("root/1", UnlockPolicy.submitControl(nodes)?.id)
    }

    @Test
    fun `submit control matches exact labels but not ordinary text`() {
        val nodes = listOf(
            node("root/0", text = "Enter your PIN"),
            node("root/1", text = "Unlock", clickable = true),
            node("root/2", text = "7", clickable = true)
        )
        assertEquals("root/1", UnlockPolicy.submitControl(nodes)?.id)
        assertNull(UnlockPolicy.submitControl(listOf(node("root/0", text = "7", clickable = true))))
    }

    // ----------------------------------------------------------- envelopes

    @Test
    fun `already-unlocked success reports no typing and no pin`() {
        val res = UnlockPolicy.unlockedResult(alreadyUnlocked = true)
        assertEquals(true, res["ok"])
        assertEquals(true, res["unlocked"])
        assertEquals(true, res["already_unlocked"])
        assertEquals(false, res["typed"])
        assertFalse("PIN must never be in a result", res.containsKey("pin"))
    }

    @Test
    fun `typed success includes steps but never the pin`() {
        val res = UnlockPolicy.unlockedResult(false, listOf("swipe_up", "keypad", "enter"))
        assertEquals(true, res["ok"])
        assertEquals(true, res["typed"])
        assertEquals(listOf("swipe_up", "keypad", "enter"), res["steps"])
        assertFalse(res.containsKey("pin"))
        assertFalse(res.containsKey("already_unlocked"))
    }

    @Test
    fun `failures are ok false with a stage and actionable error`() {
        val res = UnlockPolicy.failure(
            "reveal",
            "could not reveal the keyguard PIN/password entry; unlock the phone manually",
            steps = listOf("swipe_up"),
            typed = false
        )
        assertEquals(false, res["ok"])
        assertEquals("reveal", res["stage"])
        assertEquals(false, res["typed"])
        assertTrue((res["error"] as String).contains("unlock the phone manually"))
        assertFalse(res.containsKey("pin"))
    }

    @Test
    fun `verification failure does not blame a wrong pin`() {
        val message = "unlock not confirmed: the keyguard is still locked after submitting " +
            "the PIN. Check the PIN and that the lock screen accepts accessibility input, " +
            "then retry"
        val res = UnlockPolicy.failure("verify", message, typed = true)
        val error = res["error"] as String
        assertFalse(error.contains("wrong PIN", ignoreCase = true))
        assertTrue(error.contains("not confirmed"))
        assertTrue(error.contains("Check the PIN"))
    }
}
