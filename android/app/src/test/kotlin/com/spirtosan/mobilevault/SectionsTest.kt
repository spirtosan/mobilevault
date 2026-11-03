package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.Pvault1
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.data.Section
import com.spirtosan.mobilevault.data.SectionFilter
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.data.VaultEdits.Sort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Task 10 Part A: sections in one vault. */
class SectionsTest {
    private val fast = Argon2Params(mKiB = 32768, t = 2, p = 4)
    private val t = "2026-11-03T10:00:00Z"

    private fun e(s: String, u: String = "me", p: String = "pw-$s", sec: String = "") =
        Entry(service = s, username = u, password = p, section = sec)

    private fun twoSections(): Triple<VaultPayload, Section, Section> {
        val (p1, personal) = VaultEdits.addSection(VaultPayload(), "Personal")
        val (p2, work) = VaultEdits.addSection(p1, "Work")
        return Triple(p2.copy(entries = listOf(e("Bank", sec = personal.id), e("Jira", sec = work.id), e("Loose"))), personal, work)
    }

    private inline fun <reified T : Throwable> throws(block: () -> Unit) {
        try { block(); fail("expected ${T::class.simpleName}") } catch (x: Throwable) { if (x !is T) throw x }
    }

    @Test fun roundTripWithAndWithoutSections() {
        val (p, personal, work) = twoSections()
        val c = Pvault2.create("pw".toCharArray(), "t", kdf = fast, payload = p)
        val back = Pvault2.unlockWithPassword(c.bytes, "pw".toCharArray()).payload
        assertEquals(listOf(personal, work), VaultEdits.sections(back))
        assertEquals(listOf(personal.id, work.id, ""), back.entries.map { it.section })

        // a vault without sections: no "sections" key, nothing changes for the owner
        val plain = Pvault2.create("pw".toCharArray(), "t", kdf = fast, payload = VaultPayload(listOf(e("A")))).vault
        assertTrue(VaultEdits.sections(plain.payload).isEmpty())
        assertFalse(VaultEdits.SECTIONS_KEY in plain.payload.settings)
        assertEquals(listOf("A"), VaultEdits.visible(plain.payload, "", Sort.AZ).map { it.service })
    }

    @Test fun unknownFieldsInSettingsAndSectionsSurvive() {
        val settings = Json.parseToJsonElement(
            """{"clipboard_timeout":30,"sections":[{"id":"s1","name":"Work","color":"blue"},{"bad":1},"junk"],"future":true}"""
        ).jsonObject
        val v = Pvault2.create("pw".toCharArray(), "t", kdf = fast,
            payload = VaultPayload(entries = listOf(e("x", sec = "s1")), settings = settings)).vault
        assertEquals(listOf("Work"), VaultEdits.sections(v.payload).map { it.name })
        // renaming keeps the section's unknown keys and the other settings
        v.payload = VaultEdits.renameSection(v.payload, "s1", "Job")
        val back = Pvault2.unlockWithPassword(v.seal("t"), "pw".toCharArray()).payload
        val sec = (back.settings["sections"] as JsonArray)[0] as JsonObject
        assertEquals("Job", (sec["name"] as JsonPrimitive).content)
        assertEquals("blue", (sec["color"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(true), back.settings["future"])
        assertEquals(JsonPrimitive(30), back.settings["clipboard_timeout"])
        assertEquals("s1", back.entries.single().section)
    }

    @Test fun unknownSectionIdShowsUnderNoSection() {
        val (p, personal, _) = twoSections()
        val q = p.copy(entries = p.entries + e("Ghost", sec = "not-a-section"))
        assertEquals(listOf("Ghost", "Loose"), VaultEdits.visible(q, "", Sort.AZ, SectionFilter.None).map { it.service })
        assertEquals(listOf("Bank"), VaultEdits.visible(q, "", Sort.AZ, SectionFilter.Only(personal.id)).map { it.service })
        assertEquals(4, VaultEdits.visible(q, "", Sort.AZ).size)
        assertEquals(2, VaultEdits.countBySection(q)[""])
    }

    @Test fun searchLooksAcrossAllSections() {
        val (p, personal, _) = twoSections()
        assertEquals(listOf("Jira"), VaultEdits.visible(p, "jira", Sort.AZ, SectionFilter.Only(personal.id)).map { it.service })
    }

    @Test fun addRenameReorder() {
        val (p, personal, work) = twoSections()
        throws<IllegalArgumentException> { VaultEdits.addSection(p, " work ") }        // duplicate, any case
        throws<IllegalArgumentException> { VaultEdits.addSection(p, "  ") }
        throws<IllegalArgumentException> { VaultEdits.renameSection(p, personal.id, "Work") }
        assertEquals("Private", VaultEdits.sectionName(VaultEdits.renameSection(p, personal.id, " Private "), personal.id))
        assertEquals(listOf(work.id, personal.id), VaultEdits.sections(VaultEdits.moveSection(p, work.id, -1)).map { it.id })
        assertEquals(listOf(personal.id, work.id), VaultEdits.sections(VaultEdits.moveSection(p, work.id, +5)).map { it.id })
    }

    @Test fun deleteSectionNeverDeletesEntries() {
        val (p0, personal, work) = twoSections()
        val old = e("OldWork", sec = work.id)
        val p = VaultEdits.delete(p0.copy(entries = p0.entries + old), old.id, "2026-01-01T00:00:00Z")   // a tombstone in Work
        // move to another section
        val a = VaultEdits.deleteSection(p, work.id, moveTo = personal.id, time = t)
        assertEquals(listOf(personal.id), VaultEdits.sections(a).map { it.id })
        assertEquals(p.entries.size, a.entries.size)
        assertEquals(3, a.activeEntries.size)
        assertEquals(listOf("Bank", "Jira"), VaultEdits.visible(a, "", Sort.AZ, SectionFilter.Only(personal.id)).map { it.service })
        assertEquals(t, a.entries.first { it.service == "Jira" }.modified)
        assertEquals("2026-01-01T00:00:00Z", a.entries.first { it.id == old.id }.modified)   // tombstone keeps its time
        assertTrue(a.entries.none { it.section == work.id })
        // move to "No section"
        val b = VaultEdits.deleteSection(p, work.id, time = t)
        assertEquals(listOf("Jira", "Loose"), VaultEdits.visible(b, "", Sort.AZ, SectionFilter.None).map { it.service })
        throws<IllegalArgumentException> { VaultEdits.deleteSection(p, work.id, moveTo = work.id) }
        throws<IllegalArgumentException> { VaultEdits.deleteSection(p, work.id, moveTo = "nope") }
    }

    @Test fun moveEntries() {
        val (p, personal, work) = twoSections()
        val loose = p.entries.first { it.service == "Loose" }
        val m = VaultEdits.moveEntries(p, setOf(loose.id), work.id, t)
        assertEquals(work.id, m.entries.first { it.id == loose.id }.section)
        assertEquals(t, m.entries.first { it.id == loose.id }.modified)
        assertEquals("", VaultEdits.moveEntries(m, setOf(loose.id), "", t).entries.first { it.id == loose.id }.section)
        throws<IllegalArgumentException> { VaultEdits.moveEntries(p, setOf(loose.id), "nope") }
        assertEquals(personal.id, m.entries.first { it.service == "Bank" }.section)
    }

    @Test fun oldFilesWithoutSectionFields() {
        val desktop = Pvault1.encrypt("x".toCharArray(), VaultPayload(listOf(e("A"), e("B"))))
        val p = Pvault1.decrypt("x".toCharArray(), desktop)
        assertTrue(VaultEdits.sections(p).isEmpty())
        assertEquals(2, VaultEdits.visible(p, "", Sort.AZ, SectionFilter.None).size)
    }

    // ── import into a section (Part C logic lives in VaultEdits too) ──
    @Test fun mergeIntoSectionSkipsDuplicatesOnlyWithinThatSection() {
        val (p, personal, work) = twoSections()
        val incoming = listOf(
            e("Bank").copy(notes = "n", tags = listOf("t"), favourite = true),   // same as Personal/Bank, but goes to Work
            e("Jira"),                                                             // duplicate of Work/Jira
            e("Jira"),                                                             // duplicate inside the import
            e("New").copy(deleted = true),                                         // tombstones are not imported
        )
        val r = VaultEdits.mergeImport(p, incoming, t, section = work.id)
        assertEquals(1, r.added)
        assertEquals(2, r.skipped)
        val bank = r.payload.entries.last()
        assertEquals(work.id, bank.section)
        assertEquals("n", bank.notes); assertEquals(listOf("t"), bank.tags); assertTrue(bank.favourite)
        assertFalse(bank.id == incoming[0].id)                                 // new id
        // the same import into Personal skips Bank instead
        val r2 = VaultEdits.mergeImport(p, incoming, t, section = personal.id)
        assertEquals(1, r2.added)                                              // Jira is new in Personal
        assertEquals(2, r2.skipped)                                            // Bank (in Personal) + the 2nd Jira
    }

    @Test fun keepImportedSectionsWithSuffixOnClash() {
        val (p, _, work) = twoSections()
        val src = listOf(Section("a", "Work"), Section("b", "Travel"))
        val (q, map) = VaultEdits.importSections(p, src)
        assertEquals(listOf("Personal", "Work", "Work (2)", "Travel"), VaultEdits.sections(q).map { it.name })
        val incoming = listOf(e("Jira", sec = "a"), e("Hotel", sec = "b"), e("Other", sec = "zzz"))
        val r = VaultEdits.mergeImport(q, incoming, t) { map[it.section] ?: "" }
        assertEquals(3, r.added)                                               // Jira is new in "Work (2)", not a dup of Work
        assertEquals(listOf(map["a"], map["b"], ""), r.payload.entries.takeLast(3).map { it.section })
        assertTrue(r.payload.entries.none { it.section == work.id && it.service == "Hotel" })
    }

    @Test fun exportOnlyOneSectionIsDesktopCompatible() {
        val (p, _, work) = twoSections()
        val only = VaultEdits.onlySection(p, work.id)
        val back = Pvault1.decrypt("x".toCharArray(), Pvault1.encrypt("x".toCharArray(), only))
        assertEquals(listOf("Jira"), back.entries.map { it.service })
    }
}
