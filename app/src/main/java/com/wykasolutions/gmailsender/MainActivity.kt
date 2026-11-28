package com.wykasolutions.gmailsender

import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQUEST_IMPORT = 10
        const val REQUEST_PICK_ACCOUNT = 11
    }

    private lateinit var senderList: LinearLayout
    private lateinit var defaultSender: Spinner
    private lateinit var recipients: EditText
    private lateinit var subject: EditText
    private lateinit var body: EditText
    private lateinit var recipientCount: TextView
    private lateinit var queueSummary: TextView
    private lateinit var progress: TextView
    private lateinit var failureReport: TextView
    private lateinit var botState: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val draft by lazy { getSharedPreferences("draft", 0) }
    private val emailPattern =
        Regex("^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

    private val tick = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(18).toFloat()
    }

    private fun label(
        parent: LinearLayout,
        text: String,
        size: Float = 16f,
        bold: Boolean = false,
        color: String = "#17243D"
    ): TextView = TextView(this).also {
        it.text = text
        it.textSize = size
        it.setTextColor(Color.parseColor(color))
        if (bold) it.setTypeface(null, Typeface.BOLD)
        it.setPadding(0, dp(6), 0, dp(6))
        parent.addView(it)
    }

    private fun button(
        parent: LinearLayout,
        title: String,
        tint: String = "#365ADF",
        action: () -> Unit
    ) = Button(this).also {
        it.text = title
        it.isAllCaps = false
        it.textSize = 16f
        it.setTextColor(Color.WHITE)
        it.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
        parent.addView(it, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        it.setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#EEF2FA")
        window.navigationBarColor = Color.parseColor("#EEF2FA")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.parseColor("#EEF2FA"))
        }

        label(root, "WYKA • GMAIL SENDER", 13f, true, "#67748C")
        label(root, "One message.\nIndividual emails.", 28f, true)
        label(
            root,
            "The bot works Gmail for you, one recipient at a time. Nobody is CC'd " +
                "and nobody sees another address.",
            15f,
            false,
            "#49566F"
        )

        fun card(title: String): LinearLayout = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(dp(16), dp(12), dp(16), dp(18))
            it.background = rounded("#FFFFFF")
            root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            label(it, title, 20f, true)
        }

        fun field(parent: LinearLayout, name: String, hint: String, key: String, lines: Int = 1): EditText {
            label(parent, name, 15f, true)
            return EditText(this).also {
                it.hint = hint
                it.textSize = 16f
                it.minLines = lines
                it.gravity = Gravity.TOP
                it.setTextColor(Color.parseColor("#17243D"))
                it.setHintTextColor(Color.parseColor("#67748C"))
                it.setPadding(dp(12), dp(14), dp(12), dp(14))
                it.background = rounded("#F3F5FA")
                it.inputType = if (lines > 1)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else InputType.TYPE_CLASS_TEXT
                it.setText(draft.getString(key, ""))
                parent.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }

        // 1 - the business accounts already signed into Gmail on this phone
        val senderCard = card("1  Your Gmail accounts")
        label(
            senderCard,
            "Add each business Gmail that is already signed into the Gmail app on " +
                "this phone. The bot switches between them for you.",
            14f,
            false,
            "#49566F"
        )
        senderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        senderCard.addView(senderList)
        button(senderCard, "Add a Gmail account") { pickAccount() }

        // 2 - recipients
        val recipientCard = card("2  Recipients")
        recipients = field(
            recipientCard,
            "Send to",
            "alice@example.com\nbob@example.com\ncarol@example.com",
            "recipients",
            5
        )
        label(
            recipientCard,
            "One per line, or separated by commas. Duplicates are removed.",
            14f,
            false,
            "#49566F"
        )
        recipientCount = label(recipientCard, "", 15f, true)
        button(recipientCard, "Import TXT or CSV list", "#4A5873") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
                REQUEST_IMPORT
            )
        }

        // 3 - the message
        val composeCard = card("3  Write the email")
        subject = field(composeCard, "Subject", "e.g. Updated quotation for your review", "subject")
        body = field(composeCard, "Message", "Write the email each person receives...", "body", 8)

        // 4 - which account sends to whom
        val assignCard = card("4  Who sends to whom")
        label(
            assignCard,
            "Build the queue, then send everything from one account or split it " +
                "across several. You can also set the account for each recipient.",
            14f,
            false,
            "#49566F"
        )
        label(assignCard, "Default account", 15f, true)
        defaultSender = Spinner(this).also {
            it.setPadding(dp(8), dp(8), dp(8), dp(8))
            it.background = rounded("#F3F5FA")
            assignCard.addView(
                it,
                LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) }
            )
        }
        queueSummary = label(assignCard, "", 15f)
        button(assignCard, "Build the queue") { buildQueue() }
        button(assignCard, "Choose account per recipient", "#4A5873") { openAssign() }

        // 5 - run it
        val runCard = card("5  Run the bot")
        botState = label(runCard, "", 15f, true)
        label(
            runCard,
            "The bot needs Accessibility permission to work Gmail. While it runs, " +
                "keep the phone unlocked with Gmail on screen and do not touch it.",
            14f,
            false,
            "#49566F"
        )
        button(runCard, "Turn the bot on in Settings", "#4A5873") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(runCard, "Start sending") { start() }

        val progressCard = card("Progress")
        progress = label(progressCard, "", 16f)
        failureReport = label(progressCard, "", 13f, false, "#8A2B2B")
        button(progressCard, "Pause", "#4A5873") {
            if (Batch.exists(this)) Batch.setState(this, Batch.PAUSED, "Paused by you.")
            refreshProgress()
        }
        button(progressCard, "Resume") { resume() }
        button(progressCard, "Retry failed only", "#4A5873") { retryFailed() }
        button(progressCard, "I checked Gmail - resolve", "#4A5873") { resolveUncertain() }
        button(progressCard, "Save diagnostics", "#4A5873") { saveDiagnostics() }
        button(progressCard, "Clear this queue", "#8A2B2B") { clearQueue() }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        listOf(recipients, subject, body).forEach {
            it.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    saveDraft()
                    updateRecipientCount()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        refreshSenders()
        updateRecipientCount()
    }

    // ---------------------------------------------------------------- accounts

    private fun pickAccount() {
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null as List<android.accounts.Account>?,
            arrayOf("com.google"),
            "Choose a business Gmail to send from",
            null,
            null,
            null
        )
        startActivityForResult(intent, REQUEST_PICK_ACCOUNT)
    }

    private fun refreshSenders() {
        val senders = Senders.list(this)
        senderList.removeAllViews()

        if (senders.isEmpty()) {
            label(senderList, "No accounts added yet.", 15f, true, "#8A2B2B")
        } else {
            senders.forEach { email ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                TextView(this).also {
                    val used = Senders.sentToday(this, email)
                    it.text = "$email\n$used of ${Senders.DAILY_CAP} sent today"
                    it.textSize = 15f
                    it.setTextColor(Color.parseColor(if (Senders.atCap(this, email)) "#8A2B2B" else "#17243D"))
                    row.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
                }
                Button(this).also {
                    it.text = "Remove"
                    it.isAllCaps = false
                    it.setTextColor(Color.parseColor("#8A2B2B"))
                    it.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#F3F5FA"))
                    it.setOnClickListener {
                        Senders.remove(this, email)
                        refreshSenders()
                    }
                    row.addView(it)
                }
                senderList.addView(
                    row,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                )
            }
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            if (senders.isEmpty()) listOf("Add an account first") else senders
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        defaultSender.adapter = adapter
        defaultSender.isEnabled = senders.isNotEmpty()

        val saved = draft.getString("sender", "")
        val savedIndex = senders.indexOfFirst { it.equals(saved, true) }
        if (savedIndex >= 0) defaultSender.setSelection(savedIndex)
    }

    private fun selectedSender(): String? =
        Senders.list(this).getOrNull(defaultSender.selectedItemPosition)

    // -------------------------------------------------------------- recipients

    private fun parsedRecipients(): Pair<List<String>, Int> {
        val parts = recipients.text.toString()
            .split(Regex("[,;\\s]+"))
            .map { it.trim().trim('"', '<', '>') }
            .filter { it.isNotBlank() }
        val valid = parts.filter { emailPattern.matches(it) }.distinctBy { it.lowercase() }
        return valid to parts.count { !emailPattern.matches(it) }
    }

    private fun updateRecipientCount() {
        val (valid, invalid) = parsedRecipients()
        recipientCount.text = "${valid.size} unique recipients" +
            if (invalid > 0) " - $invalid invalid entries to fix" else ""
        recipientCount.setTextColor(Color.parseColor(if (invalid > 0) "#8A2B2B" else "#17243D"))
    }

    private fun saveDraft() {
        draft.edit()
            .putString("sender", selectedSender().orEmpty())
            .putString("recipients", recipients.text.toString())
            .putString("subject", subject.text.toString())
            .putString("body", body.text.toString())
            .apply()
    }

    // ------------------------------------------------------------------ queue

    private fun buildQueue() {
        val sender = selectedSender()
        if (sender == null) {
            Toast.makeText(this, "Add a Gmail account first", Toast.LENGTH_LONG).show()
            return
        }
        val (valid, invalid) = parsedRecipients()
        if (invalid > 0) {
            recipients.error = "Remove or fix $invalid invalid entries"
            recipients.requestFocus()
            return
        }
        if (valid.isEmpty()) {
            recipients.error = "Add at least one recipient"
            recipients.requestFocus()
            return
        }
        if (subject.text.isBlank()) {
            subject.error = "Add a subject"
            return
        }
        if (body.text.isBlank()) {
            body.error = "Write the message"
            return
        }
        if (Batch.state(this) == Batch.RUNNING) {
            Toast.makeText(this, "Pause the running queue first", Toast.LENGTH_LONG).show()
            return
        }

        val existing = Batch.counts(this)
        val replace = Batch.exists(this) && existing.pending > 0

        fun create() {
            saveDraft()
            Batch.build(this, valid, sender, subject.text.toString(), body.text.toString())
            refreshProgress()
            Toast.makeText(
                this,
                "Queue built: ${valid.size} recipients, all from $sender",
                Toast.LENGTH_LONG
            ).show()
        }

        if (replace) {
            AlertDialog.Builder(this)
                .setTitle("Replace the current queue?")
                .setMessage("It still has ${existing.pending} recipients that have not been sent to.")
                .setPositiveButton("Replace") { _, _ -> create() }
                .setNegativeButton("Keep it", null)
                .show()
        } else create()
    }

    private fun openAssign() {
        if (!Batch.exists(this)) {
            Toast.makeText(this, "Build the queue first", Toast.LENGTH_LONG).show()
            return
        }
        if (Batch.state(this) == Batch.RUNNING) {
            Toast.makeText(this, "Pause the queue before reassigning", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, AssignActivity::class.java))
    }

    // -------------------------------------------------------------- running

    private fun start() {
        if (!Batch.exists(this)) {
            Toast.makeText(this, "Build the queue first", Toast.LENGTH_LONG).show()
            return
        }
        if (!GmailBot.isEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Turn the bot on first")
                .setMessage(
                    "Open Accessibility settings, find Gmail Sender, and switch it on. " +
                        "That is what lets the app tap inside Gmail."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val counts = Batch.counts(this)
        if (counts.pending == 0) {
            Toast.makeText(this, "Nothing left to send", Toast.LENGTH_LONG).show()
            return
        }

        val perSender = Batch.perSender(this)
            .map { (sender, pair) -> "$sender: ${pair.second}" }
            .joinToString("\n")

        AlertDialog.Builder(this)
            .setTitle("Send ${counts.pending} separate emails?")
            .setMessage(
                "Subject: ${Batch.subject(this)}\n\n$perSender\n\n" +
                    "Keep the phone unlocked and leave it alone while the bot works Gmail."
            )
            .setPositiveButton("Start") { _, _ ->
                Batch.setState(this, Batch.RUNNING, "Running.")
                // Nudge the bot so it does not wait for a Gmail screen event.
                handler.postDelayed({ GmailBot.instance?.kick() }, 300)
                refreshProgress()
                Toast.makeText(this, "Open Gmail and leave the phone alone", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun resume() {
        if (!Batch.exists(this)) {
            Toast.makeText(this, "No queue to resume", Toast.LENGTH_SHORT).show()
            return
        }
        if (Batch.state(this) == Batch.UNCERTAIN) {
            Toast.makeText(this, "Resolve the uncertain send first", Toast.LENGTH_LONG).show()
            return
        }
        if (Batch.counts(this).pending == 0) {
            Toast.makeText(this, "Nothing left to send", Toast.LENGTH_SHORT).show()
            return
        }
        if (!GmailBot.isEnabled()) {
            Toast.makeText(this, "Turn the bot on in Accessibility settings", Toast.LENGTH_LONG).show()
            return
        }
        Batch.setState(this, Batch.RUNNING, "Resumed.")
        handler.postDelayed({ GmailBot.instance?.kick() }, 300)
        refreshProgress()
    }

    private fun retryFailed() {
        val moved = Batch.requeueFailed(this)
        if (moved == 0) {
            Toast.makeText(this, "No failed recipients", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$moved put back in the queue", Toast.LENGTH_LONG).show()
        refreshProgress()
    }

    private fun resolveUncertain() {
        if (Batch.state(this) != Batch.UNCERTAIN) {
            Toast.makeText(this, "Nothing uncertain right now", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Check Gmail's Sent folder")
            .setMessage(
                Batch.reason(this) +
                    "\n\nDid that email actually go out? Saying yes when it did not " +
                    "means that person never gets it; saying no when it did means they get it twice."
            )
            .setPositiveButton("It was sent") { _, _ ->
                GmailBot.instance?.resolveUncertain(true)
                refreshProgress()
            }
            .setNegativeButton("It was not sent") { _, _ ->
                GmailBot.instance?.resolveUncertain(false)
                refreshProgress()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun saveDiagnostics() {
        GmailBot.instance?.dumpNow("manual")
        val text = Diagnostics.read(this)
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("What the bot sees")
            .setMessage(text.takeLast(3000))
            .setPositiveButton("Share") { _, _ ->
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Gmail Sender diagnostics")
                            .putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000)),
                        "Send diagnostics"
                    )
                )
            }
            .setNeutralButton("Clear") { _, _ -> Diagnostics.clear(this) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearQueue() {
        if (Batch.state(this) == Batch.RUNNING) {
            Toast.makeText(this, "Pause it first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear this queue?")
            .setMessage("Its send history goes too. Your draft text stays.")
            .setPositiveButton("Clear") { _, _ ->
                Batch.clear(this)
                refreshProgress()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- progress

    private fun refreshProgress() {
        if (!::progress.isInitialized) return

        botState.text = if (GmailBot.isEnabled()) "Bot is on." else "Bot is off."
        botState.setTextColor(Color.parseColor(if (GmailBot.isEnabled()) "#1F6B3B" else "#8A2B2B"))

        if (!Batch.exists(this)) {
            progress.text = "No queue yet."
            queueSummary.text = ""
            failureReport.visibility = View.GONE
            return
        }

        val counts = Batch.counts(this)
        val state = Batch.state(this)
        val reason = Batch.reason(this)

        queueSummary.text = Batch.perSender(this)
            .map { (sender, pair) -> "$sender - ${pair.second} recipients" }
            .joinToString("\n")

        progress.text = buildString {
            append("${counts.sent} sent  •  ${counts.failed} failed  •  ${counts.pending} waiting")
            append("\nof ${counts.total} recipients")
            append("\nStatus: $state")
            if (reason.isNotBlank()) append("\n$reason")
        }
        progress.setTextColor(
            Color.parseColor(if (state == Batch.UNCERTAIN) "#8A2B2B" else "#17243D")
        )

        val failures = Batch.failures(this)
        if (failures.isEmpty()) {
            failureReport.visibility = View.GONE
        } else {
            failureReport.visibility = View.VISIBLE
            failureReport.text = "Failed:\n" + failures.take(25).joinToString("\n") +
                if (failures.size > 25) "\n...and ${failures.size - 25} more" else ""
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSenders()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        saveDraft()
        super.onPause()
    }

    @Deprecated("Activity result API kept for this small app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_ACCOUNT -> {
                val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
                Senders.add(this, email)
                refreshSenders()
                Toast.makeText(this, "$email added", Toast.LENGTH_LONG).show()
            }

            REQUEST_IMPORT -> try {
                val uri = data?.data ?: return
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText().take(2_000_000) }
                    ?: return
                val found = Regex(
                    "[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}",
                    RegexOption.IGNORE_CASE
                ).findAll(text).map { it.value }.distinct().toList()
                recipients.setText(
                    (recipients.text.toString() + "\n" + found.joinToString("\n")).trim()
                )
                Toast.makeText(this, "Imported ${found.size} addresses", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show()
            }
        }
    }
}
