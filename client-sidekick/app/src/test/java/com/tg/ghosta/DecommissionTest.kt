package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure tests for the ghostd/ghosta decommission handshake: state transitions,
 * disconnect safety, expiry and uninstall-sheet target selection.
 */
class DecommissionTest {

    private val ghostd = DecommissionPolicy.GHOSTD_PACKAGE
    private val session = "session-1"

    private fun state() = DecommissionState()

    private fun confirmed(
        s: DecommissionState = state(),
        conn: Int = 7,
        now: Long = 1_000L
    ): DecommissionState {
        assertNull(s.prepare(conn, ghostd, session, armed = true, nowMs = now))
        assertNull(s.confirm(conn, ghostd, session, nowMs = now + 10))
        return s
    }

    // ------------------------------------------------------------ prepare

    @Test
    fun `prepare requires armed consent`() {
        val s = state()
        assertNotNull(s.prepare(1, ghostd, session, armed = false, nowMs = 0))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertNull(s.prepare(1, ghostd, session, armed = true, nowMs = 0))
        assertEquals(DecommissionState.Stage.PREPARED, s.stage)
    }

    @Test
    fun `prepare only accepts the exact ghostd package and a session`() {
        val s = state()
        assertNotNull(s.prepare(1, "com.android.settings", session, true, 0))
        assertNotNull(s.prepare(1, ghostd, "  ", true, 0))
        assertNotNull(s.prepare(1, ghostd, null, true, 0))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertNull(s.prepare(1, ghostd, session, true, 0))
        assertEquals(ghostd, s.targetPackage)
        assertEquals(session, s.session)
    }

    @Test
    fun `a second prepare is rejected until cancel or expiry`() {
        val s = state()
        assertNull(s.prepare(1, ghostd, session, true, 0))
        assertNotNull(s.prepare(2, ghostd, "other", true, 10))
        assertEquals(session, s.session)
    }

    // ----------------------------------------------------------- confirm

    @Test
    fun `confirm needs a matching prepared session and binds the confirming link`() {
        val s = state()
        assertNotNull(s.confirm(1, ghostd, session, 0))
        assertNull(s.prepare(1, ghostd, session, true, 0))
        assertNotNull(s.confirm(1, ghostd, "wrong", 10))
        assertNotNull(s.confirm(1, "com.android.settings", session, 10))
        assertEquals(DecommissionState.Stage.PREPARED, s.stage)
        // A link retry between prepare and confirm is allowed; the watcher is
        // bound to the connection that actually carried the confirmation.
        assertNull(s.confirm(2, ghostd, session, 20))
        assertEquals(DecommissionState.Stage.CONFIRMED, s.stage)
        assertEquals(2, s.connectionId)
    }

    // -------------------------------------------------- disconnect safety

    @Test
    fun `routine disconnects never arm the watcher`() {
        val idle = state()
        assertFalse(idle.onConnectionClosed(1, 10))

        val prepared = state()
        assertNull(prepared.prepare(1, ghostd, session, true, 0))
        assertFalse(prepared.onConnectionClosed(1, 10))

        val confirmed = confirmed()
        assertFalse("another link closing is not ghostd's removal",
            confirmed.onConnectionClosed(999, 2_000))
        assertEquals(DecommissionState.Stage.CONFIRMED, confirmed.stage)
    }

    @Test
    fun `confirmed link close waits the grace period then self-uninstalls`() {
        val s = confirmed(conn = 7, now = 1_000)
        assertTrue(s.onConnectionClosed(7, 1_100))
        assertEquals(DecommissionState.Stage.DISCONNECTED, s.stage)

        assertEquals(
            DecommissionWatcherOutcome.WAIT,
            s.watcherOutcome(1_100 + s.graceMs - 1)
        )
        // The confirmed sheet may still be animating out; late window events
        // are still handled while the flow is active.
        assertTrue(s.allowsUninstallTap(1_100 + s.graceMs - 1))

        assertEquals(DecommissionWatcherOutcome.UNINSTALL, s.watcherOutcome(1_100 + s.graceMs))
        assertEquals(DecommissionState.Stage.SELF_UNINSTALLING, s.stage)
        assertTrue(s.allowsUninstallTap(1_100 + s.graceMs))
    }

    @Test
    fun `a reconnect during the grace period cancels the self-uninstall`() {
        val s = confirmed()
        assertTrue(s.onConnectionClosed(7, 2_000))
        assertTrue(s.onConnectionOpened(3_000))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertEquals(DecommissionWatcherOutcome.ABORT, s.watcherOutcome(20_000))

        assertFalse("the watcher must not fire after a cancel", s.allowsUninstallTap(20_000))
    }

    @Test
    fun `a reconnect while confirmed cancels the handshake`() {
        val s = confirmed()
        assertTrue(s.onConnectionOpened(2_000))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertFalse(s.onConnectionClosed(7, 3_000))
    }

    @Test
    fun `a reconnect while merely prepared does not reset it`() {
        val s = state()
        assertNull(s.prepare(1, ghostd, session, true, 0))
        assertFalse(s.onConnectionOpened(10))
        assertEquals(DecommissionState.Stage.PREPARED, s.stage)
    }

    @Test
    fun `expired confirmation cannot be used later`() {
        val s = confirmed(now = 1_000) // confirm at 1_010
        val later = 1_010 + s.ttlMs + 1
        assertFalse(s.onConnectionClosed(7, later))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
    }

    @Test
    fun `an old disconnect cannot self-uninstall after the ttl`() {
        val s = confirmed(now = 1_000)
        assertTrue(s.onConnectionClosed(7, 2_000))
        assertEquals(
            DecommissionWatcherOutcome.ABORT,
            s.watcherOutcome(2_000 + s.graceMs + s.ttlMs + 1)
        )
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
    }

    @Test
    fun `cancel requires the matching session and stops the watcher`() {
        val s = confirmed()
        assertFalse(s.cancel("other", 2_000))
        assertEquals(DecommissionState.Stage.CONFIRMED, s.stage)
        assertTrue(s.cancel(session, 2_000))
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertFalse(s.onConnectionClosed(7, 3_000))
    }

    @Test
    fun `a blank session cancel is an unconditional reset for retries`() {
        // ghostd sends this before every new prepare so a failed attempt is
        // immediately retryable; it is an explicit verb, not a disconnect.
        for (blank in listOf(null, "", "   ")) {
            val confirmedState = confirmed()
            assertTrue(confirmedState.cancel(blank, 2_000))
            assertEquals(DecommissionState.Stage.IDLE, confirmedState.stage)
            assertFalse(
                "unconditional reset must drop the Uninstall tap window",
                confirmedState.allowsUninstallTap(2_000)
            )

            val preparedState = state()
            assertNull(preparedState.prepare(1, ghostd, session, true, 0))
            assertTrue(preparedState.cancel(blank, 10))
            assertEquals(DecommissionState.Stage.IDLE, preparedState.stage)
            assertNull(
                "the same session can be prepared again after a reset",
                preparedState.prepare(1, ghostd, session, true, 20)
            )
        }
        // An idle state stays harmless.
        assertTrue(state().cancel(null, 0))
    }

    @Test
    fun `an unconditional reset cannot remove the sidekick by itself`() {
        val s = confirmed()
        assertTrue(s.cancel(null, 2_000))
        assertFalse("a reset must never arm the disconnect watcher", s.onConnectionClosed(7, 3_000))
        assertEquals(DecommissionWatcherOutcome.ABORT, s.watcherOutcome(30_000))
    }

    // ------------------------------------------------------------- taps

    @Test
    fun `uninstall labels are only permitted inside the decommission window`() {
        val s = state()
        assertFalse(s.allowsUninstallTap(0))
        assertNull(s.prepare(1, ghostd, session, true, 0))
        assertTrue("prepared must suppress installer auto-confirm", s.isPreparing(1))
        assertFalse("preparation alone must not permit Uninstall taps", s.allowsUninstallTap(1))
        assertNull(s.confirm(1, ghostd, session, 2))
        assertFalse(s.isPreparing(3))
        assertTrue(s.allowsUninstallTap(3))
        assertTrue(s.onConnectionClosed(1, 4))
        assertTrue(s.allowsUninstallTap(5))
    }

    @Test
    fun `a failed self-uninstall launch drops the flow`() {
        val s = confirmed()
        s.onConnectionClosed(7, 2_000)
        assertEquals(DecommissionWatcherOutcome.UNINSTALL, s.watcherOutcome(2_000 + s.graceMs))
        s.selfUninstallResult(false)
        assertEquals(DecommissionState.Stage.IDLE, s.stage)
        assertFalse(s.allowsUninstallTap(2_000 + s.graceMs))
    }

    @Test
    fun `an open self-uninstall sheet expires instead of permitting taps forever`() {
        val s = confirmed()
        s.onConnectionClosed(7, 2_000)
        val fired = 2_000 + s.graceMs
        assertEquals(DecommissionWatcherOutcome.UNINSTALL, s.watcherOutcome(fired))
        s.selfUninstallResult(true)
        assertTrue(s.allowsUninstallTap(fired + 1_000))
        assertFalse(
            s.allowsUninstallTap(fired + DecommissionState.TAP_WINDOW_MS + 1)
        )
    }

    // ------------------------------------------------------------ policy

    @Test
    fun `recognizes only system uninstall windows`() {
        assertTrue(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity"
            )
        )
        assertTrue(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.google.android.packageinstaller",
                "com.google.android.packageinstaller.UninstallUninstallerActivity"
            )
        )
        assertFalse(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.android.systemui",
                "com.android.systemui.keyguard.KeyguardViewMediator"
            )
        )
        assertFalse(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.example.chat",
                "com.example.chat.UninstallerActivity"
            )
        )
        assertFalse(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.android.packageinstaller", null
            )
        )
        assertFalse(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.android.packageinstaller",
                "com.android.packageinstaller.PackageInstallerActivity"
            )
        )
    }

    @Test
    fun `picks the clickable Uninstall button and never Cancel`() {
        val nodes = listOf(
            node("root", className = "android.widget.FrameLayout"),
            node("root/0", text = "Cancel", clickable = true),
            node("root/1", text = "Uninstall", clickable = true)
        )
        val target = DecommissionPolicy.pickUninstallTarget(nodes)
        assertNotNull(target)
        assertTrue(target!!.target is TapTarget.Node)
        assertEquals("root/1", (target.target as TapTarget.Node).id)
        assertEquals("Uninstall", target.label)
    }

    @Test
    fun `clicks the nearest clickable ancestor of an Uninstall label`() {
        val nodes = listOf(
            node("root", className = "android.widget.FrameLayout"),
            node("root/0", clickable = true, bounds = "0,0,200,80"),
            node("root/0/1", text = "Uninstall", clickable = false, bounds = "10,10,190,70")
        )
        val target = DecommissionPolicy.pickUninstallTarget(nodes)
        assertNotNull(target)
        assertEquals("root/0", (target!!.target as TapTarget.Node).id)
    }

    @Test
    fun `falls back to a bounded centre tap inside the display`() {
        val nodes = listOf(
            node("root/0", text = "Uninstall", clickable = false, bounds = "100,200,300,260")
        )
        val target = DecommissionPolicy.pickUninstallTarget(nodes, 1080, 2400)
        assertNotNull(target)
        val point = target!!.target as TapTarget.Point
        assertEquals(200, point.x)
        assertEquals(230, point.y)
    }

    @Test
    fun `refuses an off-screen coordinate fallback`() {
        val nodes = listOf(
            node("root/0", text = "Uninstall", clickable = false, bounds = "100,200,3000,2600")
        )
        assertNull(DecommissionPolicy.pickUninstallTarget(nodes, 1080, 2400))
    }

    @Test
    fun `an install-only sheet yields no uninstall target`() {
        val nodes = listOf(
            node("root/0", text = "Install", clickable = true),
            node("root/1", text = "Cancel", clickable = true)
        )
        assertNull(DecommissionPolicy.pickUninstallTarget(nodes))
    }

    @Test
    fun `a recognized uninstall window only uses uninstall vocabulary while permitted`() {
        val nodes = listOf(
            node("root/0", text = "OK", clickable = true),
            node("root/1", text = "Uninstall", clickable = true),
            node("root/2", text = "Cancel", clickable = true)
        )
        val target = DecommissionPolicy.pickWindowTarget(
            nodes,
            ConsentKind.INSTALLER,
            "com.android.packageinstaller",
            "com.android.packageinstaller.UninstallerActivity",
            uninstallTapAllowed = true
        )
        assertNotNull(target)
        assertEquals("Uninstall", target!!.label)
        assertEquals("root/1", (target.target as TapTarget.Node).id)

        // Not permitted (no active/confirmed decommission): no tap at all.
        // The generic installer vocabulary must never be used on an uninstall
        // sheet, even though "OK" is a generic installer label.
        assertNull(
            DecommissionPolicy.pickWindowTarget(
                nodes,
                ConsentKind.INSTALLER,
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                uninstallTapAllowed = false
            )
        )
    }

    @Test
    fun `a generic installer window keeps the unchanged installer vocabulary`() {
        val nodes = listOf(
            node("root/0", text = "Install", clickable = true),
            node("root/1", text = "Cancel", clickable = true)
        )
        val target = DecommissionPolicy.pickWindowTarget(
            nodes,
            ConsentKind.INSTALLER,
            "com.android.packageinstaller",
            "com.android.packageinstaller.PackageInstallerActivity",
            uninstallTapAllowed = true
        )
        assertNotNull(target)
        assertEquals("Install", target!!.label)
        assertEquals("root/0", (target.target as TapTarget.Node).id)
    }

    @Test
    fun `a non-system window is never treated as an uninstall sheet`() {
        val nodes = listOf(node("root/0", text = "Install", clickable = true))
        val target = DecommissionPolicy.pickWindowTarget(
            nodes,
            ConsentKind.INSTALLER,
            "com.example.chat",
            "com.example.chat.UninstallerActivity",
            uninstallTapAllowed = true
        )
        assertNotNull(target)
        assertEquals("Install", target!!.label)
        assertFalse(
            DecommissionPolicy.isRecognizedUninstallWindow(
                "com.example.chat", "com.example.chat.UninstallerActivity"
            )
        )
    }

    // --------------------------------------------------------- dispatcher

    @Before
    fun resetDispatcher() {
        Decommission.state.reset()
    }

    @Test
    fun `dispatcher enforces the prepare confirm sequence`() {
        val rejected = Decommission.handle(
            "prepare", ghostd, session, 1,
            armed = false, overlayGranted = true, nowMs = 0
        )
        assertEquals(false, rejected["ok"])

        val prepared = Decommission.handle(
            "prepare", ghostd, session, 1,
            armed = true, overlayGranted = true, nowMs = 10
        )
        assertEquals(true, prepared["ok"])
        assertEquals("prepared", prepared["stage"])

        val confirmed = Decommission.handle(
            "confirmed", ghostd, session, 1,
            armed = true, overlayGranted = true, nowMs = 20
        )
        assertEquals(true, confirmed["ok"])
        assertEquals("confirmed", confirmed["stage"])
        assertEquals(ghostd, confirmed["package"])

        val status = Decommission.handle(
            "status", null, null, 1,
            armed = true, overlayGranted = true, nowMs = 30
        )
        assertEquals(true, status["ok"])
        assertEquals("confirmed", status["stage"])
        assertFalse(status.containsKey("session"))
    }

    @Test
    fun `dispatcher rejects unknown actions`() {
        val res = Decommission.handle(
            "detonate", ghostd, session, 1,
            armed = true, overlayGranted = true, nowMs = 0
        )
        assertEquals(false, res["ok"])
        assertTrue((res["error"] as String).contains("prepare|confirmed|cancel"))
    }

    @Test
    fun `prepare is blocked with a distinct result before ghostd uninstalls`() {
        val blocked = Decommission.handle(
            "prepare", ghostd, session, 1,
            armed = true, overlayGranted = false, nowMs = 10
        )
        assertEquals(false, blocked["ok"])
        assertEquals("self_uninstall_blocked", blocked["code"])
        assertEquals("prepare", blocked["stage"])
        val message = blocked["error"] as String
        assertTrue(message.contains("self_uninstall_blocked"))
        assertTrue(message.contains("Display over other apps"))
        assertTrue(message.contains("Nothing was uninstalled"))

        // Nothing was left prepared: the flow is idle and not preparing.
        assertEquals(DecommissionState.Stage.IDLE, Decommission.state.stage)
        assertFalse(Decommission.state.isPreparing(11))
        val status = Decommission.handle(
            "status", null, null, 1, true, overlayGranted = true, nowMs = 20
        )
        assertEquals("idle", status["stage"])
    }

    @Test
    fun `dispatcher accepts a blank session cancel as unconditional reset`() {
        Decommission.handle(
            "prepare", ghostd, session, 1, true, overlayGranted = true, nowMs = 10
        )
        Decommission.handle(
            "confirmed", ghostd, session, 1, true, overlayGranted = true, nowMs = 20
        )
        assertEquals(DecommissionState.Stage.CONFIRMED, Decommission.state.stage)

        val reset = Decommission.handle(
            "cancel", ghostd, null, 2, true, overlayGranted = true, nowMs = 30
        )
        assertEquals(true, reset["ok"])
        assertEquals(DecommissionState.Stage.IDLE, Decommission.state.stage)
        assertFalse(Decommission.state.allowsUninstallTap(31))
    }

    // ------------------------------------------------------------- helpers

    private fun node(
        id: String,
        text: String? = null,
        desc: String? = null,
        className: String = "android.widget.TextView",
        bounds: String = "0,0,100,100",
        clickable: Boolean = false
    ) = NodeData(
        id = id,
        text = text,
        desc = desc,
        className = className,
        bounds = bounds,
        clickable = clickable,
        focusable = true,
        editable = false,
        viewId = null
    )
}
