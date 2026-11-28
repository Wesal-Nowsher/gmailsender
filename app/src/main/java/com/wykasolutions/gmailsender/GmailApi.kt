package com.wykasolutions.gmailsender

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends one message through the Gmail REST API as the chosen device account.
 *
 * The OAuth token comes from Google Play services for an account the owner has
 * already signed into on this phone, so no password is ever typed or stored.
 */
object GmailApi {

    const val SCOPE = "oauth2:https://www.googleapis.com/auth/gmail.send"
    private const val ENDPOINT = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"

    /** Result of trying to send a single message. */
    sealed class Result {
        object Sent : Result()
        /** The owner must tap Allow once for this account; [intent] shows that screen. */
        data class NeedsConsent(val intent: Intent?) : Result()
        /** Worth trying again later: network trouble or Gmail rate limiting. */
        data class Retryable(val message: String) : Result()
        /** Will not succeed on retry: bad address, revoked access, over quota. */
        data class Permanent(val message: String) : Result()
    }

    /**
     * Fetches an access token for [email]. Must be called off the main thread.
     * [invalidateFirst] drops a cached token that the API has already rejected.
     */
    private fun token(c: Context, email: String, invalidateFirst: Boolean): String {
        val account = Account(email, "com.google")
        if (invalidateFirst) {
            runCatching { GoogleAuthUtil.getToken(c, account, SCOPE) }
                .onSuccess { runCatching { GoogleAuthUtil.clearToken(c, it) } }
        }
        return GoogleAuthUtil.getToken(c, account, SCOPE)
    }

    /**
     * Confirms the app may send as [email], triggering the one-time consent
     * screen if it has not been granted yet. Must run off the main thread.
     */
    fun authorise(c: Context, email: String): Result = try {
        token(c, email, false)
        Result.Sent
    } catch (e: UserRecoverableAuthException) {
        Result.NeedsConsent(e.intent)
    } catch (e: IOException) {
        Result.Retryable(e.message ?: "No network")
    } catch (e: GoogleAuthException) {
        Result.Permanent(e.message ?: "Google rejected this account")
    }

    fun send(c: Context, from: String, to: String, subject: String, body: String): Result {
        val raw = Base64.encodeToString(
            Mime.build(from, to, subject, body).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val payload = JSONObject().put("raw", raw).toString().toByteArray(Charsets.UTF_8)

        var retriedWithFreshToken = false
        while (true) {
            val accessToken = try {
                token(c, from, retriedWithFreshToken)
            } catch (e: UserRecoverableAuthException) {
                return Result.NeedsConsent(e.intent)
            } catch (e: IOException) {
                return Result.Retryable(e.message ?: "No network")
            } catch (e: GoogleAuthException) {
                return Result.Permanent(e.message ?: "Google rejected this account")
            }

            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val code: Int
            val error: String
            try {
                connection.outputStream.use { it.write(payload) }
                code = connection.responseCode
                error = if (code in 200..299) "" else
                    connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(400).orEmpty()
            } catch (e: IOException) {
                connection.disconnect()
                return Result.Retryable(e.message ?: "Connection failed")
            } finally {
                runCatching { connection.inputStream.close() }
                connection.disconnect()
            }

            if (code in 200..299) return Result.Sent

            // A stale cached token: refresh once, then try this message again.
            if (code == 401 && !retriedWithFreshToken) {
                retriedWithFreshToken = true
                continue
            }

            return when (code) {
                401 -> Result.Permanent("Access was revoked for $from. Add the account again.")
                403 -> if (error.contains("limit", true) || error.contains("rateLimit", true))
                    Result.Retryable("Gmail is rate limiting this account")
                else
                    Result.Permanent(describe(403, error))
                429, in 500..599 -> Result.Retryable(describe(code, error))
                else -> Result.Permanent(describe(code, error))
            }
        }
    }

    private fun describe(code: Int, error: String): String {
        val reason = runCatching {
            JSONObject(error).getJSONObject("error").getString("message")
        }.getOrElse { error.ifBlank { "Gmail returned HTTP $code" } }
        return "HTTP $code: $reason"
    }
}
