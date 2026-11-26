package com.wykasolutions.gmailsender

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
 private lateinit var from: EditText
 private lateinit var recipients: EditText
 private lateinit var subject: EditText
 private lateinit var body: EditText
 private lateinit var status: TextView
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = 1; setPadding(24, 24, 24, 24) }
  fun field(hint: String, lines: Int = 1) = EditText(this).also { it.hint = hint; it.minLines = lines; layout.addView(it) }
  layout.addView(TextView(this).apply { text = "Gmail sender"; textSize = 25f })
  from = field("Exact sending Gmail address")
  recipients = field("Recipient emails, separated by commas or lines", 5)
  subject = field("Subject")
  body = field("Message", 6)
  status = TextView(this).apply { textSize = 17f; layout.addView(this) }
  layout.addView(Button(this).apply { text = "Enable screen automation"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
  layout.addView(Button(this).apply { text = "Start queue"; setOnClickListener {
   if (GmailAutomation.instance == null) { status.text = "Enable screen automation first."; return@setOnClickListener }
   val regex = Regex("^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
   val list = recipients.text.toString().split(Regex("[,;\\s]+")).map { it.trim() }.filter { regex.matches(it) }.distinctBy { it.lowercase() }
   if (!regex.matches(from.text.toString().trim()) || list.isEmpty() || subject.text.isBlank() || body.text.isBlank()) { status.text = "Enter sender, valid recipients, subject and message."; return@setOnClickListener }
   SendQueue.start(this@MainActivity, from.text.toString().trim(), list, subject.text.toString(), body.text.toString()); update()
  } })
  layout.addView(Button(this).apply { text = "Pause"; setOnClickListener { SendQueue.setState(this@MainActivity, "paused", "Paused by user"); update() } })
  layout.addView(Button(this).apply { text = "Resume"; setOnClickListener { SendQueue.resume(this@MainActivity); update() } })
  layout.addView(Button(this).apply { text = "I verified uncertain message was sent"; setOnClickListener { SendQueue.resolve(this@MainActivity, true); update() } })
  layout.addView(Button(this).apply { text = "Retry uncertain message"; setOnClickListener { SendQueue.resolve(this@MainActivity, false); update() } })
  setContentView(ScrollView(this).apply { addView(layout) }); update()
 }
 private fun update() { status.text = SendQueue.summary(this) }
 override fun onResume() { super.onResume(); if (::status.isInitialized) update() }
}
