package com.spirtosan.mobilevault.data

import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.VaultPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A section of the vault (Task 10: Personal / Work …). Stored in order in `settings.sections` as
 * `{"id": "<uuid>", "name": "Work"}`; unknown keys of a section object are kept in [extra].
 */
data class Section(val id: String, val name: String, val extra: Map<String, JsonElement> = emptyMap()) {
    internal fun toJson() = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra); put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name))
    })
}

/** Which entries the vault list shows: everything, entries without a section, or one section. */
sealed interface SectionFilter {
    data object All : SectionFilter
    data object None : SectionFilter
    data class Only(val id: String) : SectionFilter
}

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

    // ── sections (Task 10) ──────────────────────────────────────────────────────
    const val SECTIONS_KEY = "sections"
    const val MAX_NAME = 40

    /** The ordered section list from `settings.sections`; malformed items are skipped. */
    fun sections(p: VaultPayload): List<Section> =
        (p.settings[SECTIONS_KEY] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            Section(id, name, o.filterKeys { it != "id" && it != "name" })
        }?.distinctBy { it.id }.orEmpty()

    private fun withSections(p: VaultPayload, list: List<Section>): VaultPayload =
        p.copy(settings = LinkedHashMap(p.settings).apply {
            if (list.isEmpty()) remove(SECTIONS_KEY) else put(SECTIONS_KEY, JsonArray(list.map { it.toJson() }))
        })

    /** The section an entry is shown under: its `sec` if that section exists, otherwise "" (no section). */
    fun effectiveSection(p: VaultPayload, e: Entry, ids: Set<String> = sections(p).map { it.id }.toSet()): String =
        if (e.section in ids) e.section else ""

    fun sectionName(p: VaultPayload, id: String): String? = sections(p).firstOrNull { it.id == id }?.name

    private fun checkName(p: VaultPayload, name: String, exceptId: String? = null): String {
        val n = name.trim()
        require(n.isNotEmpty()) { "Enter a name for the section." }
        require(n.length <= MAX_NAME) { "Use at most $MAX_NAME characters." }
        require(sections(p).none { it.id != exceptId && it.name.equals(n, ignoreCase = true) }) { "A section called \"$n\" already exists." }
        return n
    }

    /** Add a section at the end. Throws IllegalArgumentException (message for the owner) on an empty or duplicate name. */
    fun addSection(p: VaultPayload, name: String, id: String = UUID.randomUUID().toString()): Pair<VaultPayload, Section> {
        val s = Section(id, checkName(p, name))
        return withSections(p, sections(p) + s) to s
    }

    fun renameSection(p: VaultPayload, id: String, name: String): VaultPayload {
        val n = checkName(p, name, exceptId = id)
        return withSections(p, sections(p).map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Move a section up ([delta] < 0) or down in the list. */
    fun moveSection(p: VaultPayload, id: String, delta: Int): VaultPayload {
        val list = sections(p).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return p
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return p
        list.add(j, list.removeAt(i))
        return withSections(p, list)
    }

    /**
     * Delete a section. Its entries are **never** deleted: they move to [moveTo] (another section id, or ""
     * for "No section"). Tombstones move too, so nothing points at a section that no longer exists.
     */
    fun deleteSection(p: VaultPayload, id: String, moveTo: String = "", time: String = now()): VaultPayload {
        require(moveTo != id) { "Choose another section for its entries." }
        val ids = sections(p).map { it.id }.toSet()
        require(moveTo.isEmpty() || moveTo in ids) { "That section doesn't exist." }
        val moved = p.copy(entries = p.entries.map {
            if (it.section == id) it.copy(section = moveTo, modified = if (it.deleted) it.modified else time) else it
        })
        return withSections(moved, sections(p).filter { it.id != id })
    }

    /** Move entries to a section ("" = no section). */
    fun moveEntries(p: VaultPayload, entryIds: Set<String>, sectionId: String, time: String = now()): VaultPayload {
        require(sectionId.isEmpty() || sections(p).any { it.id == sectionId }) { "That section doesn't exist." }
        return p.copy(entries = p.entries.map {
            if (it.id in entryIds && it.section != sectionId) it.copy(section = sectionId, modified = time) else it
        })
    }

    /** Number of active entries per effective section id ("" = no section). */
    fun countBySection(p: VaultPayload): Map<String, Int> {
        val ids = sections(p).map { it.id }.toSet()
        return p.activeEntries.groupingBy { effectiveSection(p, it, ids) }.eachCount()
    }

    /**
     * Add sections from an imported vault ("Keep its sections"). Names that clash get a suffix: "Work (2)".
     * Returns the new payload and a map from the source section ids to the new ids.
     */
    fun importSections(p: VaultPayload, source: List<Section>): Pair<VaultPayload, Map<String, String>> {
        var cur = p
        val map = LinkedHashMap<String, String>()
        for (s in source) {
            val base = s.name.trim().ifEmpty { "Imported" }.take(MAX_NAME - 4)
            var name = base
            var n = 2
            while (sections(cur).any { it.name.equals(name, ignoreCase = true) }) name = "$base (${n++})"
            val (next, added) = addSection(cur, name)
            cur = next
            map[s.id] = added.id
        }
        return cur to map
    }

    // ── import / merge ──────────────────────────────────────────────────────────
    class MergeResult(val payload: VaultPayload, val added: Int, val skipped: Int)

    /**
     * Add imported entries into [section] ("" = no section), skipping exact duplicates (same service +
     * username + password) of active entries **in the same section**. Imported entries get new ids and keep
     * notes, tags and favourites. [sectionFor] can place each entry differently ("Keep its sections").
     */
    fun mergeImport(
        current: VaultPayload,
        imported: List<Entry>,
        time: String = now(),
        section: String = "",
        sectionFor: (Entry) -> String = { section },
    ): MergeResult {
        val ids = sections(current).map { it.id }.toSet()
        val seen = current.activeEntries.map { effectiveSection(current, it, ids) to key(it) }.toMutableSet()
        val add = mutableListOf<Entry>()
        var skipped = 0
        for (e in imported.filter { !it.deleted }) {
            val target = sectionFor(e).takeIf { it in ids } ?: ""
            if (seen.add(target to key(e))) add += e.copy(id = UUID.randomUUID().toString(), section = target, modified = time)
            else skipped++
        }
        return MergeResult(current.copy(entries = current.entries + add), add.size, skipped)
    }

    private fun key(e: Entry) = Triple(e.service, e.username, e.password)

    /** Only the entries of one section ("" = no section), e.g. for a desktop export of that section. */
    fun onlySection(p: VaultPayload, sectionId: String): VaultPayload {
        val ids = sections(p).map { it.id }.toSet()
        return p.copy(entries = p.activeEntries.filter { effectiveSection(p, it, ids) == sectionId })
    }

    enum class Sort { AZ, RECENT }

    /**
     * Active entries matching [query] (service, username or URL), favourites first, then by [sort].
     * [section] filters the list; a search always looks across **all** sections.
     */
    fun visible(p: VaultPayload, query: String, sort: Sort, section: SectionFilter = SectionFilter.All): List<Entry> {
        val q = query.trim()
        val ids = sections(p).map { it.id }.toSet()
        val hits = p.activeEntries.filter {
            if (q.isNotEmpty()) it.service.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true)
            else when (section) {
                SectionFilter.All -> true
                SectionFilter.None -> effectiveSection(p, it, ids).isEmpty()
                is SectionFilter.Only -> effectiveSection(p, it, ids) == section.id
            }
        }
        val bySort = when (sort) {
            Sort.AZ -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.service }
            Sort.RECENT -> compareByDescending { it.modified }
        }
        return hits.sortedWith(compareByDescending<Entry> { it.favourite }.then(bySort))
    }
}
