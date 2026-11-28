package com.wykasolutions.gmailsender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The current send job, kept on disk so an interrupted run can be resumed and
 * so the owner can always see exactly who was sent to and who failed.
 */
object Batch {

    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"

    const val IDLE = "idle"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"

    private fun prefs(c: Context) = c.getSharedPreferences("batch", 0)

    fun read(c: Context): JSONObject {
        val raw = prefs(c).getString("data", "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun write(c: Context, job: JSONObject) {
        prefs(c).edit().putString("data", job.toString()).apply()
    }

    fun create(c: Context, from: String, recipients: List<String>, subject: String, body: String) {
        val items = JSONArray()
        recipients.forEach { items.put(JSONObject().put("email", it).put("state", PENDING).put("error", "")) }
        write(
            c,
            JSONObject()
                .put("from", from)
                .put("subject", subject)
                .put("body", body)
                .put("items", items)
                .put("state", RUNNING)
                .put("reason", "")
        )
    }

    fun exists(c: Context): Boolean = read(c).has("items")

    fun state(c: Context): String = read(c).optString("state", IDLE)

    fun setState(c: Context, state: String, reason: String = "") {
        if (!exists(c)) return
        write(c, read(c).put("state", state).put("reason", reason))
    }

    /** Index of the next recipient still waiting, or -1 when the job is finished. */
    fun nextPending(c: Context): Int {
        val items = read(c).optJSONArray("items") ?: return -1
        for (i in 0 until items.length()) {
            if (items.getJSONObject(i).optString("state") == PENDING) return i
        }
        return -1
    }

    fun recipient(c: Context, index: Int): String =
        read(c).optJSONArray("items")?.optJSONObject(index)?.optString("email").orEmpty()

    fun mark(c: Context, index: Int, state: String, error: String = "") {
        val job = read(c)
        val items = job.optJSONArray("items") ?: return
        val item = items.optJSONObject(index) ?: return
        item.put("state", state).put("error", error)
        write(c, job)
    }

    /** Puts every failed recipient back in the queue so only they are retried. */
    fun requeueFailed(c: Context): Int {
        val job = read(c)
        val items = job.optJSONArray("items") ?: return 0
        var moved = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("state") == FAILED) {
                item.put("state", PENDING).put("error", "")
                moved++
            }
        }
        write(c, job)
        return moved
    }

    fun clear(c: Context) {
        prefs(c).edit().remove("data").apply()
    }

    data class Counts(val sent: Int, val failed: Int, val pending: Int, val total: Int)

    fun counts(c: Context): Counts {
        val items = read(c).optJSONArray("items") ?: return Counts(0, 0, 0, 0)
        var sent = 0
        var failed = 0
        var pending = 0
        for (i in 0 until items.length()) {
            when (items.getJSONObject(i).optString("state")) {
                SENT -> sent++
                FAILED -> failed++
                else -> pending++
            }
        }
        return Counts(sent, failed, pending, items.length())
    }

    /** The failures, newest-last, for the report shown on the main screen. */
    fun failures(c: Context): List<String> {
        val items = read(c).optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .map { items.getJSONObject(it) }
            .filter { it.optString("state") == FAILED }
            .map { "${it.optString("email")} - ${it.optString("error")}" }
    }
}
