package com.wykasolutions.gmailsender

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Works the Gmail app one recipient at a time, in fixed order:
 *
 *   open compose -> set the From account -> type the recipient ->
 *   type the subject -> type the message -> check it all -> tap Send ->
 *   wait for Gmail to confirm.
 *
 * Every step types the value itself and then reads it back off the screen
 * before moving on, so nothing is assumed to have worked. A step that cannot
 * be completed stops the run and records the screen, rather than tapping Send
 * on a half-filled message.
 */
class GmailBot : AccessibilityService() {

    enum class Step {
        IDLE, OPENING, FROM, TO, SUBJECT, BODY, REVIEW, SENDING, CONFIRMING
    }

    companion object {
        const val GMAIL = "com.google.android.gm"

        @Volatile
        var instance: GmailBot? = null

        /** The step the bot is on, shown in the app so a stall is visible. */
        @Volatile
        var step: Step = Step.IDLE

        /** What the bot is working on, shown in the app. */
        @Volatile
        var note: String = ""

        /** Set by the app's Capture button: record the next Gmail screen seen. */
        @Volatile
        var captureNext: Boolean = false

        const val GAP_MS = 2500L

        /** How many looks at the screen a single step gets before giving up. */
        private const val TRIES_PER_STEP = 10

        fun isEnabled() = instance != null

        /** Practice mode: do everything except actually tap Send. */
        fun dryRun(c: Context): Boolean =
            c.getSharedPreferences("settings", 0).getBoolean("dry_run", false)

        fun setDryRun(c: Context, value: Boolean) {
            c.getSharedPreferences("settings", 0).edit().putBoolean("dry_run", value).apply()
        }

        fun openCompose(c: Context, recipient: String, subject: String, body: String): Boolean = try {
            c.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(recipient)))
                    .setPackage(GMAIL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
            )
            true
        } catch (e: Exception) {
            Batch.setState(c, Batch.PAUSED, "Could not open Gmail: ${e.message}")
            false
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var index = -1
    private var recipient = ""
    private var sender = ""
    private var tries = 0
    private var switches = 0
    private var capturedThisRun = false

    @Volatile
    private var notBefore = 0L

    override fun onServiceConnected() {
        instance = this
        if (Batch.state(this) == Batch.RUNNING && step == Step.CONFIRMING) {
            Batch.setState(this, Batch.UNCERTAIN, "The bot restarted just after tapping Send.")
        }
        step = Step.IDLE
        again(400)
    }

    override fun onDestroy() {
        instance = null
        step = Step.IDLE
        super.onDestroy()
    }

    override fun onInterrupt() {
        if (Batch.state(this) == Batch.RUNNING) {
            Batch.setState(this, Batch.PAUSED, "Accessibility service was interrupted.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != GMAIL) return

        // A capture was asked for: record whatever Gmail is showing, run or no run.
        if (captureNext) {
            val root = rootInActiveWindow
            if (root != null) {
                val nodes = Screen.all(root)
                val compose = Screen.compose(nodes)
                Diagnostics.save(
                    this,
                    "capture (${compose.boxes} text boxes found" +
                        ", to=${compose.to != null}" +
                        ", subject=${compose.subject != null}" +
                        ", body=${compose.body != null}" +
                        ", send=${Screen.sendButton(nodes) != null})",
                    nodes
                )
                captureNext = false
            }
        }

        again(350)
    }

    private fun again(delay: Long = 650) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ runCatching { step() } }, delay)
    }

    fun kick() {
        step = Step.IDLE
        capturedThisRun = false
        again(300)
    }

    private fun go(next: Step, why: String = "") {
        step = next
        tries = 0
        if (why.isNotBlank()) note = why
    }

    /** Gives up on this run, saying precisely which step failed and why. */
    private fun giveUp(nodes: List<AccessibilityNodeInfo>, what: String) {
        Diagnostics.save(this, "stuck-${step.name.lowercase()}", nodes)
        step = Step.IDLE
        Batch.setState(
            this,
            Batch.PAUSED,
            "$what\n\nTap Save diagnostics and send the file - it records exactly " +
                "what was on the Gmail screen at that moment."
        )
    }

    // ------------------------------------------------------------------ loop

    private fun step() {
        if (Batch.state(this) != Batch.RUNNING) {
            step = Step.IDLE
            return
        }

        val wait = notBefore - System.currentTimeMillis()
        if (step == Step.IDLE && wait > 0) {
            again(minOf(wait, 1200L))
            return
        }

        if (step == Step.IDLE) {
            startNext()
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != GMAIL) {
            if (step == Step.CONFIRMING) {
                Batch.setState(
                    this,
                    Batch.UNCERTAIN,
                    "Gmail closed right after Send for $recipient. Check its Sent folder."
                )
                step = Step.IDLE
                return
            }
            if (++tries > TRIES_PER_STEP * 2) {
                step = Step.IDLE
                Batch.setState(
                    this,
                    Batch.PAUSED,
                    "Gmail is not on screen. Open Gmail, then tap Resume."
                )
            } else again(900)
            return
        }

        val nodes = Screen.all(root)
        val compose = Screen.compose(nodes)

        when (step) {
            Step.OPENING -> opening(nodes, compose)
            Step.FROM -> fromAccount(nodes, compose)
            Step.TO -> typeInto(nodes, compose.to, recipient, "recipient", Step.SUBJECT) {
                it.contains(recipient, true)
            }
            Step.SUBJECT -> typeInto(
                nodes, compose.subject, Batch.subject(this), "subject", Step.BODY
            ) { it.trim() == Batch.subject(this).trim() }
            Step.BODY -> typeInto(
                nodes, compose.body, Batch.body(this), "message", Step.REVIEW
            ) { it.contains(Batch.body(this).take(25)) }
            Step.REVIEW -> review(nodes, compose)
            Step.SENDING -> sending(nodes)
            Step.CONFIRMING -> confirming(nodes)
            Step.IDLE -> Unit
        }
    }

    private fun startNext() {
        val next = Batch.nextSendable(this)
        if (next < 0) {
            if (Batch.blockedByCap(this)) {
                Batch.setState(
                    this,
                    Batch.PAUSED,
                    "Every account for the remaining recipients has hit " +
                        "${Senders.DAILY_CAP} sends today."
                )
            } else {
                val counts = Batch.counts(this)
                Batch.setState(
                    this,
                    Batch.DONE,
                    "Finished: ${counts.sent} sent, ${counts.failed} failed."
                )
            }
            note = ""
            return
        }

        index = next
        recipient = Batch.recipient(this, next)
        sender = Batch.sender(this, next)
        switches = 0

        if (sender.isBlank()) {
            Batch.mark(this, next, Batch.FAILED, "No sending account assigned")
            again(400)
            return
        }

        go(Step.OPENING, "Opening a new email to $recipient")
        if (openCompose(this, recipient, Batch.subject(this), Batch.body(this))) again(1400)
    }

    /** Step 1: wait for Gmail's compose screen to actually be up. */
    private fun opening(nodes: List<AccessibilityNodeInfo>, compose: Screen.Compose) {
        if (compose.isCompose) {
            // Record one real compose screen per run, so the layout is known.
            if (!capturedThisRun) {
                capturedThisRun = true
                Diagnostics.save(this, "compose-screen", nodes)
            }
            go(Step.FROM, "Checking the sending account")
            again(500)
            return
        }
        if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "Gmail's compose screen did not open for $recipient.")
        } else again(800)
    }

    /** Step 2: make sure the message goes out from the assigned account. */
    private fun fromAccount(nodes: List<AccessibilityNodeInfo>, compose: Screen.Compose) {
        val row = Screen.fromRow(nodes, Senders.list(this), compose)
        val shown = row?.let { Screen.label(it) }.orEmpty()

        if (shown.contains(sender, true)) {
            go(Step.TO, "Typing the recipient")
            again(400)
            return
        }

        if (row == null) {
            if (++tries > TRIES_PER_STEP) {
                giveUp(nodes, "Could not see which account would send to $recipient.")
            } else again()
            return
        }

        if (switches >= 3) {
            giveUp(nodes, "Could not switch from $shown to $sender for $recipient.")
            return
        }

        // The account list may already be open from a previous tap.
        val option = nodes.firstOrNull {
            it != row && Screen.isOnScreen(it) && Screen.label(it).contains(sender, true)
        }
        if (option != null && Screen.tap(option)) {
            switches++
            tries = 0
            again(900)
            return
        }

        if (Screen.tap(row)) {
            switches++
            tries = 0
            again(900)
        } else if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "The From row would not open for $recipient.")
        } else again()
    }

    /**
     * Steps 3 to 5: put one value in one box, then read it back off the screen
     * before moving to the next box.
     */
    private fun typeInto(
        nodes: List<AccessibilityNodeInfo>,
        field: AccessibilityNodeInfo?,
        value: String,
        what: String,
        next: Step,
        isRight: (String) -> Boolean
    ) {
        if (field == null) {
            if (++tries > TRIES_PER_STEP) {
                giveUp(nodes, "Could not find the $what box on Gmail's compose screen.")
            } else again()
            return
        }

        if (isRight(Screen.typedText(field))) {
            go(next, "Typed the $what")
            again(400)
            return
        }

        if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "Could not type the $what into Gmail for $recipient.")
            return
        }

        // Tap it, take focus, then type - the same order a person uses.
        Screen.tap(field)
        Screen.focus(field)
        val typed = Screen.setText(field, value)

        // Some Gmail builds refuse to have text set on them. Pasting is a
        // different mechanism and often works where setting text does not.
        if (!typed || tries >= 3) paste(field, value)

        again(700)
    }

    /** Step 6: nothing is sent until all four values match the queue. */
    private fun review(nodes: List<AccessibilityNodeInfo>, compose: Screen.Compose) {
        val subject = Batch.subject(this)
        val body = Batch.body(this)

        val toOk = compose.to?.let { Screen.typedText(it).contains(recipient, true) } == true
        val subjectOk = compose.subject?.let { Screen.typedText(it).trim() == subject.trim() } == true
        val bodyOk = compose.body?.let { Screen.typedText(it).contains(body.take(25)) } == true
        val fromOk = Screen.fromRow(nodes, Senders.list(this), compose)
            ?.let { Screen.label(it).contains(sender, true) } == true

        if (toOk && subjectOk && bodyOk && fromOk) {
            go(Step.SENDING, "Sending to $recipient")
            again(400)
            return
        }

        if (++tries > TRIES_PER_STEP) {
            val missing = buildList {
                if (!fromOk) add("sending account")
                if (!toOk) add("recipient")
                if (!subjectOk) add("subject")
                if (!bodyOk) add("message")
            }.joinToString(", ")
            giveUp(nodes, "The $missing did not match the queue, so nothing was sent to $recipient.")
            return
        }

        // Go back and fix whichever box lost its value.
        go(
            when {
                !fromOk -> Step.FROM
                !toOk -> Step.TO
                !subjectOk -> Step.SUBJECT
                else -> Step.BODY
            }
        )
        again(500)
    }

    /** Step 7: tap Send. */
    private fun sending(nodes: List<AccessibilityNodeInfo>) {
        if (dryRun(this)) {
            Batch.mark(this, index, Batch.FAILED, "Practice run - Send was not tapped")
            Diagnostics.save(this, "practice-run-ready-to-send", nodes)
            step = Step.IDLE
            notBefore = System.currentTimeMillis() + GAP_MS
            note = "Practice run: everything was filled in for $recipient, Send not tapped"
            again(GAP_MS)
            return
        }

        val send = Screen.sendButton(nodes)
        if (send == null) {
            if (++tries > TRIES_PER_STEP) {
                giveUp(nodes, "Could not find Gmail's Send button for $recipient.")
            } else again()
            return
        }

        go(Step.CONFIRMING, "Waiting for Gmail to confirm")
        if (!Screen.tap(send)) {
            Batch.setState(
                this,
                Batch.UNCERTAIN,
                "Send would not tap for $recipient. Check Gmail's Sent folder."
            )
            step = Step.IDLE
        } else again(900)
    }

    /** Step 8: only count it once Gmail says so. */
    private fun confirming(nodes: List<AccessibilityNodeInfo>) {
        val confirmed = nodes.any {
            val text = Screen.label(it).trim()
            text.equals("Message sent", true) || text.equals("Sent", true) ||
                text.startsWith("Sending", true) || text.contains("Message sent", true)
        }

        if (confirmed) {
            Batch.mark(this, index, Batch.SENT)
            Senders.countSend(this, sender)
            step = Step.IDLE
            note = "Sent to $recipient"
            notBefore = System.currentTimeMillis() + GAP_MS
            again(GAP_MS)
            return
        }

        // Compose has closed and no error is showing: Gmail took the message.
        if (!Screen.compose(nodes).isCompose && tries > 2) {
            Batch.mark(this, index, Batch.SENT)
            Senders.countSend(this, sender)
            step = Step.IDLE
            note = "Sent to $recipient"
            notBefore = System.currentTimeMillis() + GAP_MS
            again(GAP_MS)
            return
        }

        if (++tries > TRIES_PER_STEP + 4) {
            Batch.setState(
                this,
                Batch.UNCERTAIN,
                "Tapped Send for $recipient but saw no confirmation. " +
                    "Check Gmail's Sent folder before continuing."
            )
            step = Step.IDLE
            return
        }
        again(800)
    }

    /** Puts the value on the clipboard and pastes it into the focused box. */
    private fun paste(field: AccessibilityNodeInfo, value: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("gmail-sender", value))
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    // ------------------------------------------------------------ for the app

    /** Records the current screen on demand, whatever is showing. */
    fun dumpNow(tag: String): Boolean {
        val root = rootInActiveWindow ?: return false
        Diagnostics.save(this, tag, Screen.all(root))
        return true
    }

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
        step = Step.IDLE
        Batch.setState(this, Batch.RUNNING, "Resumed after checking Gmail.")
        again(1200)
    }
}
