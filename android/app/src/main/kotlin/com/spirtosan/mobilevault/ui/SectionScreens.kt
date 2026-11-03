package com.spirtosan.mobilevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.data.Section
import com.spirtosan.mobilevault.data.SectionFilter
import com.spirtosan.mobilevault.data.VaultEdits

/** Sections in one vault (Task 10). Nothing here is shown while the vault has no sections, except Settings → Sections. */

/** Display name of an effective section id ("" = No section). */
internal fun sectionLabel(sections: List<Section>, id: String): String =
    sections.firstOrNull { it.id == id }?.name ?: "No section"

/** The chip row on the vault list: All · Personal · Work · … (· No section, only if such entries exist). */
@Composable
internal fun SectionChips(vm: AppViewModel, p: VaultPayload, sections: List<Section>, selected: SectionFilter) {
    val counts = remember(p) { VaultEdits.countBySection(p) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("sectionChips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionChip("All", selected == SectionFilter.All, "chip:all") { vm.setListSection(SectionFilter.All) }
        sections.forEach { s ->
            SectionChip(s.name, selected == SectionFilter.Only(s.id), "chip:${s.name}") { vm.setListSection(SectionFilter.Only(s.id)) }
        }
        if ((counts[""] ?: 0) > 0 || selected == SectionFilter.None)
            SectionChip("No section", selected == SectionFilter.None, "chip:none") { vm.setListSection(SectionFilter.None) }
    }
}

@Composable
private fun SectionChip(text: String, on: Boolean, tag: String, onClick: () -> Unit) = FilterChip(
    selected = on, onClick = onClick, label = { Text(text) }, modifier = Modifier.testTag(tag),
    colors = FilterChipDefaults.filterChipColors(
        containerColor = Palette.bgPanel, labelColor = Palette.fgPrimary,
        selectedContainerColor = Palette.orange, selectedLabelColor = Palette.onOrange,
    ),
)

/**
 * A dropdown to pick a section ("" = No section). [extra]: additional choices before the sections,
 * e.g. ("new" → "New section…") on the import screen; their keys are passed to [onPick] as is.
 */
@Composable
internal fun SectionPicker(
    label: String,
    sections: List<Section>,
    value: String,
    onPick: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
    extra: List<Pair<String, String>> = emptyList(),
    exclude: Set<String> = emptySet(),
) {
    var open by remember { mutableStateOf(false) }
    val choices = extra + sections.filter { it.id !in exclude }.map { it.id to it.name } + (if (allowNone) listOf("" to "No section") else emptyList())
    val shown = choices.firstOrNull { it.first == value }?.second ?: "No section"
    Column(modifier) {
        Text(label, color = Palette.fgSecondary, style = MaterialTheme.typography.labelMedium)
        Box {
            Row(
                Modifier.fillMaxWidth().background(Palette.bgInput, RoundedCornerShape(10.dp)).clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shown, color = Palette.fgPrimary, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null, tint = Palette.fgSecondary)
            }
            DropdownMenu(open, { open = false }) {
                choices.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) },
                        modifier = Modifier.testTag("$tag:${if (id.isEmpty()) "none" else name}"))
                }
            }
        }
    }
}

/** Settings → Sections: add, rename, reorder, delete (entries are moved, never deleted). */
@Composable
internal fun SectionsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val payload by vm.payload.collectAsState()
    val p = payload ?: return
    val sections = VaultEdits.sections(p)
    val counts = VaultEdits.countBySection(p)
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Section?>(null) }
    var deleting by remember { mutableStateOf<Section?>(null) }
    Page("Sections", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Muted("Sections split one vault, e.g. into Personal and Work. One master password, one recovery key, one backup. " +
            "A search always looks in all sections.")
        if (sections.isEmpty()) Muted("No sections yet: the vault list looks as before.")
        sections.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().background(Palette.bgPanel, RoundedCornerShape(10.dp)).padding(start = 14.dp)
                    .testTag("section:${s.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = Palette.fgPrimary, fontWeight = FontWeight.Bold)
                    Muted("${counts[s.id] ?: 0} entries")
                }
                IconButton({ vm.moveSection(s.id, -1) }, enabled = i > 0 && !vm.busy) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = Palette.fgSecondary) }
                IconButton({ vm.moveSection(s.id, +1) }, enabled = i < sections.lastIndex && !vm.busy) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = Palette.fgSecondary) }
                IconButton({ renaming = s }, Modifier.testTag("rename:${s.name}")) { Icon(Icons.Filled.Edit, "Rename", tint = Palette.blue) }
                IconButton({ deleting = s }, Modifier.testTag("deleteSection:${s.name}")) { Icon(Icons.Filled.Delete, "Delete", tint = Palette.red) }
            }
        }
        if ((counts[""] ?: 0) > 0 && sections.isNotEmpty()) Muted("No section: ${counts[""]} entries")
        PanelCard(title = "New section") {
            PlainField(newName, { newName = it }, "Name", tag = "newSectionName")
            ColorButton("Add section", { vm.addSection(newName); newName = "" }, Palette.green, Modifier.fillMaxWidth().testTag("addSection"),
                Icons.Filled.Add, enabled = newName.isNotBlank() && !vm.busy)
        }
    }
    renaming?.let { s ->
        var name by remember(s) { mutableStateOf(s.name) }
        ConfirmDialog("Rename section", "New name for \"${s.name}\":", confirm = "Rename", confirmTag = "renameOk",
            confirmEnabled = name.isNotBlank(), onConfirm = { renaming = null; vm.renameSection(s.id, name) }, onDismiss = { renaming = null },
        ) { PlainField(name, { name = it }, "Name", tag = "renameField") }
    }
    deleting?.let { s ->
        val n = counts[s.id] ?: 0
        var moveTo by remember(s) { mutableStateOf("") }
        ConfirmDialog(
            "Delete section \"${s.name}\"?",
            if (n == 0) "The section is empty." else "Its $n entries are not deleted. Move them to:",
            confirm = "Delete section", confirmColor = Palette.red, confirmTag = "deleteSectionOk",
            onConfirm = { deleting = null; vm.deleteSection(s.id, moveTo) }, onDismiss = { deleting = null },
        ) {
            if (n > 0) {
                (sections.filter { it.id != s.id }.map { it.id to it.name } + ("" to "No section")).forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable { moveTo = id }.testTag("moveTo:${if (id.isEmpty()) "none" else name}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(moveTo == id, { moveTo = id }, colors = RadioButtonDefaults.colors(selectedColor = Palette.orange))
                        Text(name, color = Palette.fgPrimary)
                    }
                }
            }
        }
    }
}

/** Vault list overflow menu while there are no sections: "Split into sections (e.g. Personal / Work)". */
@Composable
internal fun SplitIntoSectionsDialog(vm: AppViewModel, entries: Int, onDone: () -> Unit) {
    var moveAll by remember { mutableStateOf(entries > 0) }
    ConfirmDialog(
        "Split into sections?",
        "Creates the sections Personal and Work. You can rename, add or delete sections in Settings → Sections.",
        confirm = "Create sections", confirmTag = "splitOk",
        onConfirm = { onDone(); vm.splitIntoSections(moveAll) }, onDismiss = onDone,
    ) {
        if (entries > 0) Row(Modifier.clickable { moveAll = !moveAll }.testTag("splitMoveAll"), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(moveAll, { moveAll = it },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Palette.orange, checkmarkColor = Palette.onOrange))
            Spacer(Modifier.width(4.dp))
            Text("Move all $entries entries into Personal", color = Palette.fgPrimary)
        }
    }
}

/** A small grey section label under an entry in the list ("All" selected or while searching). */
@Composable
internal fun SectionTag(name: String) =
    Text(name, color = Palette.fgMuted, style = MaterialTheme.typography.labelSmall)
