package com.tg.ghosta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
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
 * Pure (JVM-tested) keyguard unlock decisions.
 *
 * Safety rules:
 *  - a PIN is only considered while KeyguardManager reports the device
 *    locked;
 *  - input is only allowed into an exact system/keyguard package whose class
 *    carries a keyguard marker or whose tree really looks like PIN entry;
 *  - an already-unlocked device is a success and never receives key input;
 *  - PIN shape is validated before any input, and the PIN is never included
 *    in a result or error.
 */
object UnlockPolicy {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 32

    /** Exact packages that may host the keyguard/bouncer input surface. */
    val KEYGUARD_PACKAGES: Set<String> = linkedSetOf(
        "com.android.systemui",
        "com.google.android.systemui",
        "com.android.keyguard",
        "android"
    )

    private val KEYGUARD_CLASS_MARKERS = listOf(
        "keyguard", "bouncer", "lockscreen", "pinview", "passwordview",
        "confirmdevicecredentials", "simunlock"
    )

    /** Exact affirmative labels for a keyguard submit/enter control. */
    private val SUBMIT_LABELS = setOf(
        "enter", "ok", "okay", "unlock", "confirm", "done", "submit", "→", "↵"
    )

    /** Resource-id markers that identify a submit/enter key. */
    private val SUBMIT_ID_MARKERS = listOf(
        "key_enter", "btn_enter", "pin_enter", "keyguard_enter",
        "key_ok", "btn_ok", "unlock_button", "confirm_button", "submit_button"
    )

    /** Reasonable pre-input shape check: printable ASCII, 4-32 chars. */
    fun validatePinShape(pin: String): String? {
        if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            return "unlock: PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH characters"
        }
        if (pin.any { it.code < 0x21 || it.code > 0x7e }) {
            return "unlock: PIN must be printable ASCII without spaces or control characters"
        }
        return null
    }

    fun hasEditableField(nodes: List<NodeData>): Boolean = nodes.any { it.editable }

    /** Single-character digit nodes, first occurrence per digit. */
    fun keypadDigits(nodes: List<NodeData>): Map<Char, NodeData> {
        val out = LinkedHashMap<Char, NodeData>()
        for (n in nodes) {
            val label = (n.text ?: n.desc)?.trim() ?: continue
            if (label.length != 1) continue
            val c = label[0]
            if (c in '0'..'9' && c !in out) out[c] = n
        }
        return out
    }

    /** True when the tree exposes a PIN/password field or a full keypad. */
    fun pinEntryVisible(nodes: List<NodeData>): Boolean =
        hasEditableField(nodes) || keypadDigits(nodes).size >= 4

    /**
     * Recognize the keyguard input surface. The keyguard must report locked,
     * the package must be an exact system package, and either the window
     * class carries a keyguard marker or the tree exposes PIN entry. Anything
     * else is refused so a PIN can never be typed into an arbitrary window.
     */
    fun isRecognizedKeyguardSurface(
        packageName: String?,
        className: String?,
        keyguardLocked: Boolean,
        nodes: List<NodeData>
    ): Boolean {
        if (!keyguardLocked) return false
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg !in KEYGUARD_PACKAGES) return false
        val cls = className?.trim()?.lowercase().orEmpty()
        val classMarked = KEYGUARD_CLASS_MARKERS.any { cls.contains(it) }
        val packageMarked = pkg.contains("keyguard")
        // A full PIN keypad is distinctive. A bare editable field is NOT
        // enough: systemui also hosts quick-settings search fields, and a PIN
        // must never be typed there.
        val keypadMarked = keypadDigits(nodes).size >= 4
        val pinFieldMarked = nodes.any { n ->
            if (!n.editable) return@any false
            val id = n.viewId?.lowercase().orEmpty()
            val fieldClass = n.className?.lowercase().orEmpty()
            id.contains("pin") || id.contains("password") || id.contains("bouncer") ||
                fieldClass.contains("pin") || fieldClass.contains("password")
        }
        return classMarked || packageMarked || keypadMarked || pinFieldMarked
    }

    /**
     * Find a keyguard submit control, preferring a recognized resource id,
     * then a clickable exact label, then any exact label.
     */
    fun submitControl(nodes: List<NodeData>): NodeData? {
        nodes.firstOrNull { n ->
            val id = n.viewId?.lowercase().orEmpty()
            id.isNotEmpty() && SUBMIT_ID_MARKERS.any { id.contains(it) }
        }?.let { return it }
        val labelled = nodes.filter { n ->
            listOfNotNull(n.text, n.desc)
                .map { it.trim().lowercase() }
                .any { it in SUBMIT_LABELS }
        }
        return labelled.firstOrNull { it.clickable } ?: labelled.firstOrNull()
    }

    /** Success result. Never contains the PIN. */
    fun unlockedResult(
        alreadyUnlocked: Boolean,
        steps: List<String> = emptyList()
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = true
        out["what"] = "unlock"
        out["unlocked"] = true
        if (alreadyUnlocked) out["already_unlocked"] = true
        out["typed"] = !alreadyUnlocked && steps.isNotEmpty()
        if (steps.isNotEmpty()) out["steps"] = steps
        return out
    }

    /**
     * Actionable failure, tagged with the stage that failed. The PIN is never
     * part of this map.
     */
    fun failure(
        stage: String,
        message: String,
        steps: List<String> = emptyList(),
        typed: Boolean = false
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = false
        out["error"] = message
        out["stage"] = stage
        out["typed"] = typed
        if (steps.isNotEmpty()) out["steps"] = steps
        return out
    }
}

/** Navigation keys that Android exposes as accessibility global actions. */
internal fun globalActionForKey(code: Int): Int? = when (code) {
    KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
    KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
    KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
    else -> null
}

/**
 * The sidekick accessibility service. Generic UI-driving verbs for the
 * operator, plus bounded auto-confirm of recognized system consent surfaces:
 * MediaProjection consent is confirmed only while ghostd's short-TTL
 * projection expectation is active (it arms it immediately before its own
 * capture request), while installer/permission dialogs are only confirmed when
 * explicitly armed. The operator still inspects the tree via `dump`/`find` and
 * decides what to tap; see [ConsentPolicy] for the safety rules.
 */
class GhostAccessibilityService : AccessibilityService(), UiDriver {

    companion object {
        @Volatile
        var instance: GhostAccessibilityService? = null
            private set

        /**
         * Affirmative labels the bounded consent policy may tap. Projection
         * consent ("Start now", "Share screen", whole-display selection) is
         * scoped to the projection consent activity class *and* requires the
         * short-TTL expectation ghostd arms immediately before its request;
         * installer/permission confirmation remains armed-only.
         */
        val CONFIRM_TEXTS: List<String> = ConsentPolicy.allConfirmTexts()

        /**
         * Exact system packages whose consent surfaces may be automated. A
         * package prefix alone is never enough: the window class must also
         * carry a MediaProjection/PackageInstaller/permission marker.
         */
        val SYSTEM_DIALOG_PKGS: List<String> = ConsentPolicy.SYSTEM_PACKAGES.toList()

        private const val ROOT_ID = "root"
        private const val TAP_MS = 60L
        private const val LONG_MS = 800L

        /** ghostd kick broadcast action (received by ghostd's KickReceiver). */
        const val KICK_ACTION = "com.tg.ghostd.KICK"
        private const val BABYSIT_CHECK_MS = 5 * 60_000L   // check every 5 min
        private const val BABYSIT_STALE_MS = 15 * 60_000L   // stale after 15 min

        // Keyguard unlock bounds. Keep the whole attempt comfortably inside
        // the sidekick link's 10 s read timeout: reveal 2.1 s + input ~1 s +
        // submit (control then Enter) 3.6 s worst case.
        private const val REVEAL_MAX_ATTEMPTS = 3
        private const val REVEAL_DELAY_MS = 700L
        private const val KEYPAD_DIGIT_DELAY_MS = 120L
        private const val KEYGUARD_SETTLE_MS = 250L
        private const val SUBMIT_WAIT_MS = 1_800L
        private const val UNLOCK_POLL_MS = 150L
    }

    private var link: LocalLinkServer? = null

    /** Serializes unlock attempts so two callers cannot interleave key input. */
    private val unlockLock = Any()

    private enum class PinEntry { TYPED, UNLOCKED, FAILED }
    private enum class SubmitOutcome { UNLOCKED, FAILED, NO_METHOD }
    private class KeyguardSurface(val nodes: List<NodeData>)

    @Volatile
    private var armed = false

    /**
     * Short-TTL "ghostd just asked for a capture" marker. MediaProjection
     * consent is only auto-confirmed while this is active; generic [armed]
     * never enables it (see [ProjectionExpectation]).
     */
    private val projectionExpectation = ProjectionExpectation()

    /** Bounded consent automation state (see [ConsentPolicy]). */
    private val consentHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val consentRetry = Runnable { attemptConsent() }
    private val consentTracker = ConsentAttemptTracker()

    /**
     * Decommission watcher. Armed only by an explicit prepare+confirmed
     * handshake bound to the link that confirmed; never by routine traffic.
     */
    private val decommissionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val decommissionSelfUninstall = Runnable { resolveDecommissionWatcher() }
    private fun resolveDecommissionWatcher() {
        when (Decommission.state.watcherOutcome(SystemClock.elapsedRealtime())) {
            DecommissionWatcherOutcome.UNINSTALL -> {
                val launched = Decommission.launchSelfUninstall(this)
                Decommission.state.selfUninstallResult(launched)
            }
            DecommissionWatcherOutcome.WAIT -> decommissionHandler.postDelayed(
                decommissionSelfUninstall, 500L
            )
            DecommissionWatcherOutcome.ABORT -> Unit
        }
    }

    /** Last window-state identity; content-changed events reuse it. */
    @Volatile
    private var lastWindowPackage: String? = null
    @Volatile
    private var lastWindowClassName: String? = null

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
        consentTracker.reset()
        projectionExpectation.clear()
        lastWindowPackage = null
        lastWindowClassName = null
        consentHandler.removeCallbacks(consentRetry)
        // A rebind starts a new link generation: any previous handshake is
        // stale and must never fire a self-uninstall.
        decommissionHandler.removeCallbacks(decommissionSelfUninstall)
        Decommission.state.reset()
        link = LocalLinkServer(this).also { it.start() }
        // Once the system binds us, the launcher icon has done its job.
        MainActivity.hideLauncherIcon(this)
        babysitHandler.removeCallbacks(babysitCheck)
        babysitHandler.postDelayed(babysitCheck, BABYSIT_CHECK_MS)
    }

    override fun onDestroy() {
        babysitHandler.removeCallbacks(babysitCheck)
        consentHandler.removeCallbacks(consentRetry)
        projectionExpectation.clear()
        decommissionHandler.removeCallbacks(decommissionSelfUninstall)
        Decommission.state.reset()
        link?.stop()
        link = null
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent): Boolean {
        // service about to be disabled — stop listening until rebind
        if (instance === this) instance = null
        consentHandler.removeCallbacks(consentRetry)
        projectionExpectation.clear()
        decommissionHandler.removeCallbacks(decommissionSelfUninstall)
        Decommission.state.reset()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // This is the only event carrying the activity/window class;
                // content-changed events reuse it while the window is stable.
                lastWindowPackage = event.packageName?.toString()
                lastWindowClassName = event.className?.toString()
                consentHandler.removeCallbacks(consentRetry)
                attemptConsent()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Background apps also post content events; only a change of
                // the *active* window invalidates the remembered class.
                val pkg = rootInActiveWindow?.packageName?.toString()
                if (pkg != null && pkg != lastWindowPackage) {
                    // Another window took over; never reuse a stale class.
                    lastWindowPackage = pkg
                    lastWindowClassName = null
                    consentHandler.removeCallbacks(consentRetry)
                }
                attemptConsent()
            }
        }
    }

    // ------------------------------------------------------------------ //
    // bounded consent auto-confirm
    //
    // MediaProjection consent is only confirmed while ghostd's short-TTL
    // projection expectation is active (it is sent immediately before its own
    // request); installer/permission remain armed-only and a plain arm never
    // enables projection. Every path is restricted to exact system packages
    // plus a consent class marker, skipped entirely while the keyguard is
    // locked, and capped per window.

    private fun attemptConsent() {
        val locked = keyguardLocked()
        if (locked) {
            consentTracker.reset()
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            consentTracker.reset()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val window = ConsentPolicy.windowFor(
            packageName = lastWindowPackage,
            className = lastWindowClassName,
            keyguardLocked = locked,
            armed = armed,
            projectionExpected = projectionExpectation.isActive(now),
            windowId = root.windowId
        )
        consentTracker.observe(window)
        if (window == null || !consentTracker.canAttempt()) return
        // Same package, different window (shade/keyguard) must not inherit a
        // consent class remembered from an earlier window.
        if (root.packageName?.toString() != window.packageName) return

        val snap = snapshot(root) ?: return
        val nodes = NodeTree.flatten(snap)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // A recognized system uninstall window is matched on the exact
        // window-state package/class (not merely the installer kind): only
        // there may the uninstall vocabulary be used, and only while the
        // decommission state permits that tap. Every other installer sheet
        // keeps the unchanged generic vocabulary.
        val uninstallWindow = DecommissionPolicy.isRecognizedUninstallWindow(
            lastWindowPackage, lastWindowClassName
        )
        if ((window.kind == ConsentKind.INSTALLER || uninstallWindow) &&
            Decommission.state.isPreparing(now)
        ) {
            // A prepared decommission is about to open the uninstall sheet.
            // Suppress installer auto-confirm until `confirmed` binds the
            // disconnect watcher; otherwise the dialog could be tapped before
            // ghosta knows when ghostd is actually removed.
            return
        }
        val target = DecommissionPolicy.pickWindowTarget(
            nodes,
            window.kind,
            lastWindowPackage,
            lastWindowClassName,
            Decommission.state.allowsUninstallTap(now),
            width,
            height
        ) ?: return
        if (!consentTracker.beginAttempt(target.key, now)) return

        val tapped = when (val t = target.target) {
            is TapTarget.Node -> {
                if (performClick(t.id)) {
                    true
                } else {
                    // Node path went stale between snapshot and click; use the
                    // snapshot bounds as a bounded coordinate fallback.
                    ConsentPolicy.centreInBounds(target.bounds, width, height)
                        ?.let { dispatchGestureAt(it.first, it.second, TAP_MS) } ?: false
                }
            }
            is TapTarget.Point -> dispatchGestureAt(t.x, t.y, TAP_MS)
        }
        if (tapped) {
            // Multi-step sheets ("Entire screen" -> "Share screen") need a
            // refreshed tree. Bounded by ConsentPolicy.MAX_ATTEMPTS.
            consentHandler.removeCallbacks(consentRetry)
            consentHandler.postDelayed(consentRetry, ConsentPolicy.RETRY_DELAY_MS)
        }
    }

    /**
     * Keyguard-locked devices suppress consent UI; never tap blind there.
     * Fails closed via [ConsentPolicy.keyguardLockedOrFailClosed]: an
     * unavailable KeyguardManager or a throwing query is treated as locked.
     */
    private fun keyguardLocked(): Boolean = ConsentPolicy.keyguardLockedOrFailClosed {
        val km = getSystemService(KeyguardManager::class.java) ?: return@keyguardLockedOrFailClosed null
        km.isKeyguardLocked || km.isDeviceLocked
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
        val global = globalActionForKey(code)
        val ok = if (global != null) performGlobalAction(global) else sendKey(code, 0)
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

    /**
     * Enter the keyguard PIN and prove the unlock.
     *
     * Order: already-unlocked success; bounded reveal loop (swipe only while
     * no field/keypad is exposed); recognized-keyguard surface check; input
     * via ACTION_SET_TEXT, keypad digits with a refreshed tree per digit, or
     * key events; submit via a recognized control or Enter; then wait for
     * KeyguardManager to report unlocked. Every failure is `ok:false` with a
     * stage and an action; the PIN never appears in the result.
     */
    override fun unlock(pin: String): Map<String, Any?> = synchronized(unlockLock) {
        UnlockPolicy.validatePinShape(pin)?.let {
            return@synchronized UnlockPolicy.failure("validation", it)
        }
        val km = getSystemService(KeyguardManager::class.java)
            ?: return@synchronized UnlockPolicy.failure(
                "keyguard_state",
                "keyguard state unavailable; refusing to enter a PIN without verification"
            )
        if (!isLocked(km)) {
            return@synchronized UnlockPolicy.unlockedResult(alreadyUnlocked = true)
        }

        val steps = ArrayList<String>()
        var surface = keyguardSurface(km)
        var reveals = 0
        // Reveal the PIN entry. Swipe only while no field/keypad is visible,
        // then refresh and retry a bounded number of times.
        while (surface == null && reveals < REVEAL_MAX_ATTEMPTS) {
            if (!isLocked(km)) {
                return@synchronized UnlockPolicy.unlockedResult(alreadyUnlocked = true)
            }
            reveals++
            swipeUpFromBottom()
            steps.add("swipe_up")
            SystemClock.sleep(REVEAL_DELAY_MS)
            surface = keyguardSurface(km)
        }
        if (surface == null) {
            // A reveal swipe can itself dismiss a non-secure keyguard.
            if (!isLocked(km)) {
                return@synchronized UnlockPolicy.unlockedResult(
                    alreadyUnlocked = true, steps = steps
                )
            }
            return@synchronized UnlockPolicy.failure(
                "reveal",
                "could not reveal the keyguard PIN/password entry after $reveals swipe " +
                    "attempts; the lock screen may use a different unlock method or block " +
                    "accessibility input. Unlock the phone manually, then retry",
                steps = steps
            )
        }

        val entry = enterPin(pin, km, steps)
        if (entry == PinEntry.UNLOCKED) {
            return@synchronized UnlockPolicy.unlockedResult(false, steps)
        }
        if (entry != PinEntry.TYPED) {
            return@synchronized UnlockPolicy.failure(
                "type",
                "could not enter the PIN on the keyguard: no editable field, rejected key " +
                    "events and no tappable digit keypad were available. Unlock the phone " +
                    "manually, then retry",
                steps = steps
            )
        }

        return@synchronized when (submitPin(km, steps)) {
            SubmitOutcome.UNLOCKED ->
                UnlockPolicy.unlockedResult(false, steps)
            SubmitOutcome.NO_METHOD ->
                UnlockPolicy.failure(
                    "submit",
                    "could not submit the PIN: neither Enter nor a recognized keyguard " +
                        "submit control responded. Unlock the phone manually, then retry",
                    steps = steps,
                    typed = true
                )
            SubmitOutcome.FAILED ->
                UnlockPolicy.failure(
                    "verify",
                    "unlock not confirmed: the keyguard is still locked after submitting " +
                        "the PIN. Check the PIN and that the lock screen accepts " +
                        "accessibility input, then retry",
                    steps = steps,
                    typed = true
                )
        }
    }

    /**
     * Enter [pin] on the recognized keyguard surface. Returns UNLOCKED when
     * the keyguard disappears mid-entry (for example auto-submit after the
     * final digit).
     */
    private fun enterPin(
        pin: String,
        km: KeyguardManager,
        steps: ArrayList<String>
    ): PinEntry {
        val surface = keyguardSurface(km) ?: return PinEntry.FAILED
        val editable = surface.nodes
            .filter { it.editable }
            .sortedByDescending { n ->
                val id = n.viewId?.lowercase().orEmpty()
                when {
                    id.contains("pin") || id.contains("password") -> 2
                    id.contains("edit") -> 1
                    else -> 0
                }
            }
            .firstOrNull()

        if (editable != null) {
            val node = liveNode(editable.id)
            if (node != null) {
                try {
                    node.refresh()
                } catch (_: Exception) {
                }
                if (node.isEditable) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin
                    )
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        steps.add("set_text")
                        SystemClock.sleep(KEYGUARD_SETTLE_MS)
                        return if (isLocked(km)) PinEntry.TYPED else PinEntry.UNLOCKED
                    }
                }
            }
            // A field exists but SET_TEXT was rejected: key events may still
            // reach the focused keyguard field.
            if (!isLocked(km)) return PinEntry.UNLOCKED
            try {
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
            }
            if (keyByKey(pin)) {
                steps.add("keys")
                SystemClock.sleep(KEYGUARD_SETTLE_MS)
                return if (isLocked(km)) PinEntry.TYPED else PinEntry.UNLOCKED
            }
        }

        // No useable editable field: prefer the keypad, re-reading the tree
        // before every digit so stale node paths cannot type into the wrong
        // place. Blind key events remain the last resort.
        val keypadSurface = keyguardSurface(km)
        if (keypadSurface != null && UnlockPolicy.keypadDigits(keypadSurface.nodes).isNotEmpty()) {
            for (c in pin) {
                if (!isLocked(km)) return PinEntry.UNLOCKED
                val fresh = keyguardSurface(km) ?: return PinEntry.FAILED
                val digit = UnlockPolicy.keypadDigits(fresh.nodes)[c] ?: return PinEntry.FAILED
                if (!performClick(digit.id)) return PinEntry.FAILED
                SystemClock.sleep(KEYPAD_DIGIT_DELAY_MS)
            }
            steps.add("keypad")
            SystemClock.sleep(KEYGUARD_SETTLE_MS)
            return if (isLocked(km)) PinEntry.TYPED else PinEntry.UNLOCKED
        }

        if (!isLocked(km)) return PinEntry.UNLOCKED
        if (keyByKey(pin)) {
            steps.add("keys")
            SystemClock.sleep(KEYGUARD_SETTLE_MS)
            return if (isLocked(km)) PinEntry.TYPED else PinEntry.UNLOCKED
        }
        return PinEntry.FAILED
    }

    /**
     * Submit the entered PIN through a recognized keyguard control, then
     * Enter, and wait for the keyguard to actually disappear.
     */
    private fun submitPin(km: KeyguardManager, steps: ArrayList<String>): SubmitOutcome {
        if (!isLocked(km)) return SubmitOutcome.UNLOCKED
        var attempted = false
        val control = keyguardSurface(km)?.let { UnlockPolicy.submitControl(it.nodes) }
        if (control != null && performClick(control.id)) {
            steps.add("submit_control")
            attempted = true
            if (awaitUnlocked(km, SUBMIT_WAIT_MS)) return SubmitOutcome.UNLOCKED
        }
        if (isLocked(km) && sendKey(KeyEvent.KEYCODE_ENTER, 0)) {
            steps.add("enter")
            attempted = true
            if (awaitUnlocked(km, SUBMIT_WAIT_MS)) return SubmitOutcome.UNLOCKED
        }
        return if (attempted) SubmitOutcome.FAILED else SubmitOutcome.NO_METHOD
    }

    private fun isLocked(km: KeyguardManager): Boolean = try {
        km.isKeyguardLocked || km.isDeviceLocked
    } catch (_: Exception) {
        true
    }

    private fun awaitUnlocked(km: KeyguardManager, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (!isLocked(km)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            SystemClock.sleep(UNLOCK_POLL_MS)
        }
    }

    /**
     * Snapshot the active window only when the keyguard is locked and the
     * window is a recognized system/keyguard surface with PIN entry.
     */
    private fun keyguardSurface(km: KeyguardManager): KeyguardSurface? {
        if (!isLocked(km)) return null
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        val cls = if (pkg == lastWindowPackage) lastWindowClassName else null
        val snap = snapshot(root) ?: return null
        val nodes = NodeTree.flatten(snap)
        if (!UnlockPolicy.isRecognizedKeyguardSurface(pkg, cls, true, nodes)) return null
        // A recognized keyguard window that is still only clock/notifications
        // must keep the reveal loop going; only a real field/keypad is input.
        if (!UnlockPolicy.pinEntryVisible(nodes)) return null
        return KeyguardSurface(nodes)
    }

    private class Shot(
        val bitmap: Bitmap?,
        val error: String?
    )

    /**
     * One accessibility screenshot attempt.
     *
     * The HardwareBuffer is closed in `finally` on every path, and a bitmap
     * that arrives after the caller already timed out is recycled instead of
     * leaking until GC.
     */
    private fun captureShotOnce(timeoutMs: Long): Shot {
        val latch = CountDownLatch(1)
        val lock = Any()
        var timedOut = false
        var bmp: Bitmap? = null
        var error: String? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    var wrapped: Bitmap? = null
                    var decoded: Bitmap? = null
                    try {
                        wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        decoded = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        error = "decode: ${e.message}"
                    } finally {
                        if (wrapped != null && wrapped !== decoded) {
                            try {
                                wrapped.recycle()
                            } catch (_: Exception) {
                            }
                        }
                        // Always close, including decode failures.
                        try {
                            hb.close()
                        } catch (_: Exception) {
                        }
                    }
                    synchronized(lock) {
                        if (timedOut) decoded?.recycle() else bmp = decoded
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    synchronized(lock) {
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
            }
        )
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized(lock) {
                timedOut = true
                bmp?.recycle()
                bmp = null
            }
            return Shot(null, "screenshot timeout")
        }
        return Shot(bmp, error)
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
        val shot = captureShotOnce(8_000)
        val b = shot.bitmap ?: return CommandRouter.err(shot.error ?: "screenshot failed")
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

    // ------------------------------------------------------------------ //
    // decommission handshake (owner-authorized ghostd removal)

    /**
     * prepare / confirmed / cancel / status. The confirmed step only records
     * intent to watch; the actual self-uninstall starts after the
     * authenticated link that carried it closes AND no reconnection arrives
     * within the grace period (see [DecommissionState]).
     */
    override fun decommission(
        action: String?,
        targetPackage: String?,
        session: String?,
        connectionId: Int
    ): Map<String, Any?> {
        val res = Decommission.handle(
            action = action,
            targetPackage = targetPackage,
            session = session,
            connectionId = connectionId,
            armed = armed,
            // Self-uninstall runs from the background after ghostd is gone;
            // without this owner-granted capability prepare must fail.
            overlayGranted = Decommission.canDrawOverlays(this),
            nowMs = SystemClock.elapsedRealtime()
        )
        if (res["ok"] == true && DecommissionPolicy.parseAction(action) == "confirmed") {
            // The system uninstall sheet may already be up; handle it without
            // waiting for the next window event. Normal consent bounds still
            // apply (armed-only, recognized PackageInstaller class).
            consentHandler.post { attemptConsent() }
        }
        return res
    }

    override fun connectionOpened(connectionId: Int) {
        if (Decommission.state.onConnectionOpened(SystemClock.elapsedRealtime())) {
            // ghostd survived: remove a pending self-uninstall.
            decommissionHandler.removeCallbacks(decommissionSelfUninstall)
        }
    }

    override fun connectionClosed(connectionId: Int) {
        if (!Decommission.state.onConnectionClosed(
                connectionId, SystemClock.elapsedRealtime()
            )
        ) {
            return
        }
        decommissionHandler.removeCallbacks(decommissionSelfUninstall)
        decommissionHandler.postDelayed(
            decommissionSelfUninstall, Decommission.state.graceMs
        )
    }

    /**
     * Arm the short-TTL projection expectation for ghostd's own upcoming
     * capture consent. Does NOT arm installer/permission confirmation.
     */
    override fun expectProjection(ttlMs: Long?): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        val expiresAt = projectionExpectation.expect(now, ttlMs)
        // Link threads call this; consent tracking is main-thread only. Run an
        // immediate attempt so a sheet that is already up is handled.
        consentHandler.post { attemptConsent() }
        return CommandRouter.ok(
            mapOf(
                "expect" to "projection",
                "ttl_ms" to (expiresAt - now),
                "armed" to armed
            )
        )
    }

    override fun setArmed(on: Boolean): Map<String, Any?> {
        armed = on
        // Link threads call this; tracker/handler state is main-thread only.
        if (on) {
            // Confirm an already-open installer/permission sheet promptly.
            consentHandler.post { attemptConsent() }
        } else {
            // Disarm stops installer/permission confirmation; an in-flight
            // MediaProjection flow (with its own short-TTL expectation) may
            // finish its bounded retry.
            consentHandler.post {
                if (consentTracker.window?.kind != ConsentKind.MEDIA_PROJECTION) {
                    consentHandler.removeCallbacks(consentRetry)
                    consentTracker.reset()
                }
            }
        }
        return CommandRouter.ok(mapOf("armed" to armed))
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
            // Remaining lifetime of ghostd's projection expectation, 0 if none.
            "projection_expected_ms" to
                projectionExpectation.remainingMs(SystemClock.elapsedRealtime()),
            "link_port" to LocalLinkServer.PORT,
            "confirm_texts" to CONFIRM_TEXTS,
            // Owner-setup capability for the self-uninstall activity start.
            "self_uninstall_capable" to Decommission.canDrawOverlays(this),
            "decommission" to Decommission.state.snapshot(SystemClock.elapsedRealtime())
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
