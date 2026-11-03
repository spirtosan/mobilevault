package com.spirtosan.mobilevault.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

/**
 * What `mvault-admin pair <name>` prints (and shows as a QR code):
 * `{"t":"mvault-pair","v":1,"url":"https://…/api/v1","code":"ABCDE23456","pins":["sha256/…"]}`.
 * The server URL and pins come only from here, never from the app's code.
 */
data class PairingPayload(val url: String, val code: String, val pins: List<String>) {
    val host: String get() = URI(url).host

    class Invalid(message: String) : Exception(message)

    companion object {
        private val CODE = Regex("^[A-HJ-NP-Z2-9]{10}$")
        /** OkHttp CertificatePinner format: base64 of the SHA-256 of the SubjectPublicKeyInfo. */
        val PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

        const val NO_PINS_TEXT = "This pairing code has no server key pins, so the app can't check that it is talking to your " +
            "server. Check \"server_cert\" and \"backup_pins\" in the server's config, then run mvault-admin pair again."

        /**
         * `mvault-admin pair` always includes pins when the server is set up (code review F6): a code without
         * pins means something is wrong. Release builds refuse it; debug builds ask for confirmation.
         */
        fun allowWithoutPins(debugBuild: Boolean) = debugBuild

        /** Accepts the bare JSON, or a pasted line around it (e.g. `Payload: {…}`). */
        fun parse(text: String): PairingPayload {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end < start) throw Invalid("This is not a pairing code from mvault-admin.")
            val o = try { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject } catch (e: Exception) {
                throw Invalid("This is not a pairing code from mvault-admin.")
            }
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (str("t") != "mvault-pair") throw Invalid("This is not a pairing code from mvault-admin.")
            if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) throw Invalid("Unsupported pairing code version. Update the app.")
            val url = (str("url") ?: "").trimEnd('/')
            val uri = try { URI(url) } catch (e: Exception) { throw Invalid("The server address in the pairing code is invalid.") }
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null)
                throw Invalid("The server address must be an https:// URL.")
            val code = (str("code") ?: "").replace("-", "").uppercase()
            if (!CODE.matches(code)) throw Invalid("The one-time code in the pairing text is invalid.")
            val pins = when (val p = o["pins"]) {
                null -> emptyList()
                is JsonArray -> p.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                else -> throw Invalid("The server pins in the pairing code are invalid.")
            }
            if (pins.any { !PIN.matches(it) }) throw Invalid("The server pins in the pairing code are invalid.")
            return PairingPayload(url, code, pins.distinct())
        }
    }
}
