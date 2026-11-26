package com.wykasolutions.gmailsender

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
import android.text.TextWatcher
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
 private lateinit var from: EditText
 private lateinit var recipients: EditText
 private lateinit var subject: EditText
 private lateinit var body: EditText
 private lateinit var status: TextView
 private lateinit var count: TextView
 private lateinit var queueCard: LinearLayout
 private val handler = Handler(Looper.getMainLooper())
 private val draft by lazy { getSharedPreferences("draft", 0) }
 private val email = Regex("^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
 private val tick = object : Runnable { override fun run() { update(); handler.postDelayed(this, 1000) } }
 private fun dp(n: Int) = (resources.displayMetrics.density*n).toInt()
 private fun bg(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(18).toFloat() }
 private fun label(parent: LinearLayout, text: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).also {
  it.text = text; it.textSize = size; it.setTextColor(Color.parseColor("#17243D")); if (bold) it.setTypeface(null, Typeface.BOLD)
  it.setPadding(0, dp(8), 0, dp(8)); parent.addView(it)
 }
 private fun button(parent: LinearLayout, title: String, action: () -> Unit) = Button(this).also {
  it.text = title; it.isAllCaps = false; it.textSize = 16f; it.setTextColor(Color.WHITE); it.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#365ADF"))
  parent.addView(it, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) }); it.setOnClickListener { action() }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor = Color.parseColor("#EEF2FA")
  window.navigationBarColor = Color.parseColor("#EEF2FA")
  val root = LinearLayout(this).apply { orientation = 1; setPadding(dp(20), dp(24), dp(20), dp(32)); setBackgroundColor(Color.parseColor("#EEF2FA")) }
  label(root, "WYKA • GMAIL SENDER", 13f, true)
  label(root, "One message.\nIndividual emails.", 28f, true)
  label(root, "Choose your business account, add recipients, then write your email.")
  fun card(title: String): LinearLayout = LinearLayout(this).also {
   it.orientation = 1; it.setPadding(dp(16), dp(12), dp(16), dp(18)); it.background = bg("#FFFFFF")
   root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }); label(it, title, 20f, true)
  }
  fun field(parent: LinearLayout, name: String, hint: String, key: String, lines: Int = 1): EditText {
   label(parent, name, 15f, true)
   return EditText(this).also {
    it.hint = hint; it.textSize = 16f; it.minLines = lines; it.gravity = Gravity.TOP
    it.setTextColor(Color.parseColor("#17243D")); it.setHintTextColor(Color.parseColor("#67748C")); it.setPadding(dp(12), dp(14), dp(12), dp(14)); it.background = bg("#F3F5FA")
    it.inputType = if (lines > 1) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_CLASS_TEXT
    it.setText(draft.getString(key, "")); parent.addView(it, LinearLayout.LayoutParams(-1, -2))
   }
  }
  val account = card("1  Sending account")
  from = field(account, "From email address", "e.g. business@gmail.com", "from")
  label(account, "Use an account already signed into Gmail. The bot checks this address before tapping Send.", 14f)
  button(account, "Open Gmail to choose account") { try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL).setPackage("com.google.android.gm")) } catch (_: Exception) { Toast.makeText(this, "Gmail unavailable", Toast.LENGTH_LONG).show() } }
  val receiver = card("2  Recipients")
  recipients = field(receiver, "To • receiver email list", "alice@example.com\nbob@example.com\ncarol@example.com", "recipients", 5)
  label(receiver, "Paste one address per line, or separate with commas / semicolons.", 14f)
  count = label(receiver, "")
  button(receiver, "Import TXT or CSV recipient list") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 10) }
  val compose = card("3  Write email")
  subject = field(compose, "Email subject", "e.g. Updated quotation for your review", "subject")
  body = field(compose, "Email message", "Write the email you want each person to receive…", "body", 8)
  val controls = card("4  Review and send")
  label(controls, "Enable Gmail Sender under Android Accessibility settings. Keep Gmail visible and your phone unlocked during sending.", 14f)
  button(controls, "Enable Gmail automation") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  button(controls, "Review recipients and start") { review() }
  queueCard = card("Sending progress")
  status = label(queueCard, "", 16f)
  button(queueCard, "Pause sending") { val state = SendQueue.current(this).optString("state"); SendQueue.setState(this, if (state == "awaiting") "uncertain" else "paused", "Paused by you") }
  button(queueCard, "Resume pending emails") { SendQueue.resume(this) }
  button(queueCard, "Resolve uncertain send") {
   if (SendQueue.current(this).optString("state") != "uncertain") { Toast.makeText(this, "No uncertain message", Toast.LENGTH_SHORT).show(); return@button }
   AlertDialog.Builder(this).setTitle("Check Gmail Sent first").setMessage("Was this recipient's email sent? Retrying a sent email creates a duplicate.").setPositiveButton("Already sent") { _, _ -> SendQueue.resolve(this, true) }.setNegativeButton("Not sent — retry") { _, _ -> SendQueue.resolve(this, false) }.setNeutralButton("Cancel", null).show()
  }
  button(queueCard, "Clear queue") { AlertDialog.Builder(this).setTitle("Clear current queue?").setMessage("Saved draft fields will remain.").setPositiveButton("Clear") { _, _ -> SendQueue.setState(this, "done", "Queue cleared") }.setNegativeButton("Cancel", null).show() }
  setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
  listOf(from, recipients, subject, body).forEach { it.addTextChangedListener(object : TextWatcher {
   override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
   override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveDraft(); updateCount() }
   override fun afterTextChanged(s: Editable?) {}
  }) }; updateCount()
 }
 private fun parsed(): Pair<List<String>, Int> {
  val parts = recipients.text.toString().split(Regex("[,;\\s]+" )).map { it.trim().trim('"', '<', '>') }.filter { it.isNotBlank() }
  return parts.filter { email.matches(it) }.distinctBy { it.lowercase() } to parts.count { !email.matches(it) }
 }
 private fun updateCount() { val (list, invalid) = parsed(); count.text = "${list.size} unique recipients • $invalid invalid entries" }
 private fun saveDraft() { draft.edit().putString("from", from.text.toString()).putString("recipients", recipients.text.toString()).putString("subject", subject.text.toString()).putString("body", body.text.toString()).apply() }
 private fun review() {
  val (list, invalid) = parsed()
  if (!email.matches(from.text.toString().trim())) { from.error = "Enter the sending Gmail address"; from.requestFocus(); return }
  if (list.isEmpty() || invalid > 0) { recipients.error = "Enter valid recipients; remove $invalid invalid entries"; recipients.requestFocus(); return }
  if (subject.text.isBlank()) { subject.error = "Add an email subject"; return }
  if (body.text.isBlank()) { body.error = "Write your email message"; return }
  if (GmailAutomation.instance == null) { AlertDialog.Builder(this).setMessage("Enable Gmail Sender in Accessibility settings first.").setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show(); return }
  AlertDialog.Builder(this).setTitle("Send ${list.size} separate emails?").setMessage("From: ${from.text}\nSubject: ${subject.text}\n\n${list.take(12).joinToString("\n")}\n\nThe bot will tap Send for each recipient.").setPositiveButton("Start sending") { _, _ -> SendQueue.start(this, from.text.toString().trim(), list, subject.text.toString(), body.text.toString()); update() }.setNegativeButton("Back", null).show()
 }
 private fun update() { if (::status.isInitialized) status.text = "Automation: ${if (GmailAutomation.instance != null) "enabled" else "disabled"}\n" + SendQueue.summary(this) }
 override fun onResume() { super.onResume(); handler.post(tick) }
 override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
 @Deprecated("Android activity result") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode == 10 && resultCode == RESULT_OK) try {
   val uri = data?.data ?: return
   val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(1000000) } ?: return
   val found = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.distinct().toList()
   recipients.setText((recipients.text.toString() + "\n" + found.joinToString("\n")).trim()); Toast.makeText(this, "Imported ${found.size} addresses", Toast.LENGTH_LONG).show()
  } catch (_: Exception) { Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show() }
 }
}
