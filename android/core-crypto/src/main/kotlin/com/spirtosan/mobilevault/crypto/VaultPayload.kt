/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * One credential. Field names on disk keep the desktop v1 short keys (s/u/p/url).
 * Unknown keys written by newer app versions are preserved in [extra] so that an
 * older version never silently drops data.
 */
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val service: String,
    val username: String = "",
    val password: String,
    val url: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val favourite: Boolean = false,
    val created: String = nowIso(),
    val modified: String = created,
    /** Tombstone: kept ~90 days so a merge does not resurrect deleted entries. */
    val deleted: Boolean = false,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    override fun toString() = "Entry(id=$id, service=$service, username=$username, password=***)"

    internal fun toJson(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra)
        put("id", JsonPrimitive(id)); put("s", JsonPrimitive(service)); put("u", JsonPrimitive(username))
        put("p", JsonPrimitive(password)); put("url", JsonPrimitive(url)); put("notes", JsonPrimitive(notes))
        put("tags", JsonArray(tags.map { JsonPrimitive(it) })); put("fav", JsonPrimitive(favourite))
        put("created", JsonPrimitive(created)); put("modified", JsonPrimitive(modified))
        put("deleted", JsonPrimitive(deleted))
    })

    internal companion object {
        private val KNOWN = setOf("id", "s", "u", "p", "url", "notes", "tags", "fav", "created", "modified", "deleted")

        fun fromJson(o: JsonObject, defaultTime: String): Entry {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
            val created = str("created").ifEmpty { defaultTime }
            return Entry(
                id = str("id").ifEmpty { UUID.randomUUID().toString() },
                service = str("s"), username = str("u"), password = str("p"), url = str("url"),
                notes = str("notes"),
                tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                favourite = (o["fav"] as? JsonPrimitive)?.booleanOrNull ?: false,
                created = created,
                modified = str("modified").ifEmpty { created },
                deleted = (o["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                extra = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

/** Decrypted vault content. */
data class VaultPayload(
    val entries: List<Entry> = emptyList(),
    val settings: Map<String, JsonElement> = emptyMap(),
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val activeEntries: List<Entry> get() = entries.filter { !it.deleted }

    override fun toString() = "VaultPayload(${entries.size} entries)"

    internal fun toJsonBytes(): ByteArray = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra)
        put("entries", JsonArray(this@VaultPayload.entries.map { it.toJson() }))
        put("settings", JsonObject(settings))
    }).toString().toByteArray(Charsets.UTF_8)

    internal companion object {
        fun fromJsonBytes(b: ByteArray): VaultPayload {
            val root = try {
                Json.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject
            } catch (e: Exception) {
                throw VaultFormatException("payload is not a JSON object")
            }
            val arr = root["entries"] as? JsonArray ?: throw VaultFormatException("payload has no entries")
            val t = nowIso()
            return VaultPayload(
                entries = arr.map { Entry.fromJson(it.jsonObject, t) },
                settings = (root["settings"] as? JsonObject) ?: emptyMap(),
                extra = root.filterKeys { it != "entries" && it != "settings" },
            )
        }
    }
}
