package com.spirtosan.mobilevault.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One stored revision on the server (`GET …/revs`, `…/latest`, the 201 answer of a PUT). */
data class RemoteRev(val rev: Long, val size: Long, val sha256: String, val received: String, val device: String = "") {
    companion object {
        fun fromJson(o: JsonObject) = RemoteRev(
            rev = (o["rev"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("rev"),
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            sha256 = (o["sha256"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "",
            received = (o["received"] as? JsonPrimitive)?.contentOrNull ?: "",
            device = (o["device"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
    }
}

sealed interface UploadResult {
    data class Stored(val meta: RemoteRev) : UploadResult
    /** 409: the server already has this rev (never overwritten). */
    data object Exists : UploadResult
}

/**
 * A remote, append-only backup destination (docs/BACKUP_AND_RECOVERY.md). There is no delete:
 * retention is the server's job. All calls throw [BackupException].
 */
interface BackupTarget {
    suspend fun health()
    suspend fun upload(vaultId: String, rev: Long, bytes: ByteArray): UploadResult
    /** Oldest first, as the server sends them. */
    suspend fun list(vaultId: String): List<RemoteRev>
    /** Checks the server's `X-Content-SHA256` against the bytes. */
    suspend fun download(vaultId: String, rev: Long): ByteArray
    /** Highest rev, or null if the server has none for this vault. */
    suspend fun latest(vaultId: String): RemoteRev?
}
