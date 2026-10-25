package com.tg.ghosta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.app.Instrumentation

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
            "continue",
            "while using the app"
        )

        private const val ROOT_ID = "root"
        private const val TAP_MS = 60L
        private const val LONG_MS = 800L
    }

    private var link: LocalLinkServer? = null

    @Volatile
    private var armed = false
    private var lastAutoTapAt = 0L
    private var lastAutoTapKey = ""

    // ------------------------------------------------------------------ //
    // lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        link = LocalLinkServer(this).also { it.start() }
        // Once the system binds us, the launcher icon has done its job.
        MainActivity.hideLauncherIcon(this)
    }

    override fun onDestroy() {
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
        val snap = snapshot(rootInActiveWindow ?: return) ?: return
        val hit = NodeTree.flatten(snap).firstOrNull { n ->
            n.clickable && confirmText(n)
        } ?: return
        // debounce the same button/key
        if (hit.id == lastAutoTapKey && now - lastAutoTapAt < 5000) return
        lastAutoTapAt = now
        lastAutoTapKey = hit.id
        performClick(hit.id)
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

    override fun setArmed(on: Boolean): Map<String, Any?> {
        armed = on
        if (!on) lastAutoTapKey = ""
        return CommandRouter.ok(mapOf("armed" to armed))
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
