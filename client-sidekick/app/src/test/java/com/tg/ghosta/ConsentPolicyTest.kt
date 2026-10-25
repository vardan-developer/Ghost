package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentPolicyTest {

    private val projectionClass =
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity"

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        className: String = "android.widget.TextView",
        bounds: String = "0,0,100,100",
        clickable: Boolean = false,
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
        editable = false,
        viewId = viewId,
        children = children
    )

    private fun window(windowId: Int = 1) = ConsentWindow(
        ConsentKind.MEDIA_PROJECTION,
        "com.android.systemui",
        "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionActivity",
        windowId
    )

    // ---------------------------------------------------------------- //
    // window recognition / arming

    @Test
    fun `media projection consent requires an active projection expectation`() {
        val w = ConsentPolicy.windowFor(
            packageName = "com.android.systemui",
            className = projectionClass,
            keyguardLocked = false,
            armed = false,
            projectionExpected = true
        )
        assertNotNull(w)
        assertEquals(ConsentKind.MEDIA_PROJECTION, w?.kind)
    }

    @Test
    fun `projection consent is ignored without an expectation`() {
        // Not always-on and not enabled by the generic arm: another app's
        // capture sheet (or a stale one) must not be tapped.
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", projectionClass,
                keyguardLocked = false, armed = false
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", projectionClass,
                keyguardLocked = false, armed = true
            )
        )
    }

    @Test
    fun `projection expectation does not arm installer or permission consent`() {
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.packageinstaller",
                "com.android.packageinstaller.PackageInstallerActivity",
                keyguardLocked = false, armed = false, projectionExpected = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.permissioncontroller",
                "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity",
                keyguardLocked = false, armed = false, projectionExpected = true
            )
        )
    }

    @Test
    fun `projection expectation never makes another app's capture sheet tappable`() {
        assertNull(
            ConsentPolicy.windowFor(
                "com.example.notes",
                "com.example.notes.MediaProjectionPermissionActivity",
                keyguardLocked = false, armed = true, projectionExpected = true
            )
        )
    }

    @Test
    fun `installer consent requires armed`() {
        val cls = "com.android.packageinstaller.PackageInstallerActivity"
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.packageinstaller", cls,
                keyguardLocked = false, armed = false
            )
        )
        val w = ConsentPolicy.windowFor(
            "com.android.packageinstaller", cls,
            keyguardLocked = false, armed = true
        )
        assertEquals(ConsentKind.INSTALLER, w?.kind)
    }

    @Test
    fun `permission consent requires armed`() {
        val cls = "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.permissioncontroller", cls,
                keyguardLocked = false, armed = false
            )
        )
        val w = ConsentPolicy.windowFor(
            "com.android.permissioncontroller", cls,
            keyguardLocked = false, armed = true
        )
        assertEquals(ConsentKind.PERMISSION, w?.kind)
    }

    @Test
    fun `class markers are required not only a system package`() {
        // Keyguard / shade windows live in com.android.systemui too.
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui",
                "com.android.systemui.keyguard.KeyguardViewMediator",
                keyguardLocked = false, armed = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui",
                "com.android.systemui.shade.NotificationShadeWindowView",
                keyguardLocked = false, armed = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", null,
                keyguardLocked = false, armed = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "android", "android.app.AlertDialog",
                keyguardLocked = false, armed = true
            )
        )
    }

    @Test
    fun `arbitrary application packages are never recognized`() {
        // A random app with a matching-looking activity class must be ignored.
        assertNull(
            ConsentPolicy.windowFor(
                "com.example.notes",
                "com.example.notes.MediaProjectionPermissionActivity",
                keyguardLocked = false, armed = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui.evil", projectionClass,
                keyguardLocked = false, armed = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.tg.ghostd", "com.tg.ghostd.MainActivity",
                keyguardLocked = false, armed = true
            )
        )
    }

    // ---------------------------------------------------------------- //
    // keyguard fail-closed

    @Test
    fun `keyguard query failures are treated as locked`() {
        // A throwing query must fail closed, never "unlocked".
        assertTrue(
            ConsentPolicy.keyguardLockedOrFailClosed { throw IllegalStateException("no service") }
        )
        // Missing KeyguardManager is unknown, so also locked.
        assertTrue(ConsentPolicy.keyguardLockedOrFailClosed { null })
        // A real unlocked state is the only false case.
        assertFalse(ConsentPolicy.keyguardLockedOrFailClosed { false })
        assertTrue(ConsentPolicy.keyguardLockedOrFailClosed { true })
    }

    @Test
    fun `projection window is recognized only while the expectation is live`() {
        val expectation = ProjectionExpectation()
        expectation.expect(nowMs = 1_000, ttlMs = 15_000)
        assertNotNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", projectionClass,
                keyguardLocked = false, armed = false,
                projectionExpected = expectation.isActive(2_000)
            )
        )
        // One millisecond after the TTL the same window is ignored, even
        // though generic arming may still be on.
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", projectionClass,
                keyguardLocked = false, armed = true,
                projectionExpected = expectation.isActive(16_000)
            )
        )
    }

    @Test
    fun `keyguard blocks every consent surface`() {
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.systemui", projectionClass,
                keyguardLocked = true, armed = true, projectionExpected = true
            )
        )
        assertNull(
            ConsentPolicy.windowFor(
                "com.android.packageinstaller",
                "com.android.packageinstaller.PackageInstallerActivity",
                keyguardLocked = true, armed = true
            )
        )
    }

    // ---------------------------------------------------------------- //
    // target prioritisation

    @Test
    fun `clickable affirmative is preferred over a text label`() {
        val tree = node(
            "root",
            children = listOf(
                node("root/0", text = "Start now"),
                node("root/1", text = "Start now", clickable = true)
            )
        )
        val target = ConsentPolicy.pickTarget(
            NodeTree.flatten(tree), ConsentKind.MEDIA_PROJECTION
        )
        assertEquals(TapTarget.Node("root/1"), target?.target)
    }

    @Test
    fun `non-clickable label uses nearest clickable ancestor`() {
        val row = node(
            "root/0",
            clickable = true,
            children = listOf(
                node("root/0/0", text = "Share screen")
            )
        )
        val tree = node("root", children = listOf(row))
        val target = ConsentPolicy.pickTarget(
            NodeTree.flatten(tree), ConsentKind.MEDIA_PROJECTION
        )
        assertEquals(TapTarget.Node("root/0"), target?.target)
        assertEquals("Share screen", target?.label)
    }

    @Test
    fun `whole display selection and affirmative labels are supported`() {
        val tree = node(
            "root",
            children = listOf(
                node("root/0", text = "Entire screen", clickable = true),
                node("root/1", text = "Start now", clickable = true)
            )
        )
        // "Start now" outranks the selection row.
        assertEquals(
            "Start now",
            ConsentPolicy.pickTarget(NodeTree.flatten(tree), ConsentKind.MEDIA_PROJECTION)?.label
        )
        val fullscreenOnly = node(
            "root",
            children = listOf(node("root/0", text = "Full screen", clickable = true))
        )
        assertEquals(
            "Full screen",
            ConsentPolicy.pickTarget(
                NodeTree.flatten(fullscreenOnly), ConsentKind.MEDIA_PROJECTION
            )?.label
        )
    }

    @Test
    fun `non-clickable label falls back to bounded coordinates`() {
        val tree = node(
            "root",
            bounds = "0,0,1080,2400",
            children = listOf(
                node("root/0", text = "Entire screen", bounds = "100,2000,980,2100")
            )
        )
        val target = ConsentPolicy.pickTarget(
            NodeTree.flatten(tree), ConsentKind.MEDIA_PROJECTION, 1080, 2400
        )
        assertEquals(TapTarget.Point(540, 2050), target?.target)
    }

    @Test
    fun `coordinate fallback rejects empty and off-screen bounds`() {
        val empty = node(
            "root",
            children = listOf(
                node("root/0", text = "Share screen", bounds = "0,0,0,0")
            )
        )
        assertNull(
            ConsentPolicy.pickTarget(
                NodeTree.flatten(empty), ConsentKind.MEDIA_PROJECTION, 1080, 2400
            )
        )
        val offscreen = node(
            "root",
            children = listOf(
                node("root/0", text = "Share screen", bounds = "100,2300,1200,2500")
            )
        )
        assertNull(
            ConsentPolicy.pickTarget(
                NodeTree.flatten(offscreen), ConsentKind.MEDIA_PROJECTION, 1080, 2400
            )
        )
    }

    @Test
    fun `cancel and stop labels are never targets`() {
        val tree = node(
            "root",
            children = listOf(
                node("root/0", text = "Cancel", clickable = true),
                node("root/1", text = "Stop sharing", clickable = true),
                node("root/2", text = "Don't allow", clickable = true)
            )
        )
        assertNull(
            ConsentPolicy.pickTarget(NodeTree.flatten(tree), ConsentKind.MEDIA_PROJECTION)
        )
        assertNull(
            ConsentPolicy.pickTarget(NodeTree.flatten(tree), ConsentKind.PERMISSION)
        )
    }

    @Test
    fun `permission labels match but installer labels do not leak across kinds`() {
        val permissionTree = node(
            "root",
            children = listOf(
                node("root/0", text = "While using the app", clickable = true)
            )
        )
        assertEquals(
            TapTarget.Node("root/0"),
            ConsentPolicy.pickTarget(
                NodeTree.flatten(permissionTree), ConsentKind.PERMISSION
            )?.target
        )
        assertNull(
            ConsentPolicy.pickTarget(
                NodeTree.flatten(permissionTree), ConsentKind.INSTALLER
            )
        )
    }

    // ---------------------------------------------------------------- //
    // attempt bookkeeping

    @Test
    fun `attempts are capped per consent window`() {
        val tracker = ConsentAttemptTracker()
        tracker.observe(window(windowId = 1))
        assertTrue(tracker.beginAttempt("node:root/0", 1_000))
        assertTrue(tracker.beginAttempt("node:root/1", 2_000))
        assertTrue(tracker.beginAttempt("node:root/2", 3_000))
        assertFalse(tracker.canAttempt())
        assertFalse(tracker.beginAttempt("node:root/3", 4_000))
        assertEquals(ConsentPolicy.MAX_ATTEMPTS, tracker.attempts)
    }

    @Test
    fun `a real window change resets attempts and same-node suppression`() {
        val tracker = ConsentAttemptTracker()
        tracker.observe(window(windowId = 1))
        tracker.beginAttempt("node:root/0", 1_000)
        tracker.beginAttempt("node:root/0", 2_000)
        tracker.beginAttempt("node:root/0", 3_000)
        assertFalse(tracker.canAttempt())

        // Reopened consent sheet: new window id, same package/class/target.
        assertTrue(tracker.observe(window(windowId = 2)))
        assertEquals(0, tracker.attempts)
        assertTrue(tracker.beginAttempt("node:root/0", 3_100))
    }

    @Test
    fun `leaving the consent window resets tracking`() {
        val tracker = ConsentAttemptTracker()
        tracker.observe(window(windowId = 1))
        tracker.beginAttempt("node:root/0", 1_000)
        assertFalse(tracker.observe(null))
        assertNull(tracker.window)
        assertEquals(0, tracker.attempts)
        assertTrue(tracker.canAttempt())
    }

    @Test
    fun `rapid duplicate taps are suppressed but a later retry is allowed`() {
        val tracker = ConsentAttemptTracker()
        tracker.observe(window(windowId = 1))
        assertTrue(tracker.beginAttempt("node:root/0", 1_000))
        assertFalse(tracker.beginAttempt("node:root/0", 1_100))
        assertFalse(tracker.beginAttempt("node:root/0", 1_300))
        // Past both the global interval and same-target suppression.
        assertTrue(tracker.beginAttempt("node:root/0", 1_800))
        assertEquals(2, tracker.attempts)
    }

    @Test
    fun `different steps in the same window are allowed promptly`() {
        val tracker = ConsentAttemptTracker()
        tracker.observe(window(windowId = 1))
        assertTrue(tracker.beginAttempt("node:root/0", 1_000))
        // "Entire screen" -> "Share screen" after the global interval.
        assertTrue(tracker.beginAttempt("node:root/1", 1_700))
        assertEquals(2, tracker.attempts)
    }
}
