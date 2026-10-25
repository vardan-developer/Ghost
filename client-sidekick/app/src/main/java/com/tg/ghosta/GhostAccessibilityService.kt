package com.tg.ghosta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.app.Instrumentation
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The sidekick accessibility service. Generic UI-driving verbs only — no
 * hardcoded flows (see PROJECT.md "Dynamic UI driving"). The operator
 * inspects the tree via `dump`/`find` and decides what to tap.
 */
class GhostAccessibilityService : AccessibilityService(), UiDriver {

    companion object {
        @Volatile
        var instance: GhostAccessibilityService? = null
            private set

        /**
         * When armed, the service auto-taps buttons matching these exact
         * (lowercased, trimmed) texts — powers auto-confirming
         * MediaProjection ("Start now") and installer dialogs.
         * Easy to extend: just add entries.
         */
        val CONFIRM_TEXTS = listOf(
            "allow",
            "ok",
            "okay",
            "start now",
            "start",
            "start recording",
            "install",
            "install anyway",
            "update",
            "continue",
            "while using the app",
            // Android 14+ screen-capture consent sheet (whole-display):
            // "Share entire screen" summary + "Share screen" button.
            "share screen",
            "start recording or casting",
            "allow all the time",
            "got it"
        )

        /**
         * Packages whose dialogs may be auto-confirmed. Anything else (a
         * random app's "OK"/"Share" button) must never be tapped blind.
         */
        val SYSTEM_DIALOG_PKGS = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.systemui",
            "android"
        )

        private const val ROOT_ID = "root"
        private const val TAP_MS = 60L
        private const val LONG_MS = 800L

        /** ghostd kick broadcast action (received by ghostd's KickReceiver). */
        const val KICK_ACTION = "com.tg.ghostd.KICK"
        private const val BABYSIT_CHECK_MS = 5 * 60_000L   // check every 5 min
        private const val BABYSIT_STALE_MS = 15 * 60_000L   // stale after 15 min
    }

    private var link: LocalLinkServer? = null

    @Volatile
    private var armed = false
    private var lastAutoTapAt = 0L
    private var lastAutoTapKey = ""

    /** Babysitter: ghostd beacons over the local link every heartbeat; if it
     *  goes silent too long it's dead or wedged — kick it back to life.
     *  (ghostd's own watchdog can't recover a wedged main loop; a separate
     *  process can.) */
    private val babysitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val babysitCheck = object : Runnable {
        override fun run() {
            val last = LocalLinkServer.lastAliveMs
            val idle = SystemClock.elapsedRealtime() - last
            // last==0: ghostd never beaconed since we started — kick too
            if (last == 0L || idle > BABYSIT_STALE_MS) {
                try {
                    sendBroadcast(android.content.Intent(KICK_ACTION)
                        .setPackage("com.tg.ghostd"))
                } catch (_: Exception) {
                }
            }
            babysitHandler.postDelayed(this, BABYSIT_CHECK_MS)
        }
    }

    // ------------------------------------------------------------------ //
    // lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        link = LocalLinkServer(this).also { it.start() }
        // Once the system binds us, the launcher icon has done its job.
        MainActivity.hideLauncherIcon(this)
        babysitHandler.removeCallbacks(babysitCheck)
        babysitHandler.postDelayed(babysitCheck, BABYSIT_CHECK_MS)
    }

    override fun onDestroy() {
        babysitHandler.removeCallbacks(babysitCheck)
        link?.stop()
        link = null
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent): Boolean {
        // service about to be disabled — stop listening until rebind
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> autoConfirm()
        }
    }

    // ------------------------------------------------------------------ //
    // armed auto-confirm

    private fun autoConfirm() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoTapAt < 1200) return
        val root = rootInActiveWindow ?: return
        // Only system confirmation surfaces (install sheet, runtime-permission
        // prompt, screen-share consent). Tapping "ok"/"share" inside a random
        // app's UI would be wrong, so everything else is ignored.
        val pkg = root.packageName?.toString() ?: ""
        if (SYSTEM_DIALOG_PKGS.none { pkg.startsWith(it) }) return
        val snap = snapshot(root) ?: return
        val flat = NodeTree.flatten(snap)
        val cand = flat.firstOrNull { confirmText(it) } ?: return
        val target = if (cand.clickable) cand else ancestorClickable(cand, flat)
        if (target != null) {
            if (target.id == lastAutoTapKey && now - lastAutoTapAt < 5000) return
            lastAutoTapAt = now
            lastAutoTapKey = target.id
            performClick(target.id)
            return
        }
        // OEM dialogs sometimes expose only a non-clickable label: tap its
        // centre directly (this is what unblocked the screen-share sheet).
        val c = centreOf(cand.bounds) ?: return
        if (cand.id == lastAutoTapKey && now - lastAutoTapAt < 5000) return
        lastAutoTapAt = now
        lastAutoTapKey = cand.id
        dispatchGestureAt(c.first, c.second, 60)
    }

    /** Nearest clickable ancestor of [node] (buttons often wrap a TextView). */
    private fun ancestorClickable(node: NodeData, flat: List<NodeData>): NodeData? {
        val byId = flat.associateBy { it.id }
        var path = node.id
        while (true) {
            val cut = path.lastIndexOf('/')
            if (cut <= 0) return null
            path = path.substring(0, cut)
            val a = byId[path] ?: return null
            if (a.clickable) return a
        }
    }

    /** "x1,y1,x2,y2" -> centre point. */
    private fun centreOf(bounds: String?): Pair<Int, Int>? = try {
        val p = (bounds ?: return null).replace("][", ",").trim('[', ']').split(',')
        val (x1, y1, x2, y2) = p.take(4).map { it.trim().toInt() }
        Pair((x1 + x2) / 2, (y1 + y2) / 2)
    } catch (_: Exception) {
        null
    }

    private fun confirmText(n: NodeData): Boolean {
        val t = (n.text ?: n.desc ?: return false).trim().lowercase()
        return CONFIRM_TEXTS.contains(t)
    }

    // ------------------------------------------------------------------ //
    // tree snapshot helpers

    /** Walk the live node tree into pure [NodeData] (id = index path). */
    private fun snapshot(root: AccessibilityNodeInfo?): NodeData? {
        if (root == null) return null

        fun conv(n: AccessibilityNodeInfo, path: String): NodeData {
            val r = Rect()
            n.getBoundsInScreen(r)
            val kids = ArrayList<NodeData>(n.childCount)
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (c != null) kids.add(conv(c, "$path/$i"))
            }
            return NodeData(
                id = path,
                text = n.text?.toString(),
                desc = n.contentDescription?.toString(),
                className = n.className?.toString(),
                bounds = "${r.left},${r.top},${r.right},${r.bottom}",
                clickable = n.isClickable,
                focusable = n.isFocusable,
                editable = n.isEditable,
                viewId = n.viewIdResourceName,
                children = kids
            )
        }
        return conv(root, ROOT_ID)
    }

    /** Live AccessibilityNodeInfo for an id path ("root/0/3"). */
    private fun liveNode(id: String?): AccessibilityNodeInfo? {
        if (id == null) return null
        val root = rootInActiveWindow ?: return null
        var node: AccessibilityNodeInfo = root
        val rest = if (id == ROOT_ID) "" else id.removePrefix(ROOT_ID).removePrefix("/")
        for (seg in rest.split('/')) {
            if (seg.isEmpty()) continue
            val idx = seg.toIntOrNull() ?: return null
            val child = try {
                node.getChild(idx)
            } catch (_: Exception) {
                null
            } ?: return null
            node = child
        }
        return node
    }

    // ------------------------------------------------------------------ //
    // UiDriver implementation (called from link server threads)

    override fun dump(): Map<String, Any?> {
        val snap = snapshot(rootInActiveWindow)
            ?: return CommandRouter.err("no active window")
        return CommandRouter.ok(mapOf("tree" to NodeTree.toMap(snap)))
    }

    override fun find(text: String?, id: String?): Map<String, Any?> {
        val snap = snapshot(rootInActiveWindow)
            ?: return CommandRouter.err("no active window")
        val hits = NodeTree.find(snap, text, id)
        return CommandRouter.ok(mapOf("nodes" to hits.map { NodeTree.toShallowMap(it) }))
    }

    override fun tap(x: Int?, y: Int?, id: String?): Map<String, Any?> =
        clickCommon("tap", x, y, id, TAP_MS)

    override fun longClick(x: Int?, y: Int?, id: String?): Map<String, Any?> =
        clickCommon("longclick", x, y, id, LONG_MS)

    private fun clickCommon(what: String, x: Int?, y: Int?, id: String?, ms: Long): Map<String, Any?> {
        if (id != null) {
            val done = performClick(id)
            return if (done) CommandRouter.ok(mapOf("what" to what, "id" to id))
            else CommandRouter.err("click failed for id $id")
        }
        if (x != null && y != null) {
            val done = dispatchGestureAt(x, y, ms)
            return if (done) CommandRouter.ok(mapOf("what" to what, "x" to x, "y" to y))
            else CommandRouter.err("gesture failed")
        }
        return CommandRouter.err("need id or x,y")
    }

    /** Click a node by id path; walks up to the nearest clickable ancestor. */
    private fun performClick(id: String): Boolean {
        var node = liveNode(id) ?: return false
        // Nodes resolved from a previous dump can be stale: performAction on a
        // detached node reports success but lands nowhere. Refresh in place,
        // and re-resolve by path if the refresh fails.
        var refreshed = false
        try { refreshed = node.refresh() } catch (_: Exception) {}
        if (!refreshed) {
            node = liveNode(id) ?: return false
        }
        var tries = 0
        while (tries++ < 8) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent ?: return false
        }
        return false
    }

    override fun type(text: String, id: String?): Map<String, Any?> {
        // 1) ACTION_SET_TEXT on the given node, or the focused editable node
        val node = if (id != null) {
            liveNode(id)
        } else {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
        if (node != null && node.isEditable) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                (node.text?.toString() ?: "") + text
            )
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return CommandRouter.ok(mapOf("what" to "type", "how" to "set_text"))
            }
        }
        // 2) key-by-key fallback
        val sent = keyByKey(text)
        return if (sent) CommandRouter.ok(mapOf("what" to "type", "how" to "keys"))
        else CommandRouter.err("no editable node and key fallback failed")
    }

    override fun key(code: Int): Map<String, Any?> {
        val ok = sendKey(code, 0)
        return if (ok) CommandRouter.ok(mapOf("what" to "key", "code" to code))
        else CommandRouter.err("key event failed for code $code")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Map<String, Any?> {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val done = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (done) CommandRouter.ok(
            mapOf("what" to "swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2)
        ) else CommandRouter.err("swipe failed")
    }

    override fun unlock(pin: String): Map<String, Any?> {
        val steps = ArrayList<String>()
        // 0) On a locked phone the PIN pad usually isn't up yet: the lock
        //    screen shows clock/notifications and needs a swipe-up first.
        //    Detect "no editable field and no digit keypad" and swipe.
        if (!pinEntryVisible()) {
            swipeUpFromBottom()
            steps.add("swipe_up")
            SystemClock.sleep(800)
        }
        // 1) SET_TEXT the PIN into the keyguard PIN field (focused editable node)
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        var typed = false
        if (node != null && node.isEditable) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin
            )
            typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (typed) steps.add("set_text")
        }
        // 2) fallback: key events, then tapping the on-screen keypad digits
        if (!typed) {
            if (keyByKey(pin)) {
                typed = true
                steps.add("keys")
            }
        }
        if (!typed) {
            var clicked = true
            for (c in pin) {
                val snap = snapshot(rootInActiveWindow) ?: break
                val digit = NodeTree.flatten(snap).firstOrNull {
                    (it.text ?: it.desc ?: "").trim() == c.toString()
                }
                if (digit != null) performClick(digit.id) else { clicked = false; break }
                SystemClock.sleep(150)
            }
            if (clicked) {
                typed = true
                steps.add("keypad")
            }
        }
        // 3) press enter (key event; fallback: click the focused node)
        var entered = sendKey(KeyEvent.KEYCODE_ENTER, 0)
        if (!entered) {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            entered = focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
        if (entered) steps.add("enter")
        return CommandRouter.ok(mapOf("what" to "unlock", "typed" to typed, "steps" to steps))
    }

    /**
     * Silent screenshot via the accessibility capability.
     *
     * This is the stealth path: unlike MediaProjection it shows NO consent
     * sheet and NO "screen is being shared" indicator in the status bar —
     * the phone looks completely idle while frames are taken. Requires
     * android:canTakeScreenshot="true" (Android 11+). The framework throttles
     * calls (~3/s), hence the "throttled" error the caller can retry.
     */
    override fun screenshot(format: String, quality: Int): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CommandRouter.err("accessibility screenshots need Android 11+")
        }
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        var error: String? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } catch (e: Exception) {
                        error = "decode: ${e.message}"
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    error = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                            "throttled: wait ~400ms and retry"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                            "accessibility access lost — re-enable the service"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
                        else -> "screenshot failed (code $errorCode)"
                    }
                    latch.countDown()
                }
            }
        )
        if (!latch.await(8, TimeUnit.SECONDS)) return CommandRouter.err("screenshot timeout")
        val b = bmp ?: return CommandRouter.err(error ?: "screenshot failed")
        return try {
            val png = format.equals("png", true)
            val out = ByteArrayOutputStream()
            b.compress(
                if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality.coerceIn(10, 100), out
            )
            CommandRouter.ok(
                mapOf(
                    "format" to if (png) "png" else "jpeg",
                    "width" to b.width,
                    "height" to b.height,
                    "bytes" to out.size(),
                    "b64" to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                )
            )
        } catch (e: Exception) {
            CommandRouter.err("encode: ${e.message}")
        } finally {
            b.recycle()
        }
    }

    override fun setArmed(on: Boolean): Map<String, Any?> {        armed = on
        if (!on) lastAutoTapKey = ""
        return CommandRouter.ok(mapOf("armed" to armed))
    }

    /** True when the keyguard PIN entry seems on screen (editable field or
     *  several single-digit buttons — the lock-screen keypad). */
    private fun pinEntryVisible(): Boolean {
        val snap = snapshot(rootInActiveWindow) ?: return false
        val nodes = NodeTree.flatten(snap)
        if (nodes.any { it.editable }) return true
        val digits = nodes.count {
            ((it.text ?: it.desc ?: "").trim().length == 1 &&
                (it.text ?: it.desc ?: "").trim()[0] in '0'..'9')
        }
        return digits >= 4
    }

    /** Swipe up from the bottom center (reveals PIN entry on lock screens). */
    private fun swipeUpFromBottom(): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val path = Path()
        path.moveTo(w / 2f, h * 0.85f)
        path.lineTo(w / 2f, h * 0.25f)
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun status(): Map<String, Any?> = CommandRouter.ok(
        mapOf(
            "armed" to armed,
            "link_port" to LocalLinkServer.PORT,
            "confirm_texts" to CONFIRM_TEXTS
        )
    )

    // ------------------------------------------------------------------ //
    // low-level input

    /** Tap/long-click at screen coordinates via dispatchGesture. */
    private fun dispatchGestureAt(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        // nudge slightly so the stroke is never zero-length on picky OEMs
        path.lineTo(x + 1f, y + 1f)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Type a string as key events; true if all characters were sent. */
    private fun keyByKey(text: String): Boolean {
        for (c in text) {
            val keyCode = charKeyCode(c) ?: return false
            val meta = if (c.isUpperCase() && c.isLetter()) KeyEvent.META_SHIFT_ON else 0
            if (!sendKey(keyCode, meta)) return false
            SystemClock.sleep(40)
        }
        return true
    }

    /**
     * Send one key event. Public API for key injection from a service does
     * not exist below API 33's (restricted) setScreenRecording hints, so we
     * use Instrumentation — works while our process can inject; silently
     * fails otherwise (callers have node-click fallbacks).
     */
    private fun sendKey(keyCode: Int, metaState: Int): Boolean = try {
        val inst = Instrumentation()
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inst.sendKeySync(
                KeyEvent(now, now, action, keyCode, 0, metaState)
            )
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun charKeyCode(c: Char): Int? = when (c) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
        in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        ' ' -> KeyEvent.KEYCODE_SPACE
        '\n' -> KeyEvent.KEYCODE_ENTER
        '.' -> KeyEvent.KEYCODE_PERIOD
        ',' -> KeyEvent.KEYCODE_COMMA
        '-' -> KeyEvent.KEYCODE_MINUS
        '=' -> KeyEvent.KEYCODE_EQUALS
        '/' -> KeyEvent.KEYCODE_SLASH
        '\\' -> KeyEvent.KEYCODE_BACKSLASH
        ';' -> KeyEvent.KEYCODE_SEMICOLON
        '\'' -> KeyEvent.KEYCODE_APOSTROPHE
        '`' -> KeyEvent.KEYCODE_GRAVE
        '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        '+' -> KeyEvent.KEYCODE_PLUS
        '@' -> KeyEvent.KEYCODE_AT
        '*' -> KeyEvent.KEYCODE_STAR
        '\t' -> KeyEvent.KEYCODE_TAB
        else -> null
    }
}
