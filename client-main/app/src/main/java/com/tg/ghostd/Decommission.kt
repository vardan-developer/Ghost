package com.tg.ghostd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Owner-authorized `destroy` (Remove Device).
 *
 * Scope is deliberately narrow: this app removes ONLY its own artifacts and
 * its own installation. It never touches personal SMS, photos, contacts or
 * other apps. Android's normal uninstall also clears this app's private data.
 *
 * WHY A TWO-APP HANDSHAKE
 * ghosta (the accessibility sidekick) is the only component that can confirm
 * the system uninstall sheet. If ghostd simply uninstalled itself first,
 * ghosta would be left behind; if ghostd uninstalled ghosta first, ghostd's
 * own sheet would need a human tap. The order is therefore:
 *
 *   1. ghostd aborts a half-received push and deletes its staged APK copies.
 *   2. ghostd arms ghosta and sends `decommission prepare` (exact package +
 *      random session nonce). While prepared, ghosta suppresses installer
 *      auto-confirm so it cannot tap ghostd's sheet before the watcher exists.
 *   3. ghostd starts the system uninstall confirmation for its own package,
 *      then sends `decommission confirmed`. ONLY this step binds a disconnect
 *      watcher to the authenticated link that carried it, so a failure before
 *      step 3 cannot remove the sidekick.
 *   4. ghosta confirms only a recognized PackageInstaller uninstall window
 *      while armed.
 *   5. ghostd is removed; the local link closes. After a short grace period
 *      with no reconnection, ghosta starts and confirms its own uninstall.
 *
 * RESULT SEMANTICS
 * Opening a sheet is NOT success. After prepare+confirmed this command waits
 * up to [REPLACEMENT_WAIT_MS] for Android to actually replace this process.
 * Two outcomes are possible:
 *
 *  - the process is killed by the uninstall (the normal case) and no result
 *    is ever delivered; the resulting device disconnect is the removal
 *    signal;
 *  - the process survives the whole window (sheet cancelled or ignored): the
 *    watcher is cancelled and the command returns `ok:false` with
 *    `stage=awaiting_confirmation`. It must never report `ok:true` merely
 *    because a sheet was opened.
 *
 * `Intent.ACTION_DELETE` is the system uninstall API used here:
 * PackageInstaller.uninstall() requires REQUEST_DELETE_PACKAGES, which this
 * app does not declare, and action-delete has no session status callback. The
 * link disconnect is the removal signal. Silent/root uninstall is NOT
 * claimed or attempted; the system sheet remains the authority.
 */
object Decommission {

    const val ACTION = "destroy"
    const val SELF_PACKAGE = "com.tg.ghostd"

    /** Wire verbs understood by the sidekick (`Decommission.kt` in ghosta). */
    internal const val STEP_PREPARE = "prepare"
    internal const val STEP_CONFIRMED = "confirmed"
    internal const val STEP_CANCEL = "cancel"

    /** Stages ghosta reports back for an accepted step. */
    internal const val RESPONSE_PREPARED = "prepared"
    internal const val RESPONSE_CONFIRMED = "confirmed"

    /**
     * Machine-readable ghosta failure code: the sidekick cannot start a
     * background activity (no SYSTEM_ALERT_WINDOW capability), so it could
     * never confirm its own uninstall sheet. Raised by `prepare`, i.e. before
     * ghostd opens its own sheet.
     */
    internal const val CODE_SELF_UNINSTALL_BLOCKED = "self_uninstall_blocked"

    /** Stage reported when the sheet did not complete inside the wait. */
    internal const val STAGE_AWAITING_CONFIRMATION = "awaiting_confirmation"

    /**
     * How long to wait for Android to actually replace this process after
     * `confirmed`. Bounded so a cancelled/ignored sheet can never leave a
     * watcher armed: if ghostd is still alive at the deadline, ghosta's
     * watcher is cancelled and the command reports awaiting_confirmation.
     */
    internal const val REPLACEMENT_WAIT_MS = 120_000L

    /** Poll interval while waiting; only used to observe the package state. */
    internal const val REPLACEMENT_POLL_MS = 500L

    // ------------------------------------------------------------- command

    fun destroy(ctx: Context): JSONObject {
        val session = UUID.randomUUID().toString()

        // The system uninstall sheet cannot be confirmed while the keyguard is
        // up. Reuse the PIN remembered by an earlier `unlock`; otherwise fail
        // before arming anything.
        val unlocked = try {
            AppUpdater.ensureUnlocked(ctx)
        } catch (_: Exception) {
            false
        }
        if (!unlocked) {
            return failure(
                "keyguard",
                "device is locked: the system uninstall sheet cannot be confirmed " +
                    "while the keyguard is up. Unlock the phone (or run `unlock <pin>` " +
                    "once to store it), then retry destroy"
            )
        }

        // Turn the display on so the system sheet renders; best effort.
        try {
            SystemControl.wake(ctx)
        } catch (_: Exception) {
        }

        // Release a half-received push so its .part file cannot be left behind.
        try {
            FileReceiver.abortAll("destroy")
        } catch (_: Exception) {
        }

        // Clear any stale handshake from an earlier attempt before touching
        // this one. A blank session id is an unconditional reset in ghosta (it
        // is never a normal-disconnect signal), so a previously prepared or
        // confirmed flow cannot wedge a retry with "already prepared". The
        // confirmed-link watcher stays bound to the connection that carried
        // the confirmation; only this explicit verb clears it.
        sidekickSend(cancelRequest(SELF_PACKAGE, null))

        // ghosta's uninstall-confirmation path is armed-only. `destroy` is an
        // explicit operator request, so arming here is intentional and does
        // not change the routine policy.
        val armed = sidekickSend(JSONObject().put("cmd", "arm"))
        if (!armed.optBoolean("ok", false)) {
            return failure(
                "sidekick_arm",
                "ghosta is not reachable over the local link " +
                    "(${sidekickError(armed)}). Install/enable the sidekick and retry; " +
                    "ghostd refuses to start an uninstall nobody can confirm"
            )
        }

        val prepared = sidekickSend(prepareRequest(SELF_PACKAGE, session))
        if (!stageMatches(prepared, RESPONSE_PREPARED)) {
            sidekickSend(cancelRequest(SELF_PACKAGE, session))
            // A blocked sidekick must fail BEFORE ghostd opens its own sheet:
            // otherwise ghostd could be removed while ghosta can never confirm
            // its own uninstall, leaving the sidekick installed forever.
            if (isSelfUninstallBlocked(prepared)) {
                return failure(
                    CODE_SELF_UNINSTALL_BLOCKED,
                    "ghosta cannot open its own system uninstall sheet: the " +
                        "\"Display over other apps\" (SYSTEM_ALERT_WINDOW) capability " +
                        "is not granted, so Android blocks background activity starts. " +
                        "Grant it for ghosta during owner setup (Settings → Apps → " +
                        "ghosta → Display over other apps), then retry destroy. " +
                        "Nothing was uninstalled"
                )
            }
            return failure(
                "sidekick_prepare",
                "ghosta refused decommission preparation (${sidekickError(prepared)}); " +
                    "nothing was uninstalled"
            )
        }

        // Delete only our own staged artifacts. A failure here is reported but
        // does not block removal: uninstall clears this app's private data.
        val artifacts = cleanup(artifactFiles(ctx.cacheDir))

        // Start the sheet while ghosta is only PREPARED: its installer
        // auto-confirm is suppressed in that stage, so the dialog cannot be
        // tapped before the disconnect watcher exists.
        val started = try {
            ctx.startActivity(uninstallIntent(SELF_PACKAGE))
            true
        } catch (e: Exception) {
            false
        }
        if (!started) {
            sidekickSend(cancelRequest(SELF_PACKAGE, session))
            return failure(
                "uninstall_request",
                "could not open the system uninstall sheet for $SELF_PACKAGE. " +
                    "Remove the device from Android Settings → Apps, and cancel the " +
                    "sidekick if it should stay installed"
            )
        }

        // Now commit: arm ghosta's watcher and allow the Uninstall tap. If this
        // fails, the sheet stays open for a manual tap and ghosta stays
        // installed (nothing is watching the link yet).
        val confirmed = sidekickSend(confirmedRequest(SELF_PACKAGE, session))
        if (!stageMatches(confirmed, RESPONSE_CONFIRMED)) {
            sidekickSend(cancelRequest(SELF_PACKAGE, session))
            return failure(
                "sidekick_confirm",
                "the system uninstall sheet is open but ghosta did not accept the " +
                    "decommission confirmation (${sidekickError(confirmed)}). Confirm " +
                    "Uninstall manually, or cancel the sheet and retry destroy; " +
                    "ghosta was left installed"
            )
        }

        // Opening a sheet is not removal. Wait for Android to actually replace
        // this process. In the normal case the process is killed here and no
        // result is delivered at all; the device disconnect is the signal the
        // daemon reconciles. If we are still alive at the deadline, the sheet
        // was cancelled or ignored: cancel ghosta's watcher so a later,
        // unrelated disconnect can never remove the sidekick, and report a
        // non-successful, retryable result.
        val waitedMs = awaitProcessReplacement(ctx)

        if (isSelfPackageGone(ctx)) {
            return replacedResult(artifacts, waitedMs)
        }

        sidekickSend(cancelRequest(SELF_PACKAGE, session))
        return awaitingConfirmation(artifacts, waitedMs)
    }

    // ------------------------------------------------------------- protocol
    // Pure builders/validators, shared with the JVM tests.

    internal fun prepareRequest(packageName: String, session: String): JSONObject =
        JSONObject()
            .put("cmd", "decommission")
            .put("action", STEP_PREPARE)
            .put("package", packageName)
            .put("session", session)

    internal fun confirmedRequest(packageName: String, session: String): JSONObject =
        JSONObject()
            .put("cmd", "decommission")
            .put("action", STEP_CONFIRMED)
            .put("package", packageName)
            .put("session", session)

    /**
     * Cancel a decommission flow. A blank/[null] session omits the field,
     * which ghosta treats as an unconditional reset (never as a normal
     * disconnect signal). A matching session cancels only that attempt.
     */
    internal fun cancelRequest(packageName: String, session: String?): JSONObject =
        JSONObject()
            .put("cmd", "decommission")
            .put("action", STEP_CANCEL)
            .put("package", packageName)
            .apply {
                val s = session?.trim()
                if (!s.isNullOrEmpty()) put("session", s)
            }

    /**
     * A response is accepted only when it is ok, reports the stage we asked
     * for, and echoes the exact package we are removing. This rejects a
     * success meant for a different target.
     */
    internal fun stageMatches(
        res: JSONObject?,
        expected: String,
        expectedPackage: String = SELF_PACKAGE
    ): Boolean =
        res != null &&
            res.optBoolean("ok", false) &&
            res.optString("stage") == expected &&
            res.optString("package") == expectedPackage

    internal fun sidekickError(res: JSONObject?): String =
        res?.optString("error").orEmpty().ifBlank { "no response" }

    /** True when ghosta reported that it cannot launch its own sheet. */
    internal fun isSelfUninstallBlocked(res: JSONObject?): Boolean =
        res?.optString("code") == CODE_SELF_UNINSTALL_BLOCKED

    /** True once the bounded replacement wait has elapsed. */
    internal fun replacementWaitElapsed(
        startedAtMs: Long,
        nowMs: Long,
        budgetMs: Long = REPLACEMENT_WAIT_MS
    ): Boolean = nowMs - startedAtMs >= budgetMs

    /**
     * The only success shape: Android was observed to have replaced this
     * installation. A sheet merely being open never produces this result.
     */
    internal fun replacedResult(artifacts: JSONObject, waitedMs: Long): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("action", ACTION)
            .put("stage", "replaced")
            .put("replaced", true)
            .put("removing", SELF_PACKAGE)
            .put("uninstall_requested", true)
            .put("artifacts", artifacts)
            .put("sidekick", JSONObject().put("prepared", true).put("confirmed", true))
            .put(
                "sequence",
                "system uninstall sheet -> ghosta confirms PackageInstaller window -> " +
                    "ghostd removed -> ghosta link disconnect -> ghosta self-uninstalls"
            )
            .put("waited_ms", waitedMs)
            .put("replacement_wait_ms", REPLACEMENT_WAIT_MS)
            .put(
                "note",
                "no root/silent uninstall is claimed; the system sheet remains the " +
                    "authority. Removal usually kills this process before any result " +
                    "is delivered; the device disconnect is the expected signal"
            )

    /**
     * The sheet did not complete within [REPLACEMENT_WAIT_MS]: this process
     * survived and ghosta's watcher has been cancelled. Deliberately
     * `ok:false` — opening a sheet is not removal.
     */
    internal fun awaitingConfirmation(artifacts: JSONObject, waitedMs: Long): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("action", ACTION)
            .put("stage", STAGE_AWAITING_CONFIRMATION)
            .put("awaiting_confirmation", true)
            .put("removing", SELF_PACKAGE)
            .put("uninstall_requested", true)
            .put("artifacts", artifacts)
            .put("waited_ms", waitedMs)
            .put("replacement_wait_ms", REPLACEMENT_WAIT_MS)
            .put(
                "error",
                "the system uninstall sheet was opened but $SELF_PACKAGE was not " +
                    "replaced within ${REPLACEMENT_WAIT_MS / 1000}s (sheet cancelled, " +
                    "ignored, or the sidekick could not confirm it). ghosta's " +
                    "decommission watcher was cancelled, so ghosta stays installed and " +
                    "a later disconnect cannot remove it. Confirm Uninstall on the phone " +
                    "and retry destroy if needed"
            )

    /** Only these staged copies are ours; everything else is left alone. */
    internal fun artifactFiles(cacheDir: File): List<File> = listOf(
        File(AppUpdater.DEFAULT_SELF_APK_PATH),
        File(AppUpdater.DEFAULT_SELF_APK_PATH + ".part"),
        File(SidekickInstaller.DEFAULT_APK_PATH),
        File(SidekickInstaller.DEFAULT_APK_PATH + ".part"),
        File(cacheDir, "ghosta-bundled.apk")
    )

    /**
     * Delete [files] when present. Missing files are not failures. Returns
     * {"complete","removed":[...],"failed":[{"path","error"}]}.
     */
    internal fun cleanup(files: List<File>): JSONObject {
        val removed = JSONArray()
        val failed = JSONArray()
        for (f in files) {
            try {
                if (!f.exists()) continue
                if (f.delete()) {
                    removed.put(f.absolutePath)
                } else {
                    failed.put(
                        JSONObject().put("path", f.absolutePath).put("error", "delete failed")
                    )
                }
            } catch (e: Exception) {
                failed.put(
                    JSONObject().put("path", f.absolutePath)
                        .put("error", e.message ?: "delete failed")
                )
            }
        }
        return JSONObject()
            .put("complete", failed.length() == 0)
            .put("removed", removed)
            .put("failed", failed)
    }

    internal fun failure(stage: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("stage", stage)
        .put("error", message)

    // ------------------------------------------------------------- internal

    private fun sidekickSend(request: JSONObject): JSONObject = try {
        SidekickLink.send(request)
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "sidekick link: ${e.message}")
    }

    internal fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Wait, bounded by [REPLACEMENT_WAIT_MS], for Android to replace this
     * installation.
     *
     * In the normal path the uninstaller kills this process and this never
     * returns: the device disconnect is the removal signal. The poll only
     * covers the case where the package disappears while the process is still
     * alive; an interrupt (command cancelled) stops the wait early. Both early
     * exits fall through to the same awaiting_confirmation result. Returns the
     * elapsed wait in milliseconds.
     */
    private fun awaitProcessReplacement(ctx: Context): Long {
        val startedAt = SystemClock.elapsedRealtime()
        while (!replacementWaitElapsed(startedAt, SystemClock.elapsedRealtime())) {
            if (isSelfPackageGone(ctx)) break
            try {
                Thread.sleep(REPLACEMENT_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        return SystemClock.elapsedRealtime() - startedAt
    }

    /** True only when this package is observably no longer installed. */
    private fun isSelfPackageGone(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SELF_PACKAGE, 0)
        false
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        true
    } catch (_: Exception) {
        // Unknown ≠ gone: never claim success on an ambiguous read.
        false
    }
}
