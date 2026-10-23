package com.awakebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class BubbleService : Service() {

    companion object {
        const val ACT_START = "ab.start"
        const val ACT_TOGGLE = "ab.toggle"
        const val ACT_AWAKE = "ab.awake"
        const val ACT_SLEEP = "ab.sleep"
        const val ACT_TIMER = "ab.timer"
        const val ACT_VANISH = "ab.vanish"
        const val ACT_STOP = "ab.stop"
        const val ACT_RELOAD = "ab.reload"
        const val EXTRA_MIN = "min"
        const val ACTION_STATE = "com.awakebubble.STATE"
        const val CH = "awake_status"
        const val NID = 1

        fun send(c: Context, action: String, min: Int = 0) {
            c.startForegroundService(
                Intent(c, BubbleService::class.java).setAction(action).putExtra(EXTRA_MIN, min)
            )
        }
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var menu: View? = null
    private var lp = WindowManager.LayoutParams()
    private var awake = false
    private var vanished = false
    private var screenW = 0
    private var screenH = 0

    private val BASE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- timers ----------
    private val timerRun = Runnable { setAwake(false, "টাইমার শেষ") }
    private val idleRun = Runnable {
        setAwake(false, "${Prefs.int(this, Prefs.IDLE, 10)} মিনিট টাচ হয়নি")
    }
    private val vanishRun = Runnable { vanish() }

    private fun resetIdle() {
        h.removeCallbacks(idleRun)
        if (!awake) return
        val m = Prefs.int(this, Prefs.IDLE, 10)
        if (m > 0) h.postDelayed(idleRun, m * 60_000L)
    }

    private fun scheduleVanish() {
        h.removeCallbacks(vanishRun)
        val s = Prefs.int(this, Prefs.VANISH, 5)
        if (s > 0 && !vanished && menu == null) h.postDelayed(vanishRun, s * 1000L)
    }

    // ---------- battery ----------
    private fun batt(i: Intent?): Pair<Int, Boolean> {
        val b = i ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pct = if (lvl < 0 || scale <= 0) 100 else lvl * 100 / scale
        return Pair(pct, plugged)
    }

    private val battRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!awake) return
            val (pct, plugged) = batt(i)
            if (!plugged && pct <= Prefs.int(this@BubbleService, Prefs.CUTOFF, 20)) {
                setAwake(false, "ব্যাটারি $pct% — নিরাপত্তার জন্য বন্ধ")
            } else if (Prefs.bool(this@BubbleService, Prefs.CHARGE_ONLY, false) && !plugged) {
                setAwake(false, "চার্জার খোলা হয়েছে")
            }
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Awake status", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(battRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Prefs.setBool(this, Prefs.AWAKE, false)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val a = i?.action ?: ACT_START
        if (a == ACT_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Prefs.log(this, "⚠ Overlay পারমিশন নেই")
            stopAll()
            return START_NOT_STICKY
        }
        val first = bubble == null
        if (first) {
            createBubble()
            Prefs.setBool(this, Prefs.RUNNING, true)
            Prefs.log(this, "🫧 বাবল চালু হয়েছে")
        }
        when (a) {
            ACT_TOGGLE -> { unvanish(); setAwake(!awake, "টগল") }
            ACT_AWAKE -> setAwake(true, "চালু করা হয়েছে")
            ACT_SLEEP -> setAwake(false, "বন্ধ করা হয়েছে")
            ACT_TIMER -> {
                val m = i?.getIntExtra(EXTRA_MIN, 0) ?: 0
                Prefs.setInt(this, Prefs.TIMER_MIN, m)
                setAwake(true, "টাইমার ${Prefs.timerName(m)}")
            }
            ACT_VANISH -> if (vanished) { unvanish(); scheduleVanish() } else vanish()
            ACT_RELOAD -> if (!first) { dismissMenu(); removeBubble(); createBubble() }
        }
        refreshNotif()
        publish()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(battRx) } catch (_: Exception) {}
        cleanup()
        super.onDestroy()
    }

    private fun stopAll() {
        cleanup()
        Prefs.log(this, "⚫ বাবল বন্ধ — ফোন স্বাভাবিক Sleep মোডে")
        publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        h.removeCallbacksAndMessages(null)
        dismissMenu()
        removeBubble()
        awake = false
        Prefs.setBool(this, Prefs.RUNNING, false)
        Prefs.setBool(this, Prefs.AWAKE, false)
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    // ---------- awake logic ----------
    private fun setAwake(on: Boolean, why: String) {
        if (on) {
            val (pct, plugged) = batt(null)
            if (!plugged && pct <= Prefs.int(this, Prefs.CUTOFF, 20)) {
                say("🔋 ব্যাটারি $pct% — Awake চালু হবে না"); return
            }
            if (Prefs.bool(this, Prefs.CHARGE_ONLY, false) && !plugged) {
                say("🔌 শুধু চার্জে থাকলে Awake — চার্জার লাগান"); return
            }
        }
        awake = on
        h.removeCallbacks(timerRun)
        var end = 0L
        val m = Prefs.int(this, Prefs.TIMER_MIN, 0)
        if (on && m > 0) {
            end = System.currentTimeMillis() + m * 60_000L
            h.postDelayed(timerRun, m * 60_000L)
        }
        Prefs.setBool(this, Prefs.AWAKE, on)
        Prefs.setLong(this, Prefs.END_AT, end)
        applyWindow()
        updateLook()
        resetIdle()
        say(if (on) "🟢 Awake চালু — $why" else "⚪ Sleep — $why")
    }

    private fun say(msg: String) {
        Prefs.log(this, msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        refreshNotif()
        publish()
    }

    private fun applyWindow() {
        val b = bubble ?: return
        lp.flags = BASE or (if (awake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        val dim = Prefs.int(this, Prefs.DIM, 0)
        lp.screenBrightness = if (awake && dim > 0) dim.coerceAtLeast(5) / 100f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
    }

    // ---------- bubble view ----------
    private fun createBubble() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val size = dp(Prefs.int(this, Prefs.SIZE, 52))
        lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, BASE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.int(this@BubbleService, Prefs.X, screenW - size).coerceIn(0, screenW - size)
            y = Prefs.int(this@BubbleService, Prefs.Y, screenH / 3).coerceIn(0, screenH - size)
        }
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = size / dm.density * 0.4f
            setOnTouchListener(touch)
        }
        wm.addView(tv, lp)
        bubble = tv
        vanished = false
        updateLook()
        applyWindow()
        scheduleVanish()
    }

    private fun updateLook() {
        val b = bubble ?: return
        b.text = if (awake) "☀" else "☾"
        b.setTextColor(if (awake) Color.WHITE else 0xFF333333.toInt())
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (awake) 0xFF2E7D32.toInt() else 0xFFBDBDBD.toInt())
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun vanish() {
        val b = bubble ?: return
        if (menu != null) return
        vanished = true
        b.alpha = 0.2f
        lp.x = if (lp.x + lp.width / 2 < screenW / 2) -lp.width / 2 else screenW - lp.width / 2
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
        refreshNotif()
    }

    private fun unvanish() {
        val b = bubble ?: return
        if (!vanished) return
        vanished = false
        b.alpha = 1f
        lp.x = lp.x.coerceIn(0, screenW - lp.width)
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
        refreshNotif()
    }

    // ---------- touch ----------
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var longFired = false
    private var ignoreTap = false
    private val longRun = Runnable { longFired = true; showMenu(false) }

    private val touch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                h.removeCallbacks(vanishRun)
                ignoreTap = vanished
                if (vanished) unvanish()
                downX = e.rawX; downY = e.rawY
                startX = lp.x; startY = lp.y
                moved = false; longFired = false
                h.postDelayed(longRun, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) > dp(8)) {
                    moved = true
                    h.removeCallbacks(longRun)
                }
                if (moved) {
                    lp.x = (startX + dx).toInt().coerceIn(0, screenW - lp.width)
                    lp.y = (startY + dy).toInt().coerceIn(0, screenH - lp.height)
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longRun)
                if (moved) {
                    Prefs.setInt(this, Prefs.X, lp.x)
                    Prefs.setInt(this, Prefs.Y, lp.y)
                } else if (!longFired && !ignoreTap) {
                    setAwake(!awake, "বাবলে ট্যাপ")
                }
                resetIdle()
                scheduleVanish()
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longRun)
                scheduleVanish()
            }
            MotionEvent.ACTION_OUTSIDE -> resetIdle()
        }
        true
    }

    // ---------- menu ----------
    private fun showMenu(timerPage: Boolean) {
        dismissMenu()
        h.removeCallbacks(vanishRun)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(170)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF2222222.toInt())
            }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) dismissMenu()
                false
            }
        }
        fun item(label: String, act: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { dismissMenu(); act() }
            })
        }
        if (!timerPage) {
            item("🟢 Awake") { setAwake(true, "মেনু থেকে") }
            item("⚪ Sleep") { setAwake(false, "মেনু থেকে") }
            item("⏱ টাইমার") { showMenu(true) }
            item("👻 লুকাও") { vanish() }
            item("✖ বন্ধ করো") { stopAll() }
        } else {
            listOf(30, 60, 120, 0).forEach { m ->
                item(Prefs.timerName(m)) {
                    Prefs.setInt(this, Prefs.TIMER_MIN, m)
                    setAwake(true, "টাইমার ${Prefs.timerName(m)}")
                }
            }
        }
        val mlp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lp.x + lp.width / 2 > screenW / 2) (lp.x - dp(176)).coerceAtLeast(0) else lp.x + lp.width + dp(6)
            y = lp.y.coerceAtMost(screenH - dp(300)).coerceAtLeast(0)
        }
        try { wm.addView(col, mlp); menu = col } catch (_: Exception) {}
    }

    private fun dismissMenu() {
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
        scheduleVanish()
    }

    // ---------- notification + state broadcast ----------
    private fun pi(action: String, code: Int) = PendingIntent.getForegroundService(
        this, code, Intent(this, BubbleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotif(): Notification {
        val title = if (awake) "🟢 Awake চালু" else "⚪ Sleep — স্বাভাবিক"
        val end = Prefs.long(this, Prefs.END_AT, 0L)
        val sub = if (awake) {
            if (end > 0) "বন্ধ হবে ${Prefs.time(end)} এ" else "সময়সীমা নেই"
        } else "ফোন নিজের টাইমআউটে ঘুমাবে"
        val last = Prefs.str(this, Prefs.LOG, "").lines().firstOrNull() ?: ""
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(sub)
            .setStyle(Notification.BigTextStyle().bigText("$sub\n$last"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (awake) "Sleep করো" else "Awake করো", pi(ACT_TOGGLE, 1))
            .addAction(0, if (vanished) "বাবল দেখাও" else "বাবল লুকাও", pi(ACT_VANISH, 2))
            .addAction(0, "বন্ধ", pi(ACT_STOP, 3))
            .build()
    }

    private fun startFg() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }

    private fun refreshNotif() {
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif())
    }

    private fun publish() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        try {
            TileService.requestListeningState(this, ComponentName(this, AwakeTileService::class.java))
        } catch (_: Exception) {}
    }
}
