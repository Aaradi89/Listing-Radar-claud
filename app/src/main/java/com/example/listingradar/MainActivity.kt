package com.example.listingradar

import android.Manifest
import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Button(this).apply { text = "Scan now"; setOnClickListener { scanNow() } })
        root.addView(Button(this).apply { text = "Start background checks (15 min)"; setOnClickListener { startBg() } })
        root.addView(Button(this).apply { text = "Stop background checks"; setOnClickListener { stopBg() } })
        out = TextView(this).apply {
            text = "Tap 'Scan now'. The first scan saves a baseline; alerts start on later scans.\n\nNot financial advice."
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)
    }

    private fun scanNow() {
        out.text = "Scanning (takes ~20-30s)..."
        Thread {
            val text = try {
                val s = Radar.runScan(this)
                s.alerts.forEach { Radar.notify(this, it) }
                val head = if (s.first) "Baseline saved.\n\n" else ""
                head + s.rows.take(30).joinToString("\n") { "%2d  %-8s %s".format(it.score, it.sym, it.why.joinToString(", ")) }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { out.text = text }
        }.start()
    }

    private fun startBg() {
        val req = PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("radar", ExistingPeriodicWorkPolicy.UPDATE, req)
        out.text = "Background checks started (Android runs them about every 15+ min)."
    }

    private fun stopBg() {
        WorkManager.getInstance(this).cancelUniqueWork("radar")
        out.text = "Background checks stopped."
    }
}
