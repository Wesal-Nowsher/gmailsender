package com.wykasolutions.gmailsender

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The sending accounts the owner has authorised on this phone, plus a per-day
 * sent counter used to warn before Gmail's own daily limit blocks the account.
 */
object Accounts {

    private fun prefs(c: Context) = c.getSharedPreferences("accounts", 0)

    fun list(c: Context): List<String> {
        val raw = prefs(c).getString("list", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(c: Context, email: String) {
        val merged = (list(c) + email.trim()).distinctBy { it.lowercase() }.filter { it.isNotBlank() }
        prefs(c).edit().putString("list", JSONArray(merged).toString()).apply()
    }

    fun remove(c: Context, email: String) {
        val kept = list(c).filterNot { it.equals(email, true) }
        prefs(c).edit().putString("list", JSONArray(kept).toString()).apply()
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun key(email: String) = "sent_${email.lowercase()}_${today()}"

    fun sentToday(c: Context, email: String): Int = prefs(c).getInt(key(email), 0)

    fun countSend(c: Context, email: String) {
        prefs(c).edit().putInt(key(email), sentToday(c, email) + 1).apply()
    }
}
