package com.spirtosan.mobilevault.data

import com.spirtosan.mobilevault.crypto.VaultPayload

/**
 * Import another vault's entries into the open vault (Task 10 Part C): into an existing section,
 * a new section, or "No section"; a Mobile Vault source with sections can keep them.
 * The source file or server vault is never changed.
 */
object ImportInto {
    sealed interface Target {
        data object NoSection : Target
        data class Existing(val id: String) : Target
        data class New(val name: String) : Target
    }

    class Result(val payload: VaultPayload, val added: Int, val skipped: Int, val text: String)

    /**
     * [keepSections]: the source's own sections are added (suffix on name clashes) and its entries keep them;
     * entries without a section in the source go to [target]. Otherwise everything goes to [target].
     * Throws IllegalArgumentException (message for the owner), e.g. for a new section name that already exists.
     */
    fun apply(current: VaultPayload, source: VaultPayload, target: Target, keepSections: Boolean, time: String = VaultEdits.now()): Result {
        var cur = current
        val targetId = when (target) {
            Target.NoSection -> ""
            is Target.Existing -> target.id.also { id ->
                require(VaultEdits.sections(cur).any { it.id == id }) { "That section doesn't exist any more." }
            }
            is Target.New -> VaultEdits.addSection(cur, target.name.trim().ifEmpty { "Imported" }).let { (p, s) -> cur = p; s.id }
        }
        val targetName = if (targetId.isEmpty()) "No section" else VaultEdits.sectionName(cur, targetId) ?: "?"
        val srcSections = VaultEdits.sections(source)
        val keep = keepSections && srcSections.isNotEmpty()
        val map: Map<String, String> = if (keep) VaultEdits.importSections(cur, srcSections).let { (p, m) -> cur = p; m } else emptyMap()
        val srcIds = srcSections.map { it.id }.toSet()
        val r = VaultEdits.mergeImport(cur, source.activeEntries, time) { e ->
            if (keep && e.section in srcIds) map.getValue(e.section) else targetId
        }
        val where = if (keep) "their own sections" + (if (targetId.isNotEmpty()) " and $targetName" else "") else targetName
        val text = "Imported ${r.added} entries into $where" + if (r.skipped > 0) " (skipped ${r.skipped} duplicates)" else ""
        return Result(r.payload, r.added, r.skipped, text)
    }

    /** "work.vault" → "Work", "" → "Imported": the prefilled name of a new section. */
    fun nameFromFile(fileName: String?): String {
        val base = fileName.orEmpty().substringAfterLast('/').substringBeforeLast('.').replace(Regex("[_\\-]+"), " ").trim()
        return base.take(VaultEdits.MAX_NAME).replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Imported" }
    }
}
