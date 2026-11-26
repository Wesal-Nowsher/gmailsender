package com.wykasolutions.gmailsender

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper

class GmailAutomation : AccessibilityService() {
 companion object { var instance: GmailAutomation? = null }
 private val handler = Handler(Looper.getMainLooper())
 private var startedAt = 0L
 private var clickedAt = 0L
 override fun onServiceConnected() { instance = this; if (SendQueue.current(this).optString("state") == "awaiting") SendQueue.setState(this, "uncertain", "Service restarted after Send. Check Gmail Sent.") }
 override fun onDestroy() { instance = null; super.onDestroy() }
 override fun onInterrupt() { SendQueue.setState(this, "paused", "Accessibility service interrupted") }
 override fun onAccessibilityEvent(event: AccessibilityEvent?) {
  if (event?.packageName?.toString() != "com.google.android.gm") return
  handler.removeCallbacksAndMessages(null)
  handler.postDelayed({ inspect() }, 550)
 }
 private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
  if (root == null) return emptyList()
  val out = mutableListOf(root)
  for (i in 0 until root.childCount) out.addAll(nodes(root.getChild(i)))
  return out
 }
 private fun inspect() {
  val j = SendQueue.current(this); val state = j.optString("state")
  if (state != "composing" && state != "awaiting") return
  val root = rootInActiveWindow ?: run { handler.postDelayed({ inspect() }, 800); return }
  if (root.packageName?.toString() != "com.google.android.gm") { SendQueue.setState(this, if (state == "awaiting") "uncertain" else "paused", "Gmail is no longer visible"); return }
  val all = nodes(root)
  val strings = all.mapNotNull { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()).joinToString(" ") }
  if (state == "awaiting") {
   if (all.any { it.viewIdResourceName?.contains("snackbar_text") == true && (it.text?.toString()?.trim()?.equals("Message sent", true) == true || it.text?.toString()?.trim()?.equals("Sent", true) == true) }) { SendQueue.confirmed(this); return }
   if (System.currentTimeMillis() - clickedAt > 10000) SendQueue.setState(this, "uncertain", "Send tapped but confirmation was not observed. Check Gmail Sent before proceeding.")
   else handler.postDelayed({ inspect() }, 850)
   return
  }
  if (startedAt == 0L) startedAt = System.currentTimeMillis()
  val recipient = j.getJSONArray("recipients").getString(j.optInt("index"))
  val from = j.optString("from")
  val recipientVisible = strings.any { it.contains(recipient, true) }
  val senderVisible = strings.any { it.contains(from, true) }
  val subjectVisible = strings.any { it == j.optString("subject") }
  val bodyVisible = strings.any { it == j.optString("body") }
  val send = all.firstOrNull { (it.contentDescription?.toString()?.equals("Send", true) == true || it.text?.toString()?.equals("Send", true) == true) && it.isClickable }
  if (recipientVisible && senderVisible && subjectVisible && bodyVisible && send != null) {
   SendQueue.setState(this, "awaiting"); clickedAt = System.currentTimeMillis(); startedAt = 0L
   if (!send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) SendQueue.setState(this, "uncertain", "Could not tap Send. Check the draft.")
   else handler.postDelayed({ inspect() }, 850)
  } else if (System.currentTimeMillis() - startedAt > 12000) {
   startedAt = 0L
   SendQueue.setState(this, "paused", "Could not verify sender, recipient, subject, message and Send button. Check Gmail screen and resume.")
  } else handler.postDelayed({ inspect() }, 800)
 }
}
