package com.spirtosan.mobilevault.data

import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.VaultPayload
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Pure edits on a decrypted payload. The caller seals and saves the result. */
object VaultEdits {
    fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** Insert a new entry or replace the one with the same id (modified = now). */
    fun upsert(p: VaultPayload, e: Entry, time: String = now()): VaultPayload {
        val exists = p.entries.any { it.id == e.id }
        return if (exists) p.copy(entries = p.entries.map { if (it.id == e.id) e.copy(modified = time) else it })
        else p.copy(entries = p.entries + e.copy(created = time, modified = time))
    }

    /** Delete = tombstone (deleted = true, modified = now), so a later sync/merge knows it was removed. */
    fun delete(p: VaultPayload, id: String, time: String = now()): VaultPayload =
        p.copy(entries = p.entries.map { if (it.id == id) it.copy(deleted = true, modified = time) else it })

    class MergeResult(val payload: VaultPayload, val added: Int, val skipped: Int)

    /** Add imported entries, skipping exact duplicates (same service + username + password) of active entries. */
    fun mergeImport(current: VaultPayload, imported: List<Entry>, time: String = now()): MergeResult {
        val seen = current.activeEntries.map { key(it) }.toMutableSet()
        val add = mutableListOf<Entry>()
        var skipped = 0
        for (e in imported.filter { !it.deleted }) {
            if (seen.add(key(e))) add += e.copy(modified = time) else skipped++
        }
        return MergeResult(current.copy(entries = current.entries + add), add.size, skipped)
    }

    private fun key(e: Entry) = Triple(e.service, e.username, e.password)

    enum class Sort { AZ, RECENT }

    /** Active entries matching [query] (service, username or URL), favourites first, then by [sort]. */
    fun visible(p: VaultPayload, query: String, sort: Sort): List<Entry> {
        val q = query.trim()
        val hits = p.activeEntries.filter {
            q.isEmpty() || it.service.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true)
        }
        val bySort = when (sort) {
            Sort.AZ -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.service }
            Sort.RECENT -> compareByDescending { it.modified }
        }
        return hits.sortedWith(compareByDescending<Entry> { it.favourite }.then(bySort))
    }
}
