package com.awakebubble

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    const val RUNNING = "running"
    const val AWAKE = "awake"
    const val END_AT = "end"
    const val TIMER_MIN = "timer"
    const val SIZE = "size"
    const val DIM = "dim"
    const val CUTOFF = "cutoff"
    const val IDLE = "idle"
    const val VANISH = "vanish"
    const val CHARGE_ONLY = "chg"
    const val X = "x"
    const val Y = "y"
    const val LOG = "log"

    private fun sp(c: Context) = c.getSharedPreferences("ab", Context.MODE_PRIVATE)

    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun long(c: Context, k: String, d: Long) = sp(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun log(c: Context, msg: String) {
        val line = time(System.currentTimeMillis()) + "  " + msg
        val old = str(c, LOG, "").split("\n").filter { it.isNotBlank() }
        setStr(c, LOG, (listOf(line) + old).take(6).joinToString("\n"))
    }

    fun timerName(min: Int) = when (min) {
        30 -> "৩০ মিনিট"
        60 -> "১ ঘণ্টা"
        120 -> "২ ঘণ্টা"
        else -> "সীমাহীন"
    }

    fun statusText(c: Context): String {
        if (!bool(c, RUNNING, false)) return "⚫ বাবল বন্ধ আছে\nফোন স্বাভাবিকভাবে Sleep হবে"
        if (!bool(c, AWAKE, false)) return "⚪ Sleep (স্বাভাবিক)\nফোন নিজের সেটিংসের টাইমআউট অনুযায়ী ঘুমাবে"
        val end = long(c, END_AT, 0L)
        val t = if (end > 0) "বন্ধ হবে ${time(end)} এ" else "সময়সীমা নেই"
        return "🟢 Awake চালু\nস্ক্রিন জেগে আছে · $t"
    }
}
