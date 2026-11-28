package com.wykasolutions.gmailsender

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Helpers for reading and tapping what is on the Gmail screen.
 *
 * Gmail does not promise stable view ids, so everything here matches on what a
 * person would actually see - the visible text, the hint in an empty field, or
 * the spoken label on a button.
 */
object Screen {

    val EMAIL = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)

    /** Every node in the window, parents before children. */
    fun all(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 4000) {
            val node = queue.removeFirst()
            out.add(node)
            guard++
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** Visible text, the spoken label, or the hint shown in an empty field. */
    fun label(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        if (text.isNotBlank()) return text
        val description = node.contentDescription?.toString().orEmpty()
        if (description.isNotBlank()) return description
        return node.hintText?.toString().orEmpty()
    }

    fun describes(node: AccessibilityNodeInfo, vararg words: String): Boolean {
        val haystack = buildString {
            append(node.text?.toString().orEmpty()).append(' ')
            append(node.contentDescription?.toString().orEmpty()).append(' ')
            append(node.hintText?.toString().orEmpty()).append(' ')
            append(node.viewIdResourceName.orEmpty())
        }
        return words.any { haystack.contains(it, ignoreCase = true) }
    }

    fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.className?.toString()?.contains("EditText") == true

    /** The node itself if it is tappable, else the nearest parent that is. */
    fun tappable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun tap(node: AccessibilityNodeInfo?): Boolean =
        tappable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * The compose fields, identified the way a person identifies them.
     *
     * "To" is matched strictly rather than as a substring, because plenty of
     * view ids happen to contain those two letters.
     */
    fun toField(nodes: List<AccessibilityNodeInfo>) =
        nodes.firstOrNull { isEditable(it) && looksLikeTo(it) }

    private fun looksLikeTo(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        if (id.contains("recipient", true) || id.equals("to", true) ||
            id.startsWith("to_", true) || id.endsWith("_to", true)
        ) return true
        return listOfNotNull(node.contentDescription?.toString(), node.hintText?.toString())
            .map { it.trim() }
            .any { it.equals("To", true) || it.startsWith("To ", true) }
    }

    fun subjectField(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull {
        isEditable(it) && describes(it, "Subject")
    }

    fun bodyField(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull {
        isEditable(it) && describes(it, "Compose email", "Message body", "body", "Compose")
    }

    fun sendButton(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull { node ->
        val label = label(node).trim()
        label.equals("Send", true) && tappable(node) != null
    }

    /**
     * The From row: the account address shown in the compose header. It is the
     * address that is not inside a text box the owner types into.
     */
    fun fromRow(nodes: List<AccessibilityNodeInfo>, known: List<String>): AccessibilityNodeInfo? {
        val candidates = nodes.filter { node ->
            !isEditable(node) && EMAIL.containsMatchIn(label(node))
        }
        // Prefer a row Gmail itself marks as the sender, then any known account.
        return candidates.firstOrNull { describes(it, "from") }
            ?: candidates.firstOrNull { node ->
                known.any { label(node).contains(it, true) }
            }
    }

    /** Whether this screen is the compose screen rather than the inbox. */
    fun isCompose(nodes: List<AccessibilityNodeInfo>): Boolean =
        toField(nodes) != null || sendButton(nodes) != null
}
