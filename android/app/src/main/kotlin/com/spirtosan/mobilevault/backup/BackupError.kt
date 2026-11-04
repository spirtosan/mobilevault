package com.spirtosan.mobilevault.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import com.spirtosan.mobilevault.BuildConfig
import com.spirtosan.mobilevault.backup.BackupException.Kind
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** A backup call failed. [text] is shown to the owner as is; it never contains tokens or vault data. */
class BackupException(val kind: Kind, val text: String, val status: Int = 0) : Exception(text) {
    enum class Kind {
        /** No network, DNS, timeout: try again later. */
        NETWORK,
        /** The server's key is not one of the pinned keys. Never send anything. */
        PIN_MISMATCH,
        /**
         * Other TLS failure (dropped handshake, captive portal, certificate chain). Nothing was sent yet,
         * so it is retried; from [BackupStatus.TLS_RED] failures in a row the chip turns red.
         */
        TLS,
        /** Pairing: the one-time code is wrong, expired or used. */
        PAIR_CODE,
        /** 401/403 on an authenticated call: revoked, unknown device or bad token. */
        DISCONNECTED,
        /** 409 this rev exists already. */
        CONFLICT,
        /** 413 file too large. */
        TOO_LARGE,
        /** 400/422 the server refused the request or file. */
        REJECTED,
        NOT_FOUND,
        /** 5xx, 429 and unexpected answers: try again later. */
        SERVER,
        /** The app has no pairing (or its key/token is gone). */
        NOT_PAIRED,
    }

    /** WorkManager should retry with backoff. */
    val retryable get() = kind == Kind.NETWORK || kind == Kind.SERVER || kind == Kind.TLS
}

/**
 * Task 13: texts that name server commands (`mvault-admin …`) are for the admin. Release builds show
 * plain words for the user instead ("Ask the person who runs your backup server …"). UI tests flip it.
 */
object AdminTexts {
    @Volatile var show: Boolean = BuildConfig.DEBUG
}

object BackupErrors {
    const val DISCONNECTED_TEXT = "This phone was disconnected from the server. Connect it again in Settings → Backup."

    /**
     * Wrong / expired / used pairing code. Release builds (Task 13) speak to the user, who has no server access;
     * debug builds name the admin command.
     */
    fun pairCodeText(admin: Boolean = AdminTexts.show) =
        if (admin) "This pairing code is wrong, expired or already used. Run `mvault-admin pair <name>` on the server again."
        else "This pairing QR code is wrong, expired or already used. Ask the person who runs your backup server for a new pairing QR code."

    /** `{"error": "…"}` from the mvault server; null for anything else (e.g. an nginx HTML page). */
    fun serverMessage(body: String?): String? = try {
        (Json.parseToJsonElement(body ?: "").jsonObject["error"] as? JsonPrimitive)?.contentOrNull?.take(200)
    } catch (e: Exception) { null }

    fun fromHttp(status: Int, body: String?, pairing: Boolean = false, admin: Boolean = AdminTexts.show): BackupException {
        val msg = serverMessage(body)
        return when {
            pairing && status == 403 -> BackupException(Kind.PAIR_CODE, pairCodeText(admin), status)
            pairing && status == 429 -> BackupException(Kind.SERVER, "Too many pairing attempts. Wait a minute and try again.", status)
            pairing && status in 400..499 -> BackupException(Kind.REJECTED, "The server refused the pairing" + (msg?.let { ": $it" } ?: " (HTTP $status)."), status)
            status == 401 || status == 403 -> BackupException(Kind.DISCONNECTED, DISCONNECTED_TEXT, status)
            status == 404 -> BackupException(Kind.NOT_FOUND, msg ?: "Not found on the server.", status)
            status == 409 -> BackupException(Kind.CONFLICT, "This revision is already on the server.", status)
            status == 413 -> BackupException(Kind.TOO_LARGE, "The vault file is too large for the server (limit 5 MB).", status)
            status == 400 || status == 422 -> BackupException(Kind.REJECTED, "The server refused the file" + (msg?.let { ": $it" } ?: " (HTTP $status)."), status)
            else -> BackupException(Kind.SERVER, "The server answered with an error (HTTP $status). Trying again later.", status)
        }
    }

    fun fromIo(e: IOException): BackupException {
        return when {
            e is SSLPeerUnverifiedException && e.message.orEmpty().contains("pinning", ignoreCase = true) ->
                BackupException(Kind.PIN_MISMATCH, "This is not your server, or its certificate changed. Nothing was sent.")
            e is SSLPeerUnverifiedException || e is SSLHandshakeException ->
                BackupException(Kind.TLS, "Secure connection to the server failed (${e.javaClass.simpleName}).")
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is InterruptedIOException ->
                BackupException(Kind.NETWORK, "No connection to the server. Check the internet connection.")
            else -> BackupException(Kind.NETWORK, "Connection to the server failed (${e.javaClass.simpleName}).")
        }
    }
}
