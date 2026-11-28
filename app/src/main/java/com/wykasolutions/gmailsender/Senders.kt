package com.wykasolutions.gmailsender

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Gmail accounts already signed in on this phone that the owner wants to
 * send from, plus a per-day counter so no single account is pushed past
 * Gmail's own daily limit.
 */
object Senders {

    /** Stop short of Gmail's cut-off (about 500/day free, 2000 on Workspace). */
    const val DAILY_CAP = 450

    private fun prefs(c: Context) = c.getSharedPreferences("senders", 0)

    fun list(c: Context): List<String> {
        val raw = prefs(c).getString("list", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(c: Context, email: String) {
        val merged = (list(c) + email.trim())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        prefs(c).edit().putString("list", JSONArray(merged).toString()).apply()
    }

    fun remove(c: Context, email: String) {
        val kept = list(c).filterNot { it.equals(email, true) }
        prefs(c).edit().putString("list", JSONArray(kept).toString()).apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun key(email: String) = "sent_${email.lowercase()}_${today()}"

    fun sentToday(c: Context, email: String): Int = prefs(c).getInt(key(email), 0)

    fun countSend(c: Context, email: String) {
        prefs(c).edit().putInt(key(email), sentToday(c, email) + 1).apply()
    }

    fun atCap(c: Context, email: String): Boolean = sentToday(c, email) >= DAILY_CAP
}
