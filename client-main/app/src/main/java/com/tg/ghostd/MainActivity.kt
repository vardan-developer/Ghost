package com.tg.ghostd

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "System Service"
            textSize = 20f
            setPadding(48, 96, 48, 48)
        }
        setContentView(tv)
        startForegroundService(Intent(this, GhostService::class.java))
    }
}
