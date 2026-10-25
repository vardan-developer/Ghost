package com.tg.ghosta

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal setup UI: shows whether the accessibility service is enabled and
 * links to the system accessibility settings so a human can enable it once
 * during setup. No launcher hiding here — the main app handles installs.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 64)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }

        val openBtn = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(openBtn)

        val holder = LinearLayout(this)
        holder.gravity = Gravity.CENTER_VERTICAL
        holder.addView(root)
        setContentView(holder)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled(this)
        status.text = if (enabled) {
            "Accessibility service: enabled ✓"
        } else {
            "Accessibility service: disabled — tap the button below and enable \"${getString(R.string.accessibility_label)}\"."
        }
        if (enabled) hideLauncherIcon(this)
    }

    companion object {
        fun isServiceEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, GhostAccessibilityService::class.java)
            val enabled = Settings.Secure.getInt(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            if (!enabled) return false
            val list = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }

        /** Hide the launcher icon once the service is enabled (alias trick). */
        fun hideLauncherIcon(ctx: Context) {
            try {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, "${ctx.packageName}.MainAlias"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
        }
    }
}
