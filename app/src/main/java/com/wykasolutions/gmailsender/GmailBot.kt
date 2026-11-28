package com.wykasolutions.gmailsender

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Works the Gmail app the way a person does, in fixed order:
 *
 *   open Gmail -> switch to the account this recipient was assigned ->
 *   tap Compose -> type the recipient -> type the subject ->
 *   type the message -> check it all -> tap Send -> wait for confirmation.
 *
 * The account is switched on the inbox before composing, rather than through
 * the From row inside a draft, because that is where Gmail puts the account
 * button and it leaves no doubt which account the new draft belongs to.
 *
 * Every typing step reads its value back off the screen before the next step
 * starts, and nothing is sent until all four values match the queue.
 */
class GmailBot : AccessibilityService() {

    enum class Step {
        IDLE, OPENING, ACCOUNT, SWITCHING, COMPOSE, TO, SUBJECT, BODY, REVIEW, SENDING, CONFIRMING
    }

    companion object {
        const val GMAIL = "com.google.android.gm"

        @Volatile var instance: GmailBot? = null
        @Volatile var step: Step = Step.IDLE
        @Volatile var note: String = ""
        @Volatile var captureNext: Boolean = false

        const val GAP_MS = 2500L
        private const val TRIES_PER_STEP = 10

        fun isEnabled() = instance != null

        fun dryRun(c: Context): Boolean =
            c.getSharedPreferences("settings", 0).getBoolean("dry_run", false)

        fun setDryRun(c: Context, value: Boolean) {
            c.getSharedPreferences("settings", 0).edit().putBoolean("dry_run", value).apply()
        }

        /**
         * Brings Gmail to the front.
         *
         * A mailto: intent is deliberately not used: when Gmail is already
         * running, Android brings the existing inbox forward instead of opening
         * a draft, and the bot is then left waiting for a compose screen that
         * never arrives. Tapping Compose on the inbox is what a person does and
         * it always works.
         */
        fun openGmail(c: Context): Boolean = try {
            val launch = c.packageManager.getLaunchIntentForPackage(GMAIL)
            if (launch == null) {
                Batch.setState(c, Batch.PAUSED, "The Gmail app is not installed on this phone.")
                false
            } else {
                c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
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
    private var capturedCompose = false

    @Volatile private var notBefore = 0L

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

        if (captureNext) {
            rootInActiveWindow?.let { root ->
                val nodes = Screen.all(root)
                val compose = Screen.compose(nodes)
                Diagnostics.save(
                    this,
                    "capture [inbox=${Screen.isInbox(nodes)}" +
                        " account=${Screen.activeAccount(nodes)}" +
                        " boxes=${compose.boxes}" +
                        " to=${compose.to != null}" +
                        " subject=${compose.subject != null}" +
                        " body=${compose.body != null}" +
                        " send=${Screen.sendButton(nodes) != null}]",
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
        capturedCompose = false
        again(300)
    }

    private fun go(next: Step, why: String = "") {
        step = next
        tries = 0
        if (why.isNotBlank()) note = why
    }

    private fun giveUp(nodes: List<AccessibilityNodeInfo>, what: String) {
        Diagnostics.save(this, "stuck-${step.name.lowercase()}", nodes)
        step = Step.IDLE
        Batch.setState(
            this,
            Batch.PAUSED,
            "$what\n\nTap Save diagnostics and send the file - it records what was " +
                "on the Gmail screen at that moment."
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
                Batch.setState(this, Batch.PAUSED, "Gmail is not on screen. Open Gmail, then tap Resume.")
            } else again(900)
            return
        }

        val nodes = Screen.all(root)
        val compose = Screen.compose(nodes)

        when (step) {
            Step.OPENING -> opening(nodes)
            Step.ACCOUNT -> account(nodes)
            Step.SWITCHING -> switching(nodes)
            Step.COMPOSE -> openCompose(nodes, compose)
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
                    "Every account for the remaining recipients has hit ${Senders.DAILY_CAP} sends today."
                )
            } else {
                val counts = Batch.counts(this)
                Batch.setState(this, Batch.DONE, "Finished: ${counts.sent} sent, ${counts.failed} failed.")
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

        go(Step.OPENING, "Opening Gmail for $recipient")
        if (openGmail(this)) again(1600)
    }

    /** Step 1: Gmail is in front, on a screen the bot recognises. */
    private fun opening(nodes: List<AccessibilityNodeInfo>) {
        if (Screen.isInbox(nodes)) {
            go(Step.ACCOUNT, "Checking which account is signed in")
            again(500)
            return
        }
        if (Screen.compose(nodes).isCompose) {
            // A draft is already open, so the account is whatever it belongs to.
            go(Step.COMPOSE, "A compose screen is already open")
            again(400)
            return
        }
        if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "Gmail opened on a screen the bot does not recognise.")
        } else again(800)
    }

    /** Step 2: is Gmail on the account this recipient was assigned to? */
    private fun account(nodes: List<AccessibilityNodeInfo>) {
        val active = Screen.activeAccount(nodes)

        if (active.isNotBlank() && active.equals(sender, true)) {
            go(Step.COMPOSE, "Opening a new email to $recipient")
            again(400)
            return
        }

        if (active.isBlank()) {
            if (++tries > TRIES_PER_STEP) {
                giveUp(nodes, "Could not read which Gmail account is signed in.")
            } else again()
            return
        }

        if (switches >= 3) {
            giveUp(nodes, "Could not switch Gmail from $active to $sender.")
            return
        }

        val disc = Screen.accountDisc(nodes)
        if (disc != null && Screen.tap(disc)) {
            switches++
            go(Step.SWITCHING, "Switching to $sender")
            again(1200)
        } else if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "Gmail's account button would not open.")
        } else again()
    }

    /** Step 3: pick the assigned account out of Gmail's account list. */
    private fun switching(nodes: List<AccessibilityNodeInfo>) {
        val option = nodes.firstOrNull { node ->
            Screen.isOnScreen(node) && Screen.label(node).contains(sender, true) &&
                Screen.tappable(node) != null
        }

        if (option != null && Screen.tap(option)) {
            go(Step.ACCOUNT, "Chose $sender")
            again(1500)
            return
        }

        if (++tries > TRIES_PER_STEP) {
            giveUp(
                nodes,
                "$sender was not in Gmail's account list. Make sure it is signed into " +
                    "the Gmail app itself on this phone."
            )
        } else again()
    }

    /** Step 4: tap Compose, then wait for the draft to appear. */
    private fun openCompose(nodes: List<AccessibilityNodeInfo>, compose: Screen.Compose) {
        if (compose.isCompose && !Screen.isInbox(nodes)) {
            if (!capturedCompose) {
                capturedCompose = true
                Diagnostics.save(
                    this,
                    "compose-screen [boxes=${compose.boxes} to=${compose.to != null}" +
                        " subject=${compose.subject != null} body=${compose.body != null}" +
                        " send=${Screen.sendButton(nodes) != null}]",
                    nodes
                )
            }
            go(Step.TO, "Typing the recipient")
            again(600)
            return
        }

        val button = Screen.composeButton(nodes)
        if (button != null && Screen.tap(button)) {
            tries = 0
            again(1500)
            return
        }

        if (++tries > TRIES_PER_STEP) {
            giveUp(nodes, "Could not open a new email - Gmail's Compose button did not respond.")
        } else again(800)
    }

    /** Steps 5 to 7: one box at a time, each one read back before the next. */
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

        Screen.tap(field)
        Screen.focus(field)
        val typed = Screen.setText(field, value)
        if (!typed || tries >= 3) paste(field, value)
        again(700)
    }

    /** Step 8: nothing is sent unless all four values match the queue. */
    private fun review(nodes: List<AccessibilityNodeInfo>, compose: Screen.Compose) {
        val subject = Batch.subject(this)
        val body = Batch.body(this)

        val toOk = compose.to?.let { Screen.typedText(it).contains(recipient, true) } == true
        val subjectOk = compose.subject?.let { Screen.typedText(it).trim() == subject.trim() } == true
        val bodyOk = compose.body?.let { Screen.typedText(it).contains(body.take(25)) } == true

        // If the draft shows a From row, it must agree with the assigned account.
        // When Gmail shows none, the account chosen on the inbox is what applies.
        val fromRow = Screen.fromRow(nodes, Senders.list(this), compose)
        val fromOk = fromRow == null || Screen.label(fromRow).contains(sender, true)

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

        go(
            when {
                !toOk -> Step.TO
                !subjectOk -> Step.SUBJECT
                !bodyOk -> Step.BODY
                else -> Step.ACCOUNT
            }
        )
        again(500)
    }

    /** Step 9: tap Send. */
    private fun sending(nodes: List<AccessibilityNodeInfo>) {
        if (dryRun(this)) {
            Batch.mark(this, index, Batch.FAILED, "Practice run - Send was not tapped")
            Diagnostics.save(this, "practice-run-ready-to-send", nodes)
            step = Step.IDLE
            notBefore = System.currentTimeMillis() + GAP_MS
            note = "Practice run: everything filled in for $recipient, Send not tapped"
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

    /** Step 10: only count it once Gmail says so. */
    private fun confirming(nodes: List<AccessibilityNodeInfo>) {
        val confirmed = nodes.any {
            val text = Screen.label(it).trim()
            text.equals("Message sent", true) || text.equals("Sent", true) ||
                text.startsWith("Sending", true) || text.contains("Message sent", true)
        }

        fun countIt() {
            Batch.mark(this, index, Batch.SENT)
            Senders.countSend(this, sender)
            step = Step.IDLE
            note = "Sent to $recipient"
            notBefore = System.currentTimeMillis() + GAP_MS
            again(GAP_MS)
        }

        if (confirmed) {
            countIt()
            return
        }

        // Back on the inbox with the draft gone: Gmail took the message.
        if (Screen.isInbox(nodes) && tries > 2) {
            countIt()
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

    private fun paste(field: AccessibilityNodeInfo, value: String) {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("gmail-sender", value))
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    // ------------------------------------------------------------ for the app

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
