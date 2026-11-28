package com.wykasolutions.gmailsender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The queue on disk: every recipient, which account sends to them, and whether
 * that message has gone out yet. Kept on disk so an interrupted run resumes
 * without sending anybody a second copy.
 */
object Batch {

    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"

    const val IDLE = "idle"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    /** Send was tapped but no confirmation was seen - the owner must check Gmail. */
    const val UNCERTAIN = "uncertain"
    const val DONE = "done"

    private fun prefs(c: Context) = c.getSharedPreferences("batch", 0)

    fun read(c: Context): JSONObject {
        val raw = prefs(c).getString("data", "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun write(c: Context, job: JSONObject) {
        prefs(c).edit().putString("data", job.toString()).apply()
    }

    fun exists(c: Context) = read(c).has("items")

    fun state(c: Context): String = read(c).optString("state", IDLE)

    fun reason(c: Context): String = read(c).optString("reason", "")

    fun setState(c: Context, state: String, reason: String = "") {
        if (!exists(c)) return
        write(c, read(c).put("state", state).put("reason", reason))
    }

    fun subject(c: Context): String = read(c).optString("subject")

    fun body(c: Context): String = read(c).optString("body")

    fun build(c: Context, recipients: List<String>, defaultSender: String, subject: String, body: String) {
        val items = JSONArray()
        recipients.forEach {
            items.put(
                JSONObject()
                    .put("email", it)
                    .put("sender", defaultSender)
                    .put("state", PENDING)
                    .put("error", "")
            )
        }
        write(
            c,
            JSONObject()
                .put("subject", subject)
                .put("body", body)
                .put("items", items)
                .put("state", IDLE)
                .put("reason", "Queue ready. Assign senders, then start.")
        )
    }

    fun size(c: Context): Int = read(c).optJSONArray("items")?.length() ?: 0

    fun item(c: Context, index: Int): JSONObject? =
        read(c).optJSONArray("items")?.optJSONObject(index)

    fun recipient(c: Context, index: Int): String = item(c, index)?.optString("email").orEmpty()

    fun sender(c: Context, index: Int): String = item(c, index)?.optString("sender").orEmpty()

    fun setSender(c: Context, index: Int, sender: String) {
        val job = read(c)
        job.optJSONArray("items")?.optJSONObject(index)?.put("sender", sender) ?: return
        write(c, job)
    }

    /** Every recipient goes out from one account. */
    fun assignAll(c: Context, sender: String) {
        val job = read(c)
        val items = job.optJSONArray("items") ?: return
        for (i in 0 until items.length()) items.getJSONObject(i).put("sender", sender)
        write(c, job)
    }

    /** Spread the recipients across the chosen accounts, to share the daily load. */
    fun splitAcross(c: Context, senders: List<String>) {
        if (senders.isEmpty()) return
        val job = read(c)
        val items = job.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            items.getJSONObject(i).put("sender", senders[i % senders.size])
        }
        write(c, job)
    }

    fun mark(c: Context, index: Int, state: String, error: String = "") {
        val job = read(c)
        val item = job.optJSONArray("items")?.optJSONObject(index) ?: return
        item.put("state", state).put("error", error)
        write(c, job)
    }

    /**
     * The next recipient still waiting whose account has capacity left today,
     * or -1 if there is nothing left that can be sent right now.
     */
    fun nextSendable(c: Context): Int {
        val items = read(c).optJSONArray("items") ?: return -1
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("state") != PENDING) continue
            if (Senders.atCap(c, item.optString("sender"))) continue
            return i
        }
        return -1
    }

    /** True when work remains but every account for it has hit today's cap. */
    fun blockedByCap(c: Context): Boolean =
        nextSendable(c) < 0 && counts(c).pending > 0

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

    fun clear(c: Context) = prefs(c).edit().remove("data").apply()

    data class Counts(val sent: Int, val failed: Int, val pending: Int, val total: Int)

    fun counts(c: Context): Counts {
        val items = read(c).optJSONArray("items") ?: return Counts(0, 0, 0, 0)
        var sent = 0; var failed = 0; var pending = 0
        for (i in 0 until items.length()) {
            when (items.getJSONObject(i).optString("state")) {
                SENT -> sent++
                FAILED -> failed++
                else -> pending++
            }
        }
        return Counts(sent, failed, pending, items.length())
    }

    /** Per-account totals for the summary line: account to sent/total. */
    fun perSender(c: Context): Map<String, Pair<Int, Int>> {
        val items = read(c).optJSONArray("items") ?: return emptyMap()
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val sender = item.optString("sender")
            val (sent, total) = out[sender] ?: (0 to 0)
            out[sender] = (if (item.optString("state") == SENT) sent + 1 else sent) to (total + 1)
        }
        return out
    }

    fun failures(c: Context): List<String> {
        val items = read(c).optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .map { items.getJSONObject(it) }
            .filter { it.optString("state") == FAILED }
            .map { "${it.optString("email")} - ${it.optString("error")}" }
    }
}
