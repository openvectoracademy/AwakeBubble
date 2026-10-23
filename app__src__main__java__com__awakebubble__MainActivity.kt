package com.awakebubble

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var permBtn: Button
    private lateinit var runBtn: Button

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun running() = Prefs.bool(this, Prefs.RUNNING, false)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(ScrollView(this).apply { addView(buildUi()) })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(BubbleService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, f)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(rx) } catch (_: Exception) {}
    }

    private fun askOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun ensureOverlay(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        askOverlay()
        return false
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }

        root.addView(TextView(this).apply {
            text = "AwakeBubble"; textSize = 26f; setTextColor(Color.BLACK)
        })

        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFE8F5E9.toInt())
            }
        }
        root.addView(status)

        permBtn = btn("⚠ Overlay পারমিশন দিন") { askOverlay() }
        root.addView(permBtn)

        runBtn = btn("") {
            if (running()) BubbleService.send(this, BubbleService.ACT_STOP)
            else if (ensureOverlay()) BubbleService.send(this, BubbleService.ACT_START)
        }
        root.addView(runBtn)

        root.addView(btn("☀ Awake / ☾ Sleep টগল") {
            if (ensureOverlay()) {
                BubbleService.send(this, if (running()) BubbleService.ACT_TOGGLE else BubbleService.ACT_AWAKE)
            }
        })

        timerTv = TextView(this).apply { setPadding(0, dp(12), 0, 0); setTextColor(Color.BLACK) }
        root.addView(timerTv)
        val row = LinearLayout(this)
        listOf("৩০ মি" to 30, "১ ঘণ্টা" to 60, "২ ঘণ্টা" to 120, "∞" to 0).forEach { (t, m) ->
            row.addView(Button(this).apply {
                text = t
                setOnClickListener {
                    Prefs.setInt(this@MainActivity, Prefs.TIMER_MIN, m)
                    if (ensureOverlay()) BubbleService.send(this@MainActivity, BubbleService.ACT_TIMER, m)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "\nসেটিংস"; textSize = 20f; setTextColor(Color.BLACK)
        })
        slider(root, "বাবলের সাইজ", Prefs.SIZE, 36, 96, 52) { "$it dp" }
        slider(root, "Reading dim (Awake থাকার সময় উজ্জ্বলতা)", Prefs.DIM, 0, 80, 0) { if (it == 0) "বন্ধ" else "$it%" }
        slider(root, "ব্যাটারি কাটঅফ", Prefs.CUTOFF, 5, 50, 20) { "$it%" }
        slider(root, "Idle auto-sleep", Prefs.IDLE, 0, 60, 10) { if (it == 0) "বন্ধ" else "$it মিনিট" }
        slider(root, "অটো-লুকানো (Vanish)", Prefs.VANISH, 0, 15, 5) { if (it == 0) "কখনো না" else "$it সেকেন্ড" }

        root.addView(Switch(this).apply {
            text = "শুধু চার্জে থাকলে Awake"
            isChecked = Prefs.bool(this@MainActivity, Prefs.CHARGE_ONLY, false)
            setOnCheckedChangeListener { _, c -> Prefs.setBool(this@MainActivity, Prefs.CHARGE_ONLY, c) }
        })

        root.addView(btn("🔋 ব্যাটারি অপটিমাইজেশন ছাড় দিন") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(TextView(this).apply {
            text = "Xiaomi / Oppo / Vivo: ফোনের সেটিংসে AwakeBubble-এর Autostart চালু করুন, নইলে সিস্টেম সার্ভিস মেরে ফেলতে পারে।"
            textSize = 13f
        })

        logTv = TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            setTextColor(Color.DKGRAY)
        }
        root.addView(logTv)
        return root
    }

    private fun slider(root: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, fmt: (Int) -> String) {
        val label = TextView(this).apply { setPadding(0, dp(12), 0, 0); setTextColor(Color.BLACK) }
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = Prefs.int(this, key, def).coerceIn(min, max) - min
        label.text = "$title: ${fmt(sb.progress + min)}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                label.text = "$title: ${fmt(p + min)}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                Prefs.setInt(this@MainActivity, key, (s?.progress ?: 0) + min)
                if (running()) BubbleService.send(this@MainActivity, BubbleService.ACT_RELOAD)
            }
        })
        root.addView(label)
        root.addView(sb)
    }

    private fun refresh() {
        status.text = Prefs.statusText(this)
        timerTv.text = "টাইমার (পছন্দ): " + Prefs.timerName(Prefs.int(this, Prefs.TIMER_MIN, 0))
        runBtn.text = if (running()) "বাবল বন্ধ করো" else "বাবল চালু করো"
        permBtn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val log = Prefs.str(this, Prefs.LOG, "")
        logTv.text = if (log.isBlank()) "" else "সর্বশেষ আপডেট:\n$log"
    }
}
