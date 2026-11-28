package com.wykasolutions.gmailsender

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives the Gmail app the way a person would: opens a compose screen for one
 * recipient, switches the From account if that recipient was assigned to a
 * different business Gmail, checks every field is right, taps Send, and waits
 * to see Gmail confirm before moving on.
 *
 * It never taps Send until the recipient, the sending account, the subject and
 * the message on screen all match the queue, so a misread screen stalls the run
 * rather than sending the wrong thing to the wrong person.
 */
class GmailBot : AccessibilityService() {

    companion object {
        const val GMAIL = "com.google.android.gm"

        @Volatile
        var instance: GmailBot? = null

        /** Pause between messages, long enough to look like a person working. */
        const val GAP_MS = 2500L

        private const val COMPOSE_TIMEOUT_MS = 15000L
        private const val CONFIRM_TIMEOUT_MS = 12000L
        private const val SWITCH_TIMEOUT_MS = 8000L

        fun isEnabled() = instance != null

        /** Opens Gmail's compose screen with the message already filled in. */
        fun openCompose(c: Context, recipient: String, subject: String, body: String): Boolean = try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(recipient)))
                .setPackage(GMAIL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
            c.startActivity(intent)
            true
        } catch (e: Exception) {
            Batch.setState(c, Batch.PAUSED, "Could not open Gmail: ${e.message}")
            false
        }
    }

    /** What the bot is doing with the message currently on screen. */
    private enum class Phase { WAITING, OPENING, FILLING, SWITCHING, CONFIRMING }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var phase = Phase.WAITING
    /** Earliest time the next message may start, so the pace stays human. */
    @Volatile private var notBefore = 0L
    private var index = -1
    private var recipient = ""
    private var sender = ""
    private var phaseStartedAt = 0L
    private var switchAttempts = 0

    override fun onServiceConnected() {
        instance = this
        // If the phone restarted the service while a Send was in flight, the
        // outcome is unknown. Never guess: ask the owner to check Gmail.
        if (Batch.state(this) == Batch.RUNNING && phase == Phase.CONFIRMING) {
            Batch.setState(this, Batch.UNCERTAIN, "The bot restarted just after tapping Send.")
        }
        phase = Phase.WAITING
        step()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        if (Batch.state(this) == Batch.RUNNING) {
            Batch.setState(this, Batch.PAUSED, "Accessibility service was interrupted.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != GMAIL) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ step() }, 400)
    }

    /** Asks for another look at the screen shortly. */
    private fun again(delay: Long = 700) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ step() }, delay)
    }

    private fun enterPhase(next: Phase) {
        phase = next
        phaseStartedAt = System.currentTimeMillis()
    }

    private fun inPhaseFor() = System.currentTimeMillis() - phaseStartedAt

    private fun pause(reason: String) {
        phase = Phase.WAITING
        Batch.setState(this, Batch.PAUSED, reason)
    }

    // ---------------------------------------------------------------- the loop

    private fun step() {
        if (Batch.state(this) != Batch.RUNNING) {
            phase = Phase.WAITING
            return
        }

        // Gmail's own screen activity must not shorten the gap between messages.
        val wait = notBefore - System.currentTimeMillis()
        if (phase == Phase.WAITING && wait > 0) {
            again(minOf(wait, 1500L))
            return
        }

        if (phase == Phase.WAITING) {
            startNext()
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != GMAIL) {
            // Gmail left the foreground. Mid-send that is not safe to assume about.
            if (phase == Phase.CONFIRMING) {
                Batch.setState(
                    this,
                    Batch.UNCERTAIN,
                    "Gmail closed right after Send for $recipient."
                )
                phase = Phase.WAITING
            } else if (inPhaseFor() > COMPOSE_TIMEOUT_MS) {
                pause("Gmail is not on screen. Open Gmail and tap Resume.")
            } else {
                again(900)
            }
            return
        }

        val nodes = Screen.all(root)
        when (phase) {
            Phase.OPENING -> if (Screen.isCompose(nodes)) {
                enterPhase(Phase.FILLING)
                again(500)
            } else if (inPhaseFor() > COMPOSE_TIMEOUT_MS) {
                dump(nodes, "compose-did-not-open")
                pause("Gmail's compose screen did not appear for $recipient.")
            } else {
                again()
            }

            Phase.FILLING -> fill(nodes)
            Phase.SWITCHING -> switchAccount(nodes)
            Phase.CONFIRMING -> confirm(nodes)
            Phase.WAITING -> Unit
        }
    }

    /** Picks up the next recipient and opens a compose screen for them. */
    private fun startNext() {
        val next = Batch.nextSendable(this)
        if (next < 0) {
            if (Batch.blockedByCap(this)) {
                pause(
                    "Every account for the remaining recipients has reached " +
                        "${Senders.DAILY_CAP} sends today. Resume tomorrow, or reassign them."
                )
            } else {
                val counts = Batch.counts(this)
                Batch.setState(
                    this,
                    Batch.DONE,
                    "Finished: ${counts.sent} sent, ${counts.failed} failed."
                )
            }
            return
        }

        index = next
        recipient = Batch.recipient(this, next)
        sender = Batch.sender(this, next)
        switchAttempts = 0

        if (sender.isBlank()) {
            Batch.mark(this, next, Batch.FAILED, "No sending account assigned")
            again(400)
            return
        }

        enterPhase(Phase.OPENING)
        if (openCompose(this, recipient, Batch.subject(this), Batch.body(this))) again(1200)
    }

    /**
     * Makes the screen match the queue: the right account, recipient, subject
     * and message. Only then does it tap Send.
     */
    private fun fill(nodes: List<AccessibilityNodeInfo>) {
        val subject = Batch.subject(this)
        val body = Batch.body(this)

        val toField = Screen.toField(nodes)
        val subjectField = Screen.subjectField(nodes)
        val bodyField = Screen.bodyField(nodes)

        // Gmail normally pre-fills these from the intent. Type anything it missed.
        if (toField != null && !Screen.label(toField).contains(recipient, true)) {
            Screen.setText(toField, recipient)
            again(600)
            return
        }
        if (subjectField != null && Screen.label(subjectField).trim() != subject.trim()) {
            Screen.setText(subjectField, subject)
            again(600)
            return
        }
        if (bodyField != null && !Screen.label(bodyField).contains(body.take(40))) {
            Screen.setText(bodyField, body)
            again(600)
            return
        }

        // The account this message would go out from, right now.
        val fromRow = Screen.fromRow(nodes, Senders.list(this))
        val fromShown = fromRow?.let { Screen.label(it) }.orEmpty()

        if (fromShown.isBlank()) {
            if (inPhaseFor() > COMPOSE_TIMEOUT_MS) {
                dump(nodes, "no-from-row")
                pause(
                    "Could not read which account would send to $recipient. " +
                        "Tap Save diagnostics and send me the file."
                )
            } else again()
            return
        }

        if (!fromShown.contains(sender, true)) {
            if (switchAttempts >= 3) {
                dump(nodes, "account-switch-failed")
                pause(
                    "Could not switch from $fromShown to $sender for $recipient. " +
                        "Tap Save diagnostics and send me the file."
                )
                return
            }
            switchAttempts++
            if (Screen.tap(fromRow)) {
                enterPhase(Phase.SWITCHING)
                again(900)
            } else {
                dump(nodes, "from-row-not-tappable")
                pause("The From row would not open for $recipient.")
            }
            return
        }

        // Everything on screen matches the queue - safe to send.
        val send = Screen.sendButton(nodes)
        if (send == null) {
            if (inPhaseFor() > COMPOSE_TIMEOUT_MS) {
                dump(nodes, "no-send-button")
                pause("Could not find Gmail's Send button for $recipient.")
            } else again()
            return
        }

        enterPhase(Phase.CONFIRMING)
        if (!Screen.tap(send)) {
            Batch.setState(
                this,
                Batch.UNCERTAIN,
                "Send would not tap for $recipient. Check Gmail's Sent folder."
            )
            phase = Phase.WAITING
        } else {
            again(900)
        }
    }

    /** Chooses the assigned account from the list Gmail just opened. */
    private fun switchAccount(nodes: List<AccessibilityNodeInfo>) {
        val option = nodes.firstOrNull { Screen.label(it).contains(sender, true) }
        if (option != null && Screen.tap(option)) {
            enterPhase(Phase.FILLING)
            again(900)
            return
        }
        if (inPhaseFor() > SWITCH_TIMEOUT_MS) {
            dump(nodes, "account-not-in-list")
            pause(
                "$sender was not in Gmail's account list. Make sure it is signed " +
                    "into the Gmail app on this phone."
            )
        } else again()
    }

    /**
     * Waits for Gmail itself to say the message went. A send is only counted
     * once Gmail confirms it, so a resumed run never sends a second copy.
     */
    private fun confirm(nodes: List<AccessibilityNodeInfo>) {
        val confirmed = nodes.any { node ->
            val text = Screen.label(node).trim()
            text.equals("Message sent", true) ||
                text.equals("Sent", true) ||
                text.startsWith("Sending", true) ||
                text.contains("Message sent", true)
        }

        if (confirmed) {
            Batch.mark(this, index, Batch.SENT)
            Senders.countSend(this, sender)
            phase = Phase.WAITING
            notBefore = System.currentTimeMillis() + GAP_MS
            again(GAP_MS)
            return
        }

        // Leaving compose without a visible confirmation usually means it sent,
        // but "usually" is not good enough when a duplicate is the cost.
        if (inPhaseFor() > CONFIRM_TIMEOUT_MS) {
            Batch.setState(
                this,
                Batch.UNCERTAIN,
                "Tapped Send for $recipient but saw no confirmation. " +
                    "Check Gmail's Sent folder before continuing."
            )
            phase = Phase.WAITING
            return
        }
        again(800)
    }

    // --------------------------------------------------------- diagnostics

    /** Records what the bot saw, so a mis-targeted tap can be fixed exactly. */
    private fun dump(nodes: List<AccessibilityNodeInfo>, tag: String) {
        Diagnostics.save(this, tag, nodes)
    }

    /** Starts or resumes work without waiting for Gmail to send an event. */
    fun kick() {
        phase = Phase.WAITING
        again(300)
    }

    /** Called from the app's Save diagnostics button. */
    fun dumpNow(tag: String): Boolean {
        val root = rootInActiveWindow ?: return false
        Diagnostics.save(this, tag, Screen.all(root))
        return true
    }

    /** The owner resolved an uncertain send by checking Gmail themselves. */
    fun resolveUncertain(alreadySent: Boolean) {
        if (Batch.state(this) != Batch.UNCERTAIN) return
        if (index >= 0) {
            if (alreadySent) {
                Batch.mark(this, index, Batch.SENT)
                Senders.countSend(this, sender)
            } else {
                Batch.mark(this, index, Batch.PENDING)
            }
        }
        phase = Phase.WAITING
        Batch.setState(this, Batch.RUNNING, "Resumed after checking Gmail.")
        again(1200)
    }
}
