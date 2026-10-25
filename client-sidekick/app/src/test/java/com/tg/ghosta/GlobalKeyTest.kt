package com.tg.ghosta

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalKeyTest {
    @Test
    fun `navigation keys use accessibility global actions`() {
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME,
            globalActionForKey(KeyEvent.KEYCODE_HOME))
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK,
            globalActionForKey(KeyEvent.KEYCODE_BACK))
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS,
            globalActionForKey(KeyEvent.KEYCODE_APP_SWITCH))
        assertNull(globalActionForKey(KeyEvent.KEYCODE_ENTER))
    }
}
