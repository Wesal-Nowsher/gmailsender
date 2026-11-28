package com.wykasolutions.gmailsender

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        const val REQUEST_CONSENT = 12
        const val REQUEST_NOTIFICATIONS = 13
    }

    private lateinit var accountList: LinearLayout
    private lateinit var fromSpinner: Spinner
    private lateinit var recipients: EditText
    private lateinit var subject: EditText
    private lateinit var body: EditText
    private lateinit var recipientCount: TextView
    private lateinit var progress: TextView
    private lateinit var failureReport: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val draft by lazy { getSharedPreferences("draft", 0) }
    private val emailPattern = Regex("^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    private var awaitingConsentFor: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- small view helpers, kept here so the app needs no layout files ----------

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
        it.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
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
            "Every recipient gets their own email. Nobody sees anyone else's address.",
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
                else
                    InputType.TYPE_CLASS_TEXT
                it.setText(draft.getString(key, ""))
                parent.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }

        // 1 - the business accounts, authorised once each
        val accountCard = card("1  Your sending accounts")
        label(
            accountCard,
            "Add each business Gmail once. Android shows the accounts already signed in on " +
                "this phone - pick one and tap Allow. No passwords are entered or stored.",
            14f,
            false,
            "#49566F"
        )
        accountList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountCard.addView(accountList)
        button(accountCard, "Add sending account") { pickAccount() }

        // 2 - which account this batch goes out from
        val fromCard = card("2  Send this batch from")
        fromSpinner = Spinner(this).also {
            it.setPadding(dp(8), dp(8), dp(8), dp(8))
            it.background = rounded("#F3F5FA")
            fromCard.addView(it, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        }
        label(fromCard, "", 14f, false, "#49566F").also { quotaLine = it }

        // 3 - the recipients
        val recipientCard = card("3  Recipients")
        recipients = field(
            recipientCard,
            "To - receiver email list",
            "alice@example.com\nbob@example.com\ncarol@example.com",
            "recipients",
            5
        )
        label(
            recipientCard,
            "One address per line, or separated by commas or semicolons. Duplicates are removed.",
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

        // 4 - the message
        val composeCard = card("4  Write the email")
        subject = field(composeCard, "Subject", "e.g. Updated quotation for your review", "subject")
        body = field(
            composeCard,
            "Message",
            "Write the email each person should receive...",
            "body",
            8
        )

        // 5 - send
        val sendCard = card("5  Send")
        label(
            sendCard,
            "Sending runs in the background. You can lock the phone or use other apps.",
            14f,
            false,
            "#49566F"
        )
        button(sendCard, "Review and send") { review() }

        val progressCard = card("Progress")
        progress = label(progressCard, "", 16f)
        failureReport = label(progressCard, "", 13f, false, "#8A2B2B")
        button(progressCard, "Pause", "#4A5873") { SendService.stop(this) }
        button(progressCard, "Resume remaining") { resume() }
        button(progressCard, "Retry failed only", "#4A5873") { retryFailed() }
        button(progressCard, "Clear this batch", "#8A2B2B") { clearBatch() }

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

        askForNotifications()
        refreshAccounts()
        updateRecipientCount()
    }

    private lateinit var quotaLine: TextView

    // ---------- accounts ----------

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun pickAccount() {
        // Android's own picker: it lists the Google accounts already signed in on this
        // phone, and choosing one here is what makes it visible to this app.
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

    /** Asks Google for a send token, which triggers the one-time Allow screen. */
    private fun authorise(email: String) {
        Toast.makeText(this, "Checking permission for $email", Toast.LENGTH_SHORT).show()
        Thread {
            val result = GmailApi.authorise(this, email)
            runOnUiThread {
                when (result) {
                    is GmailApi.Result.Sent -> {
                        Accounts.add(this, email)
                        refreshAccounts()
                        Toast.makeText(this, "$email is ready to send", Toast.LENGTH_LONG).show()
                    }
                    is GmailApi.Result.NeedsConsent -> {
                        val consent = result.intent
                        if (consent == null) {
                            Toast.makeText(this, "Google did not return a permission screen", Toast.LENGTH_LONG).show()
                        } else {
                            awaitingConsentFor = email
                            startActivityForResult(consent, REQUEST_CONSENT)
                        }
                    }
                    is GmailApi.Result.Retryable ->
                        Toast.makeText(this, "No network: ${result.message}", Toast.LENGTH_LONG).show()
                    is GmailApi.Result.Permanent ->
                        AlertDialog.Builder(this)
                            .setTitle("Could not authorise $email")
                            .setMessage(
                                result.message +
                                    "\n\nIf this says the app is not configured, the Google Cloud " +
                                    "OAuth client for this APK's signing key is missing. See the README."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                }
            }
        }.start()
    }

    private fun refreshAccounts() {
        val accounts = Accounts.list(this)
        accountList.removeAllViews()

        if (accounts.isEmpty()) {
            label(accountList, "No accounts added yet.", 15f, true, "#8A2B2B")
        } else {
            accounts.forEach { email ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                TextView(this).also {
                    it.text = "$email\n${Accounts.sentToday(this, email)} sent today"
                    it.textSize = 15f
                    it.setTextColor(Color.parseColor("#17243D"))
                    row.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
                }
                Button(this).also {
                    it.text = "Remove"
                    it.isAllCaps = false
                    it.setTextColor(Color.parseColor("#8A2B2B"))
                    it.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#F3F5FA"))
                    it.setOnClickListener {
                        Accounts.remove(this, email)
                        refreshAccounts()
                    }
                    row.addView(it)
                }
                accountList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            if (accounts.isEmpty()) listOf("Add an account first") else accounts
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fromSpinner.adapter = adapter
        fromSpinner.isEnabled = accounts.isNotEmpty()

        val saved = draft.getString("from", "")
        val savedIndex = accounts.indexOfFirst { it.equals(saved, true) }
        if (savedIndex >= 0) fromSpinner.setSelection(savedIndex)

        updateQuotaLine()
    }

    private fun selectedAccount(): String? {
        val accounts = Accounts.list(this)
        if (accounts.isEmpty()) return null
        val index = fromSpinner.selectedItemPosition
        return accounts.getOrNull(index)
    }

    private fun updateQuotaLine() {
        val account = selectedAccount()
        quotaLine.text = if (account == null) {
            "Add at least one sending account above."
        } else {
            val used = Accounts.sentToday(this, account)
            val cap = SendService.dailyCap(this)
            "$used of $cap sent today from this account. Google's own daily limit is about " +
                "500 for free Gmail and 2,000 for Workspace."
        }
    }

    // ---------- recipients and draft ----------

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
            .putString("from", selectedAccount().orEmpty())
            .putString("recipients", recipients.text.toString())
            .putString("subject", subject.text.toString())
            .putString("body", body.text.toString())
            .apply()
    }

    // ---------- sending ----------

    private fun review() {
        val account = selectedAccount()
        if (account == null) {
            Toast.makeText(this, "Add a sending account first", Toast.LENGTH_LONG).show()
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
            subject.requestFocus()
            return
        }
        if (body.text.isBlank()) {
            body.error = "Write the message"
            body.requestFocus()
            return
        }

        if (SendService.running || Batch.state(this) == Batch.RUNNING) {
            Toast.makeText(this, "A batch is already running", Toast.LENGTH_LONG).show()
            return
        }

        val counts = Batch.counts(this)
        val unfinished = Batch.exists(this) && counts.pending > 0
        val warning = if (unfinished)
            "\n\nThis replaces the current batch, which still has ${counts.pending} unsent recipients."
        else ""

        val remainingToday = SendService.dailyCap(this) - Accounts.sentToday(this, account)
        val capWarning = if (valid.size > remainingToday)
            "\n\nOnly $remainingToday more can go out from this account today. " +
                "The rest stay queued until tomorrow."
        else ""

        AlertDialog.Builder(this)
            .setTitle("Send ${valid.size} separate emails?")
            .setMessage(
                "From: $account\nSubject: ${subject.text}\n\n" +
                    valid.take(10).joinToString("\n") +
                    (if (valid.size > 10) "\n...and ${valid.size - 10} more" else "") +
                    capWarning + warning
            )
            .setPositiveButton("Start sending") { _, _ ->
                saveDraft()
                Batch.create(
                    this,
                    account,
                    valid,
                    subject.text.toString(),
                    body.text.toString()
                )
                SendService.start(this)
                refreshProgress()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun resume() {
        if (!Batch.exists(this)) {
            Toast.makeText(this, "No batch to resume", Toast.LENGTH_SHORT).show()
            return
        }
        if (Batch.nextPending(this) < 0) {
            Toast.makeText(this, "Nothing left to send", Toast.LENGTH_SHORT).show()
            return
        }
        Batch.setState(this, Batch.RUNNING)
        SendService.start(this)
        refreshProgress()
    }

    private fun retryFailed() {
        val moved = Batch.requeueFailed(this)
        if (moved == 0) {
            Toast.makeText(this, "No failed recipients", Toast.LENGTH_SHORT).show()
            return
        }
        Batch.setState(this, Batch.RUNNING)
        SendService.start(this)
        Toast.makeText(this, "Retrying $moved recipients", Toast.LENGTH_LONG).show()
        refreshProgress()
    }

    private fun clearBatch() {
        if (SendService.running) {
            Toast.makeText(this, "Pause the batch first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear this batch?")
            .setMessage("The send history for it is deleted. Your draft text stays.")
            .setPositiveButton("Clear") { _, _ ->
                Batch.clear(this)
                refreshProgress()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshProgress() {
        if (!::progress.isInitialized) return
        updateQuotaLine()

        if (!Batch.exists(this)) {
            progress.text = "No batch yet."
            failureReport.visibility = View.GONE
            return
        }

        val counts = Batch.counts(this)
        val job = Batch.read(this)
        val reason = job.optString("reason")
        progress.text = buildString {
            append("${counts.sent} sent  •  ${counts.failed} failed  •  ${counts.pending} waiting")
            append("\nof ${counts.total} recipients, from ${job.optString("from")}")
            append("\nStatus: ${job.optString("state")}")
            if (reason.isNotBlank()) append("\n$reason")
        }

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
        refreshAccounts()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        saveDraft()
        super.onPause()
    }

    @Deprecated("Activity result API kept for this single-activity app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_PICK_ACCOUNT -> {
                if (resultCode != RESULT_OK) return
                val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
                authorise(email)
            }

            REQUEST_CONSENT -> {
                val email = awaitingConsentFor
                awaitingConsentFor = null
                if (resultCode == RESULT_OK && email != null) authorise(email)
                else Toast.makeText(this, "Permission was not granted", Toast.LENGTH_LONG).show()
            }

            REQUEST_IMPORT -> {
                if (resultCode != RESULT_OK) return
                try {
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
}
