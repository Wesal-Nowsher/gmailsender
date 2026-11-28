package com.wykasolutions.gmailsender

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads and taps what is on the Gmail screen.
 *
 * Gmail does not keep its labels or view ids stable between versions, phones
 * and languages, so nothing here depends on a particular wording. Fields are
 * found by where they sit on screen and how they behave, the way a person
 * finds them, with label matching used only as a tie-breaker.
 */
object Screen {

    val EMAIL = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)

    fun all(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 5000) {
            val node = queue.removeFirst()
            out.add(node)
            guard++
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** Visible text, the spoken label, or the hint shown in an empty field. */
    fun label(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.let { if (it.isNotBlank()) return it }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
        return node.hintText?.toString().orEmpty()
    }

    /** Only the text actually typed in, ignoring any hint. */
    fun typedText(node: AccessibilityNodeInfo): String = node.text?.toString().orEmpty()

    fun words(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty()).append(' ')
        append(node.contentDescription?.toString().orEmpty()).append(' ')
        append(node.hintText?.toString().orEmpty()).append(' ')
        append(node.viewIdResourceName.orEmpty())
    }

    fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.className?.toString()?.contains("EditText") == true

    fun bounds(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val r = bounds(node)
        return node.isVisibleToUser && r.width() > 0 && r.height() > 0
    }

    fun tappable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 12) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun tap(node: AccessibilityNodeInfo?): Boolean =
        tappable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun focus(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * The three compose boxes, worked out from the layout rather than from
     * wording: they are the typeable boxes on screen, top to bottom, and the
     * message body is the tall one.
     */
    class Compose(
        val to: AccessibilityNodeInfo?,
        val subject: AccessibilityNodeInfo?,
        val body: AccessibilityNodeInfo?,
        val boxes: Int
    ) {
        val isCompose: Boolean get() = boxes >= 2
    }

    fun compose(nodes: List<AccessibilityNodeInfo>): Compose {
        val boxes = nodes
            .filter { isEditable(it) && isOnScreen(it) }
            .distinctBy { bounds(it).flattenToString() }
            .sortedBy { bounds(it).top }

        if (boxes.isEmpty()) return Compose(null, null, null, 0)

        // Named matches win when Gmail does label its fields.
        fun named(vararg hints: String) = boxes.firstOrNull { node ->
            val w = words(node)
            hints.any { w.contains(it, true) }
        }

        var to = named("to_field", "recipient")
            ?: boxes.firstOrNull { node ->
                listOfNotNull(node.contentDescription?.toString(), node.hintText?.toString())
                    .map { it.trim() }
                    .any { it.equals("To", true) || it.startsWith("To ", true) }
            }
        var subject = named("subject")
        // The body is the tallest box: it is the only one meant for paragraphs.
        var body = named("body", "compose email", "message")
            ?: boxes.maxByOrNull { bounds(it).height() }

        // Fall back to reading position: To, then Subject, then the message.
        val rest = boxes.filter { it != to && it != subject && it != body }
        if (to == null) to = rest.firstOrNull() ?: boxes.firstOrNull { it != body }
        if (subject == null) {
            subject = boxes.filter { it != to && it != body }
                .minByOrNull { bounds(it).top }
        }
        if (body != null && (body == to || body == subject)) body = null

        return Compose(to, subject, body, boxes.size)
    }

    /**
     * The Send control. Gmail draws it as an icon, so it is found by its spoken
     * label or its view id, and as a last resort by being the right-most
     * tappable icon along the top bar.
     */
    fun sendButton(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        nodes.firstOrNull { node ->
            val text = label(node).trim()
            text.equals("Send", true) && tappable(node) != null
        }?.let { return it }

        nodes.firstOrNull { node ->
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            (id == "send" || id.startsWith("send_") || id.endsWith("_send")) &&
                tappable(node) != null
        }?.let { return it }

        // Icons in the top bar, right-most first: on every Gmail build the
        // send arrow is the furthest right control while composing.
        val avoid = listOf("attach", "more", "back", "navigate", "schedule", "discard", "menu")
        val top = nodes.filter { isOnScreen(it) }
            .filter { bounds(it).top < 400 && it.isClickable && it.isEnabled }
            .filter { label(it).length < 24 }
            .filterNot { node -> avoid.any { words(node).contains(it, true) } }
        return top.maxByOrNull { bounds(it).right }
    }

    /**
     * The account the message would be sent from: an address shown on screen
     * that is not inside a box the owner types into.
     */
    fun fromRow(
        nodes: List<AccessibilityNodeInfo>,
        known: List<String>,
        compose: Compose
    ): AccessibilityNodeInfo? {
        val candidates = nodes.filter { node ->
            node != compose.to && !isEditable(node) && isOnScreen(node) &&
                EMAIL.containsMatchIn(label(node))
        }
        return candidates.firstOrNull { words(it).contains("from", true) }
            ?: candidates.firstOrNull { node -> known.any { label(node).contains(it, true) } }
            ?: candidates.minByOrNull { bounds(it).top }
    }
}
