package com.tg.ghosta

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Owner-authorized decommission handshake (ghostd `destroy`).
 *
 * ghostd cannot remove ghosta by itself without losing the accessibility
 * service that has to confirm ghostd's own system uninstall sheet. The two
 * apps therefore coordinate over the local link:
 *
 *   ghostd → {"cmd":"arm"}                             (armed consent policy)
 *   ghostd → {"cmd":"decommission","action":"prepare", ...}
 *   ghostd → {"cmd":"decommission","action":"confirmed", ...}
 *   ghostd starts its own system uninstall sheet
 *   ghosta confirms only a recognized PackageInstaller uninstall window
 *   ghostd is removed → this authenticated link closes
 *   ghosta waits a short grace period for a reconnection; if none arrives it
 *   starts its own system uninstall sheet and confirms it the same way.
 *
 * Safety rules:
 *  - `prepare` requires the owner-granted SYSTEM_ALERT_WINDOW capability:
 *    without it a background activity start (this app's own uninstall sheet)
 *    is blocked, so the handshake fails with
 *    [DecommissionPolicy.SELF_UNINSTALL_BLOCKED] BEFORE ghostd uninstalls;
 *  - a blank/missing session on `cancel` is an unconditional reset that
 *    ghostd sends before every new `prepare`, so retries are not wedged by a
 *    stale PREPARED/CONFIRMED flow (it is an explicit verb, never a
 *    disconnect signal);
 *  - a watcher is only armed by an explicit `confirmed` for the exact ghostd
 *    package and the same nonce that `prepare` accepted;
 *  - the watcher is bound to the authenticated connection that carried
 *    `confirmed`, so routine disconnects and prepared-only requests never
 *    cause a self-uninstall;
 *  - any new authenticated connection during the confirmation or grace
 *    period means ghostd survived (sheet cancelled / link retried): the
 *    decommission is cancelled;
 *  - the whole flow expires, so a stale preparation cannot fire later;
 *  - the uninstall labels are only considered while this decommission is
 *    active and the sidekick is armed (see [DecommissionPolicy]).
 *
 * This deliberately uses [Intent.ACTION_DELETE] (the system uninstall
 * confirmation UI). PackageInstaller.uninstall() needs
 * REQUEST_DELETE_PACKAGES, which is not declared, and ACTION_DELETE provides
 * no install-session status callback — the authenticated link disconnect is
 * the removal signal. Silent/root uninstall is not claimed.
 */
object DecommissionPolicy {

    /** The main app this handshake is allowed to decommission. */
    const val GHOSTD_PACKAGE = "com.tg.ghostd"

    /** The sidekick package (this app). */
    const val GHOSTA_PACKAGE = "com.tg.ghosta"

    /**
     * Distinct, machine-readable failure: this app cannot start an activity
     * from the background, so it could never open/confirm its own system
     * uninstall sheet. Reported by `prepare`, i.e. before ghostd uninstalls;
     * ghostd surfaces it as its own failure stage.
     */
    const val SELF_UNINSTALL_BLOCKED = "self_uninstall_blocked"

    /** Actionable message for [SELF_UNINSTALL_BLOCKED]. */
    fun selfUninstallBlockedMessage(): String =
        "decommission: $SELF_UNINSTALL_BLOCKED — ghosta cannot open its own system " +
            "uninstall sheet because Android blocks background activity starts. " +
            "Grant \"Display over other apps\" (SYSTEM_ALERT_WINDOW) for ghosta " +
            "during owner setup (Settings → Apps → ghosta → Display over other apps), " +
            "then retry destroy. Nothing was uninstalled"

    /**
     * Affirmative labels of a system uninstall confirmation. Ordered by
     * preference; "Cancel" is never in this list.
     */
    val UNINSTALL_ACTIONS: List<String> = listOf(
        "uninstall", "uninstall app", "uninstall anyway", "remove", "remove app",
        "ok", "okay", "yes", "continue"
    )

    /**
     * A PackageInstaller uninstall window class carries one of these markers.
     * The exact package allow-list above is still required, so this cannot
     * match an arbitrary app or a keyguard/shade window.
     */
    private val UNINSTALL_CLASS_MARKERS = listOf("uninstall")

    /** Normalize a `decommission` action; null means unsupported. */
    fun parseAction(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "prepare" -> "prepare"
        "confirmed", "confirm" -> "confirmed"
        "cancel", "abort" -> "cancel"
        "status" -> "status"
        else -> null
    }

    fun isExpectedGhostd(targetPackage: String?): Boolean =
        targetPackage?.trim() == GHOSTD_PACKAGE

    /** Exact system package + an uninstall class marker. */
    fun isRecognizedUninstallWindow(packageName: String?, className: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg !in ConsentPolicy.SYSTEM_PACKAGES) return false
        val cls = className?.trim()?.lowercase().orEmpty()
        if (cls.isEmpty()) return false
        return UNINSTALL_CLASS_MARKERS.any { cls.contains(it) }
    }

    /**
     * Pick the affirmative target for the active window.
     *
     * A recognized system uninstall sheet ONLY gets the uninstall vocabulary,
     * and only while the decommission state permits that tap; otherwise no
     * target is returned, so a generic installer "OK" is never tapped on an
     * uninstall sheet. Every other window keeps the unchanged
     * [ConsentPolicy.pickTarget] behavior for its kind.
     */
    fun pickWindowTarget(
        nodes: List<NodeData>,
        kind: ConsentKind,
        packageName: String?,
        className: String?,
        uninstallTapAllowed: Boolean,
        screenWidth: Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE
    ): ConsentTarget? {
        if (isRecognizedUninstallWindow(packageName, className)) {
            return if (uninstallTapAllowed) {
                pickUninstallTarget(nodes, screenWidth, screenHeight)
            } else {
                null
            }
        }
        return ConsentPolicy.pickTarget(nodes, kind, screenWidth, screenHeight)
    }

    /**
     * Pick the affirmative control of a recognized uninstall sheet.
     * Same bounded strategy as [ConsentPolicy.pickTarget]: clickable match,
     * then the nearest clickable ancestor of a label, then a centre tap that
     * must lie inside the display.
     */
    fun pickUninstallTarget(
        nodes: List<NodeData>,
        screenWidth: Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE
    ): ConsentTarget? {
        val byId = nodes.associateBy { it.id }
        val matched = nodes
            .map { it to rank(it) }
            .filter { it.second != Int.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
        if (matched.isEmpty()) return null

        matched.firstOrNull { it.clickable }?.let {
            return ConsentTarget(TapTarget.Node(it.id), labelOf(it), it.bounds)
        }
        for (node in matched) {
            val ancestor = nearestClickableAncestor(node.id, byId) ?: continue
            return ConsentTarget(TapTarget.Node(ancestor.id), labelOf(node), ancestor.bounds)
        }
        for (node in matched) {
            val centre = ConsentPolicy.centreInBounds(node.bounds, screenWidth, screenHeight)
                ?: continue
            return ConsentTarget(
                TapTarget.Point(centre.first, centre.second), labelOf(node), node.bounds
            )
        }
        return null
    }

    private fun labels(node: NodeData): List<String> =
        listOfNotNull(node.text, node.desc)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Lower is better; Int.MAX_VALUE means no uninstall label matched. */
    private fun rank(node: NodeData): Int {
        var best = Int.MAX_VALUE
        for (label in labels(node)) {
            val index = UNINSTALL_ACTIONS.indexOf(label)
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

/** Outcome of the post-disconnect watcher. */
enum class DecommissionWatcherOutcome { WAIT, UNINSTALL, ABORT }

/**
 * Pure (JVM-tested) state machine for the decommission handshake.
 *
 * Time is supplied by the caller so every transition is testable; the
 * service passes [android.os.SystemClock.elapsedRealtime].
 */
class DecommissionState(
    val ttlMs: Long = DEFAULT_TTL_MS,
    val graceMs: Long = DEFAULT_GRACE_MS
) {
    enum class Stage { IDLE, PREPARED, CONFIRMED, DISCONNECTED, SELF_UNINSTALLING }

    var stage: Stage = Stage.IDLE
        private set
    var session: String? = null
        private set
    var targetPackage: String? = null
        private set

    /** Authenticated link that carried `confirmed`; the only one watched. */
    var connectionId: Int = -1
        private set

    private var preparedAtMs = 0L
    private var confirmedAtMs = 0L
    private var disconnectedAtMs = 0L
    private var selfUninstallAtMs = 0L

    /**
     * Accept a preparation. Returns null on success, otherwise an actionable
     * message. Armed is required because uninstall confirmation uses the
     * existing armed-only PackageInstaller consent policy.
     */
    @Synchronized
    fun prepare(
        connectionId: Int,
        targetPackage: String?,
        session: String?,
        armed: Boolean,
        nowMs: Long
    ): String? {
        if (expireIfNeeded(nowMs)) {
            return "decommission: previous request expired; retry destroy"
        }
        if (!armed) {
            return "decommission: sidekick is not armed; refusing to automate the " +
                "system uninstall sheet"
        }
        if (!DecommissionPolicy.isExpectedGhostd(targetPackage)) {
            return "decommission: expected package ${DecommissionPolicy.GHOSTD_PACKAGE}"
        }
        val s = session?.trim().orEmpty()
        if (s.isEmpty()) return "decommission: missing session id"
        if (connectionId < 0) return "decommission: no authenticated connection"
        if (stage != Stage.IDLE) {
            return "decommission: already ${stage.name.lowercase()} for $session"
        }
        stage = Stage.PREPARED
        this.session = s
        this.targetPackage = DecommissionPolicy.GHOSTD_PACKAGE
        this.connectionId = connectionId
        preparedAtMs = nowMs
        return null
    }

    /**
     * Accept the confirmation and bind the watcher to [connectionId] (the
     * link that must disappear when ghostd is actually removed). Preparation
     * and confirmation may arrive on different links: a link retry between
     * the two must not wedge the flow.
     */
    @Synchronized
    fun confirm(
        connectionId: Int,
        targetPackage: String?,
        session: String?,
        nowMs: Long
    ): String? {
        if (expireIfNeeded(nowMs)) {
            return "decommission: preparation expired; retry destroy"
        }
        if (stage != Stage.PREPARED) {
            return "decommission: no prepared decommission (stage ${stage.name.lowercase()})"
        }
        if (targetPackage?.trim() != this.targetPackage) {
            return "decommission: package does not match the prepared request"
        }
        if (session?.trim() != this.session) {
            return "decommission: session does not match the prepared request"
        }
        if (connectionId < 0) return "decommission: no authenticated connection"
        stage = Stage.CONFIRMED
        this.connectionId = connectionId
        confirmedAtMs = nowMs
        return null
    }

    /**
     * Cancel the handshake.
     *
     * A blank/[null] session is an UNCONDITIONAL reset: ghostd sends it before
     * every new `prepare` so a failed attempt is immediately retryable. It is
     * an explicit control verb, never a disconnect signal, so it cannot be
     * confused with ghostd's removal; the confirmed-link binding used by
     * [onConnectionClosed] is unchanged.
     *
     * A non-blank session only cancels a matching attempt.
     */
    @Synchronized
    fun cancel(session: String?, nowMs: Long): Boolean {
        expireIfNeeded(nowMs)
        val s = session?.trim().orEmpty()
        if (s.isEmpty()) {
            reset()
            return true
        }
        if (stage == Stage.IDLE) return true
        if (s != this.session) return false
        reset()
        return true
    }

    /**
     * The authenticated link that carried `confirmed` closed. Only then may a
     * watcher be armed. Routine commands/disconnects return false.
     */
    @Synchronized
    fun onConnectionClosed(connectionId: Int, nowMs: Long): Boolean {
        expireIfNeeded(nowMs)
        if (stage != Stage.CONFIRMED) return false
        if (connectionId != this.connectionId) return false
        if (nowMs - confirmedAtMs > ttlMs) {
            reset()
            return false
        }
        stage = Stage.DISCONNECTED
        disconnectedAtMs = nowMs
        return true
    }

    /**
     * A new authenticated link appeared. During confirmation or the grace
     * period this means ghostd survived, so the dangerous action is dropped.
     * Returns true when an active decommission was cancelled.
     */
    @Synchronized
    fun onConnectionOpened(nowMs: Long): Boolean {
        expireIfNeeded(nowMs)
        if (stage != Stage.CONFIRMED && stage != Stage.DISCONNECTED) return false
        reset()
        return true
    }

    /**
     * Resolve the grace-period watcher. WAIT keeps the pending callback;
     * UNINSTALL moves to SELF_UNINSTALLING (call [selfUninstallResult] next);
     * ABORT ends the flow with no action.
     */
    @Synchronized
    fun watcherOutcome(nowMs: Long): DecommissionWatcherOutcome {
        if (expireIfNeeded(nowMs)) return DecommissionWatcherOutcome.ABORT
        if (stage != Stage.DISCONNECTED) return DecommissionWatcherOutcome.ABORT
        if (nowMs - disconnectedAtMs < graceMs) return DecommissionWatcherOutcome.WAIT
        if (nowMs - confirmedAtMs > ttlMs) {
            reset()
            return DecommissionWatcherOutcome.ABORT
        }
        stage = Stage.SELF_UNINSTALLING
        selfUninstallAtMs = nowMs
        return DecommissionWatcherOutcome.UNINSTALL
    }

    /** The self-uninstall sheet could not be started: drop the flow. */
    @Synchronized
    fun selfUninstallResult(launched: Boolean) {
        if (!launched) reset()
    }

    /**
     * True between prepare and confirmed. The installer sheet is being opened
     * in this window, and no auto-confirm may run until the disconnect
     * watcher is armed.
     */
    @Synchronized
    fun isPreparing(nowMs: Long): Boolean {
        if (expireIfNeeded(nowMs)) return false
        return stage == Stage.PREPARED
    }

    /**
     * True only while this decommission is active and may auto-confirm an
     * "Uninstall" label. Expiry/normal operation never leaves this on.
     */
    @Synchronized
    fun allowsUninstallTap(nowMs: Long): Boolean {
        if (expireIfNeeded(nowMs)) return false
        return when (stage) {
            Stage.CONFIRMED, Stage.DISCONNECTED -> nowMs - confirmedAtMs <= ttlMs
            Stage.SELF_UNINSTALLING -> true
            else -> false
        }
    }

    /** Safe diagnostic snapshot (no nonce, no secrets). */
    @Synchronized
    fun snapshot(nowMs: Long): Map<String, Any?> {
        expireIfNeeded(nowMs)
        val out = LinkedHashMap<String, Any?>()
        out["stage"] = stage.name.lowercase()
        targetPackage?.let { out["package"] = it }
        if (stage == Stage.CONFIRMED) {
            out["expires_in_ms"] = (confirmedAtMs + ttlMs - nowMs).coerceAtLeast(0L)
        }
        out["grace_ms"] = graceMs
        return out
    }

    @Synchronized
    fun reset() {
        stage = Stage.IDLE
        session = null
        targetPackage = null
        connectionId = -1
        preparedAtMs = 0L
        confirmedAtMs = 0L
        disconnectedAtMs = 0L
        selfUninstallAtMs = 0L
    }

    /**
     * Drop stale flows. PREPARED/CONFIRMED expire from their last accepted
     * step; DISCONNECTED is bounded by grace+ttl; SELF_UNINSTALLING keeps the
     * uninstall-label permission for a bounded window if the sheet is left
     * open. Returns true when it reset.
     */
    private fun expireIfNeeded(nowMs: Long): Boolean {
        val expiresAt = when (stage) {
            Stage.PREPARED -> preparedAtMs + ttlMs
            Stage.CONFIRMED -> confirmedAtMs + ttlMs
            Stage.DISCONNECTED -> disconnectedAtMs + graceMs + ttlMs
            Stage.SELF_UNINSTALLING -> selfUninstallAtMs + TAP_WINDOW_MS
            Stage.IDLE -> return false
        }
        if (nowMs <= expiresAt) return false
        reset()
        return true
    }

    companion object {
        /** Time allowed between prepare/confirm and for the sheet to appear. */
        const val DEFAULT_TTL_MS = 120_000L

        /** Wait for a ghostd reconnection before self-uninstalling. */
        const val DEFAULT_GRACE_MS = 15_000L

        /** How long an open self-uninstall sheet may still be confirmed. */
        const val TAP_WINDOW_MS = 60_000L
    }
}

/**
 * Android-side wrapper: protocol dispatch, self-uninstall intent, and the
 * process-wide [state]. All decisions live in [DecommissionState] and
 * [DecommissionPolicy] so they stay JVM-testable.
 */
object Decommission {

    private const val TAG = "ghosta"

    val state = DecommissionState()

    /**
     * Route one `decommission` request; returns a ready JSON map.
     *
     * [overlayGranted] is the owner-setup SYSTEM_ALERT_WINDOW capability. It
     * is required for `prepare`: without it this app cannot start its own
     * uninstall activity from the background later, so the handshake must
     * fail here — while ghostd is still installed — rather than discovering
     * the gap after ghostd is gone.
     */
    fun handle(
        action: String?,
        targetPackage: String?,
        session: String?,
        connectionId: Int,
        armed: Boolean,
        overlayGranted: Boolean,
        nowMs: Long
    ): Map<String, Any?> = when (DecommissionPolicy.parseAction(action)) {
        "prepare" -> if (!overlayGranted) {
            // Distinct, actionable, non-retryable-until-granted result. No
            // state is prepared, so nothing is left armed.
            mapOf(
                "ok" to false,
                "code" to DecommissionPolicy.SELF_UNINSTALL_BLOCKED,
                "stage" to "prepare",
                "error" to DecommissionPolicy.selfUninstallBlockedMessage()
            )
        } else {
            state.prepare(connectionId, targetPackage, session, armed, nowMs)
                ?.let { CommandRouter.err(it) }
                ?: CommandRouter.ok(
                    mapOf(
                        "stage" to "prepared",
                        "package" to DecommissionPolicy.GHOSTD_PACKAGE,
                        "expires_in_ms" to state.ttlMs
                    )
                )
        }
        "confirmed" -> state.confirm(connectionId, targetPackage, session, nowMs)
            ?.let { CommandRouter.err(it) }
            ?: CommandRouter.ok(
                mapOf(
                    "stage" to "confirmed",
                    "package" to DecommissionPolicy.GHOSTD_PACKAGE,
                    "watch" to "localhost-disconnect",
                    "grace_ms" to state.graceMs
                )
            )
        "cancel" -> if (state.cancel(session, nowMs)) {
            CommandRouter.ok(mapOf("stage" to "cancelled"))
        } else {
            CommandRouter.err("decommission: cancel does not match the active session")
        }
        "status" -> CommandRouter.ok(state.snapshot(nowMs))
        else -> CommandRouter.err(
            "decommission: expected action prepare|confirmed|cancel|status"
        )
    }

    /**
     * Whether this app holds the background-activity-start capability needed
     * to open its own system uninstall sheet after ghostd is gone.
     *
     * SYSTEM_ALERT_WINDOW is granted by the owner in setup; this is a
     * capability check, not an evasion. Never assume it.
     */
    fun canDrawOverlays(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) {
        false
    }

    /**
     * Ask the system to uninstall this app. Returns false when the activity
     * cannot be started — including when the background-activity capability is
     * missing, because startActivity can be silently blocked and must not be
     * reported as success. The caller drops the flow rather than leaving a
     * watcher armed. ACTION_DELETE shows the normal system confirmation.
     */
    fun launchSelfUninstall(ctx: Context): Boolean {
        if (!canDrawOverlays(ctx)) {
            Log.w(
                TAG,
                "self-uninstall blocked (${DecommissionPolicy.SELF_UNINSTALL_BLOCKED}): " +
                    "SYSTEM_ALERT_WINDOW is not granted for " +
                    DecommissionPolicy.GHOSTA_PACKAGE
            )
            return false
        }
        return try {
            ctx.startActivity(uninstallIntent(DecommissionPolicy.GHOSTA_PACKAGE))
            true
        } catch (e: Exception) {
            // No secrets here: only the package and the platform message.
            Log.w(
                TAG,
                "self-uninstall request failed for " +
                    "${DecommissionPolicy.GHOSTA_PACKAGE}: ${e.message}"
            )
            false
        }
    }

    internal fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
