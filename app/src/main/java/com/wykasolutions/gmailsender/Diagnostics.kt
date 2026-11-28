package com.wykasolutions.gmailsender

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes down exactly what the bot could see on the Gmail screen. When a tap
 * lands in the wrong place on a particular phone or Gmail version, this file
 * says why, instead of leaving it to guesswork.
 */
object Diagnostics {

    fun file(c: Context): File = File(c.filesDir, "diagnostics.txt")

    fun save(c: Context, tag: String, nodes: List<AccessibilityNodeInfo>) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("=== $tag at $stamp ===\n")
            append("Gmail screen, ${nodes.size} elements\n\n")
            nodes.take(400).forEach { node ->
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val hint = node.hintText?.toString().orEmpty()
                append(cls)
                if (id.isNotBlank()) append(" #").append(id)
                if (text.isNotBlank()) append(" text=\"").append(text.take(80)).append('"')
                if (desc.isNotBlank()) append(" label=\"").append(desc.take(60)).append('"')
                if (hint.isNotBlank()) append(" hint=\"").append(hint.take(40)).append('"')
                if (node.isClickable) append(" [tappable]")
                if (node.isEditable) append(" [text box]")
                append('\n')
            }
            append("\n")
        }
        runCatching { file(c).appendText(text) }
    }

    fun read(c: Context): String =
        runCatching { file(c).readText() }.getOrElse { "" }

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }
}
