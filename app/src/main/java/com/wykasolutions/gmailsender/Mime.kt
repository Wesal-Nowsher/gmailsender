package com.wykasolutions.gmailsender

import android.util.Base64

/** Builds the RFC 5322 message that Gmail sends, with full UTF-8 support. */
object Mime {

    fun build(from: String, to: String, subject: String, body: String): String {
        val encodedBody = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return buildString {
            append("From: ").append(from).append("\r\n")
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(header(subject)).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(wrap(encodedBody))
        }
    }

    /** Subjects may contain non-ASCII text, which headers must carry encoded. */
    private fun header(subject: String): String {
        val clean = subject.replace("\r", " ").replace("\n", " ")
        if (clean.all { it.code in 32..126 }) return clean
        val encoded = Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "=?UTF-8?B?$encoded?="
    }

    private fun wrap(encoded: String): String =
        encoded.chunked(76).joinToString("\r\n")
}
