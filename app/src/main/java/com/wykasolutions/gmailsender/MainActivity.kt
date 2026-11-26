package com.wykasolutions.gmailsender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
 private lateinit var recipients: EditText
 private lateinit var subject: EditText
 private lateinit var body: EditText
 private lateinit var status: TextView
 private lateinit var send: Button
 private var queue = listOf<String>()
 private var index = 0
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(Color.WHITE) }
  val title = TextView(this).apply { text = "Individual Gmail sender"; textSize = 24f; setTextColor(Color.BLACK) }
  root.addView(title)
  fun field(hint: String, lines: Int): EditText = EditText(this).apply { this.hint = hint; minLines = lines; setTextColor(Color.BLACK); setHintTextColor(Color.GRAY); root.addView(this) }
  recipients = field("Recipients: paste emails separated by commas or new lines", 5)
  subject = field("Subject", 1)
  body = field("Message", 6)
  status = TextView(this).apply { text = "Paste addresses, then review the queue."; textSize = 16f; setTextColor(Color.DKGRAY); root.addView(this) }
  val prepare = Button(this).apply { text = "Review recipients"; setOnClickListener { prepareQueue() } }
  root.addView(prepare)
  send = Button(this).apply { text = "Open next in Gmail"; isEnabled = false; setOnClickListener { openNext() } }
  root.addView(send)
  val skip = Button(this).apply { text = "Skip this recipient"; setOnClickListener { if (index < queue.size) { index++; updateStatus() } } }
  root.addView(skip)
  val scroller = ScrollView(this); scroller.addView(root); setContentView(scroller)
 }
 private fun prepareQueue() {
  val regex = Regex("^[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
  val raw = recipients.text.toString().split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
  val valid = raw.filter { regex.matches(it) }.distinctBy { it.lowercase(Locale.ROOT) }
  queue = valid; index = 0
  status.text = "${valid.size} unique valid addresses; ${raw.size-valid.size} invalid or duplicate. Review the list before continuing:\n" + valid.joinToString("\n")
  send.isEnabled = valid.isNotEmpty() && subject.text.isNotBlank() && body.text.isNotBlank()
 }
 private fun updateStatus() { status.text = if (index >= queue.size) "Queue finished." else "Next: ${queue[index]} (${index+1}/${queue.size}). Confirm the account and tap Send in Gmail."; send.isEnabled = index < queue.size }
 private fun openNext() {
  if (index >= queue.size) return
  val address = queue[index]
  val intent = Intent(Intent.ACTION_SENDTO).apply {
   data = Uri.parse("mailto:" + Uri.encode(address))
   setPackage("com.google.android.gm")
   putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
   putExtra(Intent.EXTRA_SUBJECT, subject.text.toString())
   putExtra(Intent.EXTRA_TEXT, body.text.toString())
  }
  try { startActivity(intent); index++; updateStatus() }
  catch (_: Exception) { Toast.makeText(this, "Gmail is unavailable on this device", Toast.LENGTH_LONG).show() }
 }
}
