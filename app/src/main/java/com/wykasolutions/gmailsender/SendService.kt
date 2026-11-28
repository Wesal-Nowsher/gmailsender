package com.wykasolutions.gmailsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Works through the queue in the background, one separate email per recipient,
 * so the phone can be locked and the owner can use other apps while it runs.
 */
class SendService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "sending"
        private const val NOTIFICATION_ID = 42

        /** Default pause between messages: steady enough not to trip Gmail's limiter. */
        const val DEFAULT_GAP_MS = 1500L

        /** Stop well short of Gmail's own cut-off so the account is never flagged. */
        const val DEFAULT_DAILY_CAP = 450

        @Volatile
        var running: Boolean = false
            private set

        private val MAX_ATTEMPTS = 3

        fun start(c: Context) {
            val intent = Intent(c, SendService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent)
            else c.startService(intent)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, SendService::class.java).setAction(ACTION_STOP))
        }

        fun gapMs(c: Context): Long =
            c.getSharedPreferences("settings", 0).getLong("gap_ms", DEFAULT_GAP_MS)

        fun setGapMs(c: Context, value: Long) {
            c.getSharedPreferences("settings", 0).edit().putLong("gap_ms", value).apply()
        }

        fun dailyCap(c: Context): Int =
            c.getSharedPreferences("settings", 0).getInt("daily_cap", DEFAULT_DAILY_CAP)
    }

    @Volatile
    private var stopRequested = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            Batch.setState(this, Batch.PAUSED, "Paused by you")
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting", "Preparing the queue"))
        running = true
        stopRequested = false

        worker = Thread { runQueue() }.also { it.start() }
        return START_STICKY
    }

    private fun runQueue() {
        try {
            loop()
        } catch (e: Exception) {
            Batch.setState(this, Batch.PAUSED, "Stopped unexpectedly: ${e.message}")
        } finally {
            running = false
            stopForeground(true)
            stopSelf()
        }
    }

    private fun loop() {
        val from = Batch.read(this).optString("from")
        val subject = Batch.read(this).optString("subject")
        val body = Batch.read(this).optString("body")

        if (from.isBlank()) {
            Batch.setState(this, Batch.PAUSED, "No sending account on this job")
            return
        }

        while (!stopRequested) {
            if (Batch.state(this) != Batch.RUNNING) return

            val index = Batch.nextPending(this)
            if (index < 0) {
                val counts = Batch.counts(this)
                Batch.setState(
                    this,
                    Batch.DONE,
                    "Finished: ${counts.sent} sent, ${counts.failed} failed"
                )
                notify("Finished", "${counts.sent} sent, ${counts.failed} failed")
                return
            }

            if (Accounts.sentToday(this, from) >= dailyCap(this)) {
                Batch.setState(
                    this,
                    Batch.PAUSED,
                    "Daily cap of ${dailyCap(this)} reached for $from. Resume tomorrow, " +
                        "or switch the remaining recipients to another account."
                )
                return
            }

            val recipient = Batch.recipient(this, index)
            val counts = Batch.counts(this)
            notify(
                "Sending ${counts.sent + 1} of ${counts.total}",
                "$recipient - ${counts.failed} failed so far"
            )

            if (!sendOne(from, recipient, subject, body, index)) return

            val gap = gapMs(this)
            var waited = 0L
            while (waited < gap && !stopRequested) {
                Thread.sleep(minOf(250L, gap - waited))
                waited += 250L
            }
        }

        if (stopRequested && Batch.state(this) == Batch.RUNNING) {
            Batch.setState(this, Batch.PAUSED, "Paused by you")
        }
    }

    /** Returns false when the whole run must halt rather than move to the next recipient. */
    private fun sendOne(
        from: String,
        recipient: String,
        subject: String,
        body: String,
        index: Int
    ): Boolean {
        var attempt = 1
        while (attempt <= MAX_ATTEMPTS && !stopRequested) {
            when (val result = GmailApi.send(this, from, recipient, subject, body)) {
                is GmailApi.Result.Sent -> {
                    Batch.mark(this, index, Batch.SENT)
                    Accounts.countSend(this, from)
                    return true
                }

                is GmailApi.Result.NeedsConsent -> {
                    Batch.setState(
                        this,
                        Batch.PAUSED,
                        "$from needs permission again. Open the app, tap Add sending " +
                            "account, choose $from, tap Allow, then Resume remaining."
                    )
                    notify("Permission needed", "Re-add $from in the app")
                    return false
                }

                is GmailApi.Result.Retryable -> {
                    if (attempt == MAX_ATTEMPTS) {
                        Batch.mark(this, index, Batch.FAILED, result.message)
                        return true
                    }
                    // Back off: 5s, then 20s, giving Gmail's limiter room to clear.
                    val backoff = 5_000L * attempt * attempt
                    var waited = 0L
                    while (waited < backoff && !stopRequested) {
                        Thread.sleep(250L)
                        waited += 250L
                    }
                    attempt++
                }

                is GmailApi.Result.Permanent -> {
                    Batch.mark(this, index, Batch.FAILED, result.message)
                    return true
                }
            }
        }
        return !stopRequested
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sending progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun notify(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, text))
    }
}
