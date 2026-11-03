package com.spirtosan.mobilevault.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The pairing (public data only: the token is encrypted separately, the key lives in Keystore). */
data class BackupConfig(
    val url: String,
    val pins: List<String>,
    val device: String,
    val keyAlias: String,
    val pairedAt: Long,
    val strongBox: Boolean,
) {
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url

    fun toJson() = JsonObject(mapOf(
        "url" to JsonPrimitive(url), "pins" to JsonArray(pins.map { JsonPrimitive(it) }),
        "device" to JsonPrimitive(device), "key_alias" to JsonPrimitive(keyAlias),
        "paired_at" to JsonPrimitive(pairedAt), "strongbox" to JsonPrimitive(strongBox),
    ))

    companion object {
        fun fromJson(o: JsonObject) = BackupConfig(
            url = o.str("url")!!, pins = (o["pins"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            device = o.str("device") ?: "", keyAlias = o.str("key_alias")!!,
            pairedAt = (o["paired_at"] as? JsonPrimitive)?.longOrNull ?: 0, strongBox = o.bool("strongbox"),
        )
    }
}

/** What the last upload / check did. Kept across restarts; no secrets. */
data class BackupStatus(
    /** Wall-clock ms of the last verified upload, 0 = never. */
    val lastSuccessAt: Long = 0,
    /** Highest rev known to be on the server (uploaded and verified, or found there with our sha256). */
    val lastSuccessRev: Long = 0,
    val lastSuccessSha: String = "",
    /** Last failure shown to the owner; cleared by the next success. */
    val lastError: String? = null,
    /** A permanent failure (no automatic retry until something changes): too large, refused file, pins, TLS. */
    val stopped: Boolean = false,
    /** 401/403: revoked or unknown on the server. Cleared by pairing again. */
    val disconnected: Boolean = false,
    /** The server has a different file for our rev: the next unlock re-seals with a higher rev. */
    val needsNewRev: Boolean = false,
    /** Wall-clock ms and result of the last "test restore". */
    val testRestoreAt: Long = 0,
    val testRestoreText: String? = null,
    /** TLS failures (not pin mismatches) in a row; reset by any success. */
    val tlsFailures: Int = 0,
) {
    fun toJson() = JsonObject(buildMap {
        put("last_success_at", JsonPrimitive(lastSuccessAt)); put("last_success_rev", JsonPrimitive(lastSuccessRev))
        put("last_success_sha", JsonPrimitive(lastSuccessSha))
        lastError?.let { put("last_error", JsonPrimitive(it)) }
        put("stopped", JsonPrimitive(stopped)); put("disconnected", JsonPrimitive(disconnected))
        put("needs_new_rev", JsonPrimitive(needsNewRev)); put("test_restore_at", JsonPrimitive(testRestoreAt))
        testRestoreText?.let { put("test_restore_text", JsonPrimitive(it)) }
        put("tls_failures", JsonPrimitive(tlsFailures))
    })

    companion object {
        /** From this many TLS failures in a row the chip shows "Backup error". */
        const val TLS_RED = 3

        fun fromJson(o: JsonObject) = BackupStatus(
            lastSuccessAt = o.long("last_success_at"), lastSuccessRev = o.long("last_success_rev"),
            lastSuccessSha = o.str("last_success_sha") ?: "",
            lastError = o.str("last_error"), stopped = o.bool("stopped"), disconnected = o.bool("disconnected"),
            needsNewRev = o.bool("needs_new_rev"), testRestoreAt = o.long("test_restore_at"), testRestoreText = o.str("test_restore_text"),
            tlsFailures = o.long("tls_failures").toInt(),
        )
    }
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

/** Small JSON files in app-private storage, written atomically (tmp + fsync + rename). */
internal object JsonFiles {
    fun read(f: File): JsonObject? = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()

    fun write(f: File, o: JsonObject) = writeBytes(f, o.toString().toByteArray())

    fun writeBytes(f: File, b: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(b); it.fd.sync() }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
