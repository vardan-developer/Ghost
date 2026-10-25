package com.tg.ghosta

/** Recognized system consent surfaces. */
enum class ConsentKind { MEDIA_PROJECTION, INSTALLER, PERMISSION }

/**
 * A consent window recognized from an accessibility window-state event.
 * [windowId] keeps reopened sheets (same package/class) distinct from the
 * sheet that was already handled.
 */
data class ConsentWindow(
    val kind: ConsentKind,
    val packageName: String,
    val className: String,
    val windowId: Int
) {
    val key: String get() = "$windowId|$packageName|$className"
}

/** What a consent step should be driven through. */
sealed class TapTarget {
    /** Click (or ancestor-click) an accessibility node by id path. */
    data class Node(val id: String) : TapTarget()

    /** Bounded coordinate tap for a non-clickable label with no clickable ancestor. */
    data class Point(val x: Int, val y: Int) : TapTarget()
}

/** A single chosen consent step: target plus the label that matched it. */
data class ConsentTarget(
    val target: TapTarget,
    val label: String,
    val bounds: String?
) {
    val key: String get() = when (target) {
        is TapTarget.Node -> "node:${target.id}"
        is TapTarget.Point -> "point:${target.x},${target.y}"
    }
}

/**
 * Pure (JVM-testable) decision logic for bounded consent automation.
 *
 * Safety rules, in order:
 *  - only exact recognized system packages;
 *  - the window class must carry a consent marker (MediaProjection /
 *    PackageInstaller / permission) — a package prefix alone is never enough;
 *  - nothing at all runs while the keyguard is locked (and an unknown keyguard
 *    state counts as locked);
 *  - MediaProjection consent requires an active, short-TTL projection
 *    expectation armed by ghostd immediately before its own capture request
 *    (see [ProjectionExpectation]) — generic `arm` never enables it, and it is
 *    not always-on, so another app's capture sheet is ignored;
 *  - installer/permission confirmation stays armed-only;
 *  - target selection prefers clickable agreements, then a text/title node's
 *    nearest clickable ancestor, then a bounded coordinate tap.
 */
object ConsentPolicy {

    /** Hard cap per consent window, including multi-step sheets. */
    const val MAX_ATTEMPTS = 3

    /** Do not tap twice faster than this, even for different targets. */
    const val MIN_TAP_INTERVAL_MS = 600L

    /** Suppress duplicate taps of the same target within one window. */
    const val SAME_TARGET_SUPPRESS_MS = 700L

    /** Delay before re-reading the tree after a multi-step consent tap. */
    const val RETRY_DELAY_MS = 900L

    /** Exact recognized system packages (no prefixes, no arbitrary apps). */
    val SYSTEM_PACKAGES: Set<String> = linkedSetOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.systemui",
        "android"
    )

    private val PROJECTION_CLASS_MARKERS = listOf("mediaprojection")
    private val INSTALLER_CLASS_MARKERS = listOf("packageinstaller", "installconfirm")
    private val PERMISSION_CLASS_MARKERS = listOf("grantpermissions", "permission")

    /**
     * Whole-display flow: optional full-screen/default selection followed by an
     * affirmative action. Ordered by preference; exact matches only.
     */
    private val PROJECTION_ACTIONS = listOf(
        "start now",
        "start recording",
        "start recording or casting",
        "share screen",
        "share entire screen",
        "entire screen",
        "full screen",
        "fullscreen",
        "whole screen",
        "share",
        "start",
        "accept",
        "next",
        "continue",
        "allow"
    )

    private val INSTALLER_ACTIONS = listOf(
        "install",
        "install anyway",
        "update",
        "continue",
        "ok",
        "okay",
        "yes",
        "allow"
    )

    private val PERMISSION_ACTIONS = listOf(
        "while using the app",
        "only this time",
        "allow all the time",
        "allow",
        "ok",
        "okay",
        "continue",
        "got it",
        "yes"
    )

    fun actionsFor(kind: ConsentKind): List<String> = when (kind) {
        ConsentKind.MEDIA_PROJECTION -> PROJECTION_ACTIONS
        ConsentKind.INSTALLER -> INSTALLER_ACTIONS
        ConsentKind.PERMISSION -> PERMISSION_ACTIONS
    }

    /** All affirmative labels, for `status` reporting. */
    fun allConfirmTexts(): List<String> {
        val out = ArrayList<String>()
        for (kind in ConsentKind.values()) {
            for (text in actionsFor(kind)) if (text !in out) out.add(text)
        }
        return out
    }

    /**
     * Evaluate a keyguard query fail-closed: a null result (no KeyguardManager)
     * or a throwing query counts as locked. Guessing "unlocked" could tap a
     * consent surface that automation cannot actually see.
     */
    fun keyguardLockedOrFailClosed(query: () -> Boolean?): Boolean =
        try {
            query() ?: true
        } catch (_: Exception) {
            true
        }

    /**
     * Recognize the active consent window, or null when automation must not
     * run. [className] must be the window/activity class from a
     * TYPE_WINDOW_STATE_CHANGED event, not a package name.
     *
     * [projectionExpected] must be true only for the short TTL after ghostd
     * asked for a capture; it never comes from generic [armed] and it does not
     * apply to any other consent kind.
     */
    fun windowFor(
        packageName: String?,
        className: String?,
        keyguardLocked: Boolean,
        armed: Boolean,
        projectionExpected: Boolean = false,
        windowId: Int = -1
    ): ConsentWindow? {
        if (keyguardLocked) return null
        val pkg = packageName?.trim().orEmpty()
        if (pkg !in SYSTEM_PACKAGES) return null
        val cls = className?.trim().orEmpty()
        if (cls.isEmpty()) return null
        val lower = cls.lowercase()
        val kind = when {
            PROJECTION_CLASS_MARKERS.any { lower.contains(it) } ->
                ConsentKind.MEDIA_PROJECTION
            INSTALLER_CLASS_MARKERS.any { lower.contains(it) } ->
                ConsentKind.INSTALLER
            PERMISSION_CLASS_MARKERS.any { lower.contains(it) } ->
                ConsentKind.PERMISSION
            else -> return null
        }
        // Projection consent is requested per capture: only the short-TTL
        // expectation ghostd arms immediately before its own request may
        // confirm it. Generic arming must never make an arbitrary app's
        // capture sheet tappable. Every other surface stays armed-only.
        if (kind == ConsentKind.MEDIA_PROJECTION) {
            if (!projectionExpected) return null
        } else if (!armed) {
            return null
        }
        return ConsentWindow(kind, pkg, cls, windowId)
    }

    /**
     * Pick the next affirmative target for [kind].
     *
     * [screenWidth]/[screenHeight] bound the coordinate fallback so a bogus
     * bounds string cannot tap outside the display.
     */
    fun pickTarget(
        nodes: List<NodeData>,
        kind: ConsentKind,
        screenWidth: Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE
    ): ConsentTarget? {
        val actions = actionsFor(kind)
        val byId = nodes.associateBy { it.id }
        val matched = nodes
            .map { it to rank(it, actions) }
            .filter { it.second != Int.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
        if (matched.isEmpty()) return null

        // 1) clickable agreements first (vocabulary order, stable).
        matched.firstOrNull { it.clickable }?.let {
            return ConsentTarget(TapTarget.Node(it.id), labelOf(it), it.bounds)
        }

        // 2) text/title labels: click their nearest clickable ancestor.
        for (node in matched) {
            val ancestor = nearestClickableAncestor(node.id, byId) ?: continue
            return ConsentTarget(TapTarget.Node(ancestor.id), labelOf(node), ancestor.bounds)
        }

        // 3) last resort: bounded coordinate tap of the label itself.
        for (node in matched) {
            val centre = centreInBounds(node.bounds, screenWidth, screenHeight) ?: continue
            return ConsentTarget(
                TapTarget.Point(centre.first, centre.second),
                labelOf(node),
                node.bounds
            )
        }
        return null
    }

    /** Centre of a non-empty bounds string, or null when empty/off-screen. */
    fun centreInBounds(
        bounds: String?,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int>? {
        val parts = bounds?.split(',') ?: return null
        if (parts.size < 4) return null
        val left = parts[0].trim().toIntOrNull() ?: return null
        val top = parts[1].trim().toIntOrNull() ?: return null
        val right = parts[2].trim().toIntOrNull() ?: return null
        val bottom = parts[3].trim().toIntOrNull() ?: return null
        if (right <= left || bottom <= top) return null
        if (left < 0 || top < 0) return null
        if (screenWidth != Int.MAX_VALUE && right > screenWidth) return null
        if (screenHeight != Int.MAX_VALUE && bottom > screenHeight) return null
        return Pair((left + right) / 2, (top + bottom) / 2)
    }

    private fun labels(node: NodeData): List<String> =
        listOfNotNull(node.text, node.desc)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Lower is better; Int.MAX_VALUE means no affirmative label matched. */
    private fun rank(node: NodeData, actions: List<String>): Int {
        var best = Int.MAX_VALUE
        for (label in labels(node)) {
            val index = actions.indexOf(label)
            if (index in 0 until best) best = index
        }
        return best
    }

    private fun labelOf(node: NodeData): String =
        node.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.desc?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""

    private fun nearestClickableAncestor(
        id: String,
        byId: Map<String, NodeData>
    ): NodeData? {
        var path = id
        while (true) {
            val cut = path.lastIndexOf('/')
            if (cut <= 0) return null
            path = path.substring(0, cut)
            val ancestor = byId[path] ?: return null
            if (ancestor.clickable) return ancestor
        }
    }
}

/**
 * Short-TTL "ghostd is about to ask for MediaProjection consent" marker.
 *
 * ghostd sends `{"cmd":"arm","expect":"projection","ttl_ms":15000}` immediately
 * before it starts its own consent flow. Only while this expectation is active
 * may [ConsentPolicy] confirm a recognized MediaProjection permission window.
 * That scopes automation to the request ghostd just made:
 *
 *  - a plain `{"cmd":"arm"}` (installer/permission) never creates it;
 *  - another app's capture sheet is ignored because no expectation is active;
 *  - the expectation lapses on its own, so nothing stays armed indefinitely.
 *
 * Time is supplied by the caller ([android.os.SystemClock.elapsedRealtime] in
 * the service) so expiry is JVM-testable.
 */
class ProjectionExpectation(
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val maxTtlMs: Long = MAX_TTL_MS
) {
    companion object {
        /** Default lifetime when ghostd omits or misstates `ttl_ms`. */
        const val DEFAULT_TTL_MS = 15_000L

        /** Upper bound for a requested TTL, so a bad value cannot pin it on. */
        const val MAX_TTL_MS = 60_000L
    }

    @Volatile
    private var expiresAtMs = 0L

    /**
     * Arm the expectation for [ttlMs] from [nowMs]. A missing, zero or negative
     * TTL falls back to [defaultTtlMs]; larger values are clamped to
     * [maxTtlMs]. Returns the absolute expiry time.
     */
    @Synchronized
    fun expect(nowMs: Long, ttlMs: Long?): Long {
        val requested = ttlMs ?: 0L
        val ttl = (if (requested <= 0L) defaultTtlMs else requested)
            .coerceAtMost(maxTtlMs)
        expiresAtMs = nowMs + ttl
        return expiresAtMs
    }

    /** True only strictly before the TTL expires. */
    fun isActive(nowMs: Long): Boolean = nowMs < expiresAtMs

    /** Milliseconds left, or 0 when inactive. */
    fun remainingMs(nowMs: Long): Long =
        if (isActive(nowMs)) expiresAtMs - nowMs else 0L

    /** Drop the expectation (service restart/unbind, explicit reset). */
    @Synchronized
    fun clear() {
        expiresAtMs = 0L
    }
}

/**
 * Per-window attempt bookkeeping. A different window id resets the counter, so
 * a reopened sheet is never swallowed by the previous sheet's suppression.
 */
class ConsentAttemptTracker(
    private val maxAttempts: Int = ConsentPolicy.MAX_ATTEMPTS
) {
    var window: ConsentWindow? = null
        private set
    var attempts: Int = 0
        private set

    private var lastTargetKey: String? = null
    private var lastTargetAtMs: Long = 0L
    private var lastTapAtMs: Long = 0L

    /**
     * Observe the current window. Null (or a different window key) resets all
     * per-window state. Returns true when a real window change reset attempts.
     */
    fun observe(window: ConsentWindow?): Boolean {
        if (window == null) {
            reset()
            return false
        }
        if (this.window?.key != window.key) {
            this.window = window
            attempts = 0
            lastTargetKey = null
            lastTargetAtMs = 0L
            lastTapAtMs = 0L
            return true
        }
        return false
    }

    fun canAttempt(): Boolean = attempts < maxAttempts

    /** Reserve one attempt. False when capped or when the same target repeats. */
    fun beginAttempt(targetKey: String, nowMs: Long): Boolean {
        if (!canAttempt()) return false
        if (lastTapAtMs != 0L && nowMs - lastTapAtMs < ConsentPolicy.MIN_TAP_INTERVAL_MS) {
            return false
        }
        if (targetKey == lastTargetKey && lastTargetAtMs != 0L &&
            nowMs - lastTargetAtMs < ConsentPolicy.SAME_TARGET_SUPPRESS_MS
        ) {
            return false
        }
        attempts++
        lastTargetKey = targetKey
        lastTargetAtMs = nowMs
        lastTapAtMs = nowMs
        return true
    }

    fun reset() {
        window = null
        attempts = 0
        lastTargetKey = null
        lastTargetAtMs = 0L
        lastTapAtMs = 0L
    }
}
