package com.wykasolutions.gmailsender

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object SendQueue {
 private fun read(c: Context) = JSONObject(c.getSharedPreferences("queue", 0).getString("data", "{}") ?: "{}")
 private fun save(c: Context, j: JSONObject) { c.getSharedPreferences("queue", 0).edit().putString("data", j.toString()).apply() }
 fun current(c: Context) = read(c)
 fun setState(c: Context, state: String, reason: String = "") { save(c, read(c).put("state", state).put("reason", reason)) }
 fun start(c: Context, from: String, recipients: List<String>, subject: String, body: String) {
  if (read(c).optString("state") in listOf("ready", "composing", "awaiting", "uncertain")) return
  save(c, JSONObject().put("from", from).put("recipients", JSONArray(recipients)).put("subject", subject).put("body", body).put("index", 0).put("state", "ready")); open(c)
 }
 fun resume(c: Context) { if (read(c).optString("state") == "paused") { setState(c, "ready"); open(c) } }
 fun resolve(c: Context, sent: Boolean) {
  val j = read(c); if (j.optString("state") != "uncertain") return
  if (sent) j.put("index", j.optInt("index") + 1)
  j.put("state", "ready"); save(c, j); open(c)
 }
 fun confirmed(c: Context) {
  val j = read(c); if (j.optString("state") != "awaiting") return
  val next = j.optInt("index") + 1
  j.put("index", next).put("state", if (next >= j.getJSONArray("recipients").length()) "done" else "ready"); save(c, j)
  if (j.optString("state") == "ready") open(c)
 }
 fun open(c: Context) {
  val j = read(c); if (j.optString("state") != "ready") return
  val a = j.optJSONArray("recipients") ?: return; val i = j.optInt("index")
  if (i >= a.length()) { setState(c, "done"); return }
  val address = a.getString(i)
  val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))).apply {
   setPackage("com.google.android.gm"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   putExtra(Intent.EXTRA_EMAIL, arrayOf(address)); putExtra(Intent.EXTRA_SUBJECT, j.optString("subject")); putExtra(Intent.EXTRA_TEXT, j.optString("body"))
  }
  try { setState(c, "composing"); c.startActivity(intent) } catch (e: Exception) { setState(c, "paused", "Cannot open Gmail: ${e.message}") }
 }
 fun summary(c: Context): String {
  val j = read(c); val a = j.optJSONArray("recipients") ?: return "No queue started."
  return "${j.optInt("index")}/${a.length()} confirmed • ${j.optString("state")}\n${j.optString("reason")}"
 }
}
