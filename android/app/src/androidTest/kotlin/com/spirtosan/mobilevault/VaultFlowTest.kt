package com.spirtosan.mobilevault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.spirtosan.mobilevault.crypto.RecoveryKey
import com.spirtosan.mobilevault.ui.AppViewModel
import com.spirtosan.mobilevault.backup.AdminTexts
import com.spirtosan.mobilevault.backup.RemoteVault
import com.spirtosan.mobilevault.ui.biometricReasonFor
import com.spirtosan.mobilevault.ui.strongBiometricStatus
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end flows on the real UI. Each test wipes the app's vault first (debug app id, not the user's vault). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VaultFlowTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val pw = "Test-master-password-42"

    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t, useUnmergedTree = true)

    /** On timeout the failure shows what was on screen instead (texts and tags). */
    private fun waitFor(t: String, enabled: Boolean = false, ms: Long = 20_000) {
        try {
            rule.waitUntilAtLeastOneExists(if (enabled) hasTestTag(t) and isEnabled() else hasTestTag(t), ms)
        } catch (e: ComposeTimeoutException) {
            val screen = rule.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n---\n") { root ->
                rule.onAllNodes(hasAnyAncestor(isRoot()) or isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                    .filter { it.root == root.root }
                    .mapNotNull { n ->
                        val texts = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
                        if (texts == null && tag == null) null else "[$tag] $texts"
                    }.joinToString("\n")
            }
            throw AssertionError("'$t' not found${if (enabled) " (enabled)" else ""}. Screen:\n$screen", e)
        }
    }

    /**
     * Click through the semantics action instead of an injected touch: right after typing, the keyboard
     * animation (imePadding) can still move the button, and a touch at its old position is lost.
     */
    private fun SemanticsNodeInteraction.clickAction() = performSemanticsAction(SemanticsActions.OnClick)

    private val newPw = "Brand-new-master-password-7"

    private fun unlock(password: String = pw) {
        waitFor("unlockPw")
        tag("unlockPw").performTextInput(password)
        tag("unlock").clickAction()
        waitFor("add")
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileVaultApp

    private fun resetVault() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.filesDir.resolve("vault.pvault").delete()
        ctx.filesDir.resolve("snapshots").deleteRecursively()
        app.session.lock()
        app.backup.disconnect()                                   // never upload test vaults; shows the create step 3
        runBlocking {
            app.appSettings.setKitPending(false); app.appSettings.clearFailures()
            app.appSettings.setListSection(com.spirtosan.mobilevault.data.SectionFilter.All)
        }
    }

    /** Lock the way the screen-off receiver or the background timer does. */
    private fun lockFromOutside() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.session.lock() }
        rule.waitForIdle()
    }

    /** Onboarding → Create → password → recovery-key step. */
    private fun createUntilKeyStep() {
        waitFor("create")
        tag("create").performClick()
        tag("pw").performTextInput(pw)
        tag("pw2").performTextInput(pw)
        waitFor("createSubmit", enabled = true)
        tag("createSubmit").performScrollTo().performClick()
        waitFor("withKey")
    }

    /** … → "Create recovery key" → Emergency Kit. Returns the recovery key as shown (3 lines of 3 groups). */
    private fun createUntilKit(): String {
        createUntilKeyStep()
        tag("withKey").performClick()
        waitFor("recoveryKey")
        return shownKey()
    }

    /** Create flow, step 3 (backup server): "Skip" + confirm. The test app is never paired (see [resetVault]). */
    private fun skipBackupStep() {
        waitFor("backupStepSkip")
        tag("backupStepSkip").clickAction()
        waitFor("backupSkipConfirm")
        tag("backupSkipConfirm").clickAction()
        waitFor("add")
    }

    /** Task 09: "I wrote it down", one tap, no typing test. */
    private fun confirmKit(key: String, newVault: Boolean = true) {
        assertEquals(9, key.split("\n", "-").map { it.trim() }.size)
        tag("kitDone").performScrollTo().clickAction()
        if (newVault) skipBackupStep() else waitFor("add")
    }

    private fun fieldText(t: String) =
        tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun createLockUnlockAddEntry() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())

            tag("lock").performClick()
            unlock()

            tag("add").performClick()
            waitFor("service")
            tag("service").performTextInput("FlowTestService")
            tag("password").performTextInput("s3cret!")
            tag("save").performScrollTo().performClick()
            waitFor("entry:FlowTestService")

            tag("lock").performClick()
            unlock()
            waitFor("entry:FlowTestService")
        }
    }

    /** Forgot password: recovery key → forced new password → lock → unlock with the new password. */
    @Test
    fun recoveryKeyUnlockThenNewPassword() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = createUntilKit()
            confirmKit(key)
            tag("lock").performClick()

            waitFor("toRecovery")
            tag("toRecovery").performClick()
            // typed like from the PDF: two lines, lower case
            val g = key.split("\n", "-").map { it.trim() }
            tag("recoveryInput").performTextInput(g.take(5).joinToString("-").lowercase())
            tag("recoveryInput").performTextInput(" " + g.drop(5).joinToString("-").lowercase())
            tag("recoveryUnlock").clickAction()

            waitFor("pw")
            tag("pw").performTextInput(newPw)
            tag("pw2").performTextInput(newPw)
            waitFor("newPwSave", enabled = true)
            tag("newPwSave").performScrollTo().performClick()
            waitFor("add")

            tag("lock").performClick()
            unlock(newPw)
        }
    }

    /** A lock while nothing is open (screen off, leaving the app) keeps the Recovery screen and the typed key. */
    @Test
    fun lockOnRecoveryScreenKeepsTypedKey() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())
            tag("lock").performClick()
            waitFor("toRecovery")
            tag("toRecovery").performClick()
            tag("recoveryInput").performTextInput("ABCD-EFGH-1234")
            lockFromOutside()
            assertEquals("ABCD-EFGH-1234", fieldText("recoveryInput"))

            // a wrong key shows the parser message inline
            tag("recoveryUnlock").clickAction()
            waitFor("recoveryError")
            tag("recoveryError").assertTextContains("got 12", substring = true)
        }
    }

    /** Typed password on Create survives a lock too. */
    @Test
    fun lockOnCreateScreenKeepsTypedPassword() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("create")
            tag("create").performClick()
            tag("pw").performTextInput(pw)
            lockFromOutside()
            assertTrue(rule.onAllNodes(hasTestTag("pw"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertEquals(pw.length, fieldText("pw").length)
        }
    }

    /**
     * The owner's case: the Emergency Kit was saved, then the app locked before the kit was confirmed.
     * The vault must already be saved with that key, and remind the user that the kit was not confirmed.
     */
    @Test
    fun lockOnKitScreenKeepsVaultAndKeyWorks() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = createUntilKit()
            lockFromOutside()

            waitFor("unlockPw")
            unlock()
            waitFor("kitPending")

            tag("lock").performClick()
            waitFor("toRecovery")
            tag("toRecovery").performClick()
            tag("recoveryInput").performTextInput(key.replace("\n", " "))
            tag("recoveryUnlock").clickAction()
            waitFor("newPwSave")
        }
    }

    // ── Emergency Kit without the typing test (Task 09) ──────────────────────
    /** "I wrote it down": no pending card afterwards, also not after lock + unlock. */
    @Test
    fun kitWrittenDownLeavesNoPendingCard() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())
            gone("kitPending")
            assertEquals(false, runBlocking { app.appSettings.kitPending() })
            tag("lock").performClick()
            unlock()
            gone("kitPending")
        }
    }

    /** A successful PDF save completes the step on its own (the SAF picker is replaced by a file Uri here). */
    @Test
    fun kitPdfSaveCompletesTheStep() {
        resetVault()
        val pdf = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("kit-test.pdf").also { it.delete() }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            createUntilKit()
            sc.onActivity { ViewModelProvider(it)[AppViewModel::class.java].writeKitPdf(Uri.fromFile(pdf)) }
            skipBackupStep()
            gone("kitPending")
            assertTrue(pdf.length() > 1000)
            assertEquals("%PDF", pdf.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        }
        pdf.delete()
    }

    /** Settings → Security → "Check my recovery key": right key ✓, wrong key ✗, nothing changes. */
    @Test
    fun checkMyRecoveryKey() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = createUntilKit()
            confirmKit(key)
            val before = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("vault.pvault").readBytes()
            openSettings()
            tag("checkKey").performScrollTo().clickAction()
            waitFor("checkKeyInput")
            tag("checkKeyInput").performTextInput(key.replace("\n", "-").lowercase())
            tag("checkKeyRun").clickAction()
            waitFor("checkKeyResult")
            tag("checkKeyResult").assertTextContains("opens your vault", substring = true)

            tag("checkKeyInput").performTextClearance()
            tag("checkKeyInput").performTextInput(RecoveryKey.generate().display())    // well-formed, but another key
            tag("checkKeyRun").clickAction()
            waitFor("checkKeyResult")
            tag("checkKeyResult").assertTextContains("doesn't open this vault", substring = true)
            val after = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("vault.pvault").readBytes()
            assertTrue(before.contentEquals(after))
            runBlocking { app.appSettings.clearFailures() }
        }
    }

    // ── sections (Task 10) ───────────────────────────────────────────────────
    private fun addEntry(service: String) {
        tag("add").clickAction()
        waitFor("service")
        tag("service").performTextInput(service)
        tag("password").performTextInput("pw-$service")
        tag("save").performScrollTo().clickAction()
        waitFor("entry:$service")
    }

    private fun shown(t: String) = rule.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Split into sections, add an entry into Work, filter by chip, move an entry, delete a section with a move. */
    @Test
    fun sectionsFlow() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())
            addEntry("Mail")
            gone("sectionChips")                                      // no sections: the list looks as before

            tag("listMenu").clickAction()
            waitFor("menuSplit")
            tag("menuSplit").clickAction()
            waitFor("splitOk")
            tag("splitOk").clickAction()                              // "Move all entries into Personal" is on by default
            waitFor("chip:Work")

            tag("chip:Work").clickAction()
            rule.waitUntil(5_000) { !shown("entry:Mail") }
            addEntry("Jira")                                          // a new entry goes into the selected section
            tag("chip:Personal").clickAction()
            rule.waitUntil(5_000) { !shown("entry:Jira") }
            waitFor("entry:Mail")

            // search looks across all sections
            tag("search").performTextInput("jira")
            waitFor("entry:Jira")
            tag("search").performTextClearance()

            // move Mail to Work in the editor
            tag("entry:Mail").clickAction()
            waitFor("editEntry")
            tag("editEntry").performScrollTo().clickAction()
            waitFor("entrySection")
            tag("entrySection").performScrollTo().clickAction()
            waitFor("entrySection:Work")
            tag("entrySection:Work").clickAction()
            tag("save").performScrollTo().clickAction()
            rule.waitUntil(5_000) { !shown("save") }                  // back on the detail screen
            waitFor("editEntry")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.waitUntil(5_000) { shown("chip:Work") }
            tag("chip:Work").clickAction()
            waitFor("entry:Mail"); waitFor("entry:Jira")

            // delete Work, moving its entries to Personal: nothing is lost
            tag("listMenu").clickAction()
            waitFor("menuSections")
            tag("menuSections").clickAction()
            waitFor("deleteSection:Work")
            tag("deleteSection:Work").clickAction()
            waitFor("moveTo:Personal")
            tag("moveTo:Personal").clickAction()
            tag("deleteSectionOk").clickAction()
            rule.waitUntil(5_000) { !shown("section:Work") }
            val p = app.session.payload.value!!
            assertEquals(listOf("Personal"), com.spirtosan.mobilevault.data.VaultEdits.sections(p).map { it.name })
            assertEquals(2, p.activeEntries.size)
            assertTrue(p.activeEntries.all { it.section == com.spirtosan.mobilevault.data.VaultEdits.sections(p)[0].id })
        }
    }

    // ── Task 11 Part D: import bugs ─────────────────────────────────────────
    /**
     * D1: the section named like the file is preselected (no silent "already exists" failure), a typed clashing
     * name shows an error and blocks Import, a wrong password shows an error on the screen.
     * D2: the import password field starts empty every time, also after typing into it and going back.
     */
    @Test
    fun importPreselectsExistingSectionAndPasswordStartsEmpty() {
        resetVault()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val desk = "Desk-pass-11"
        val file = ctx.cacheDir.resolve("task11-import.vault")
        file.writeBytes(com.spirtosan.mobilevault.crypto.Pvault1.encrypt(desk.toCharArray(),
            com.spirtosan.mobilevault.crypto.VaultPayload(listOf(
                com.spirtosan.mobilevault.crypto.Entry(service = "Shop", username = "me", password = "s1"),
                com.spirtosan.mobilevault.crypto.Entry(service = "Forum", username = "me", password = "f1")))))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            confirmKit(createUntilKit())
            // a file Uri has no display name, so the suggested section name is "Imported": make that section exist
            runBlocking { app.session.update { com.spirtosan.mobilevault.data.VaultEdits.addSection(it, "Imported").first } }
            val pick = { sc.onActivity { ViewModelProvider(it)[AppViewModel::class.java].importPicked(Uri.fromFile(file)) } }

            pick(); waitFor("importPw")
            assertEquals("", fieldText("importPw"))
            tag("importPw").performTextInput("leftover")             // typed, then the owner goes back
            rule.onNodeWithContentDescription("Back").performClick()
            pick(); waitFor("importPw")
            assertEquals("", fieldText("importPw"))                  // a new import starts empty

            tag("importPw").performTextInput("wrong-pass")
            tag("importOpen").clickAction()
            waitFor("importError", ms = 30_000)                      // shown on the screen, not only in a snackbar
            assertEquals("", fieldText("importPw"))                  // cleared after use

            tag("importPw").performTextInput(desk)
            tag("importOpen").clickAction()
            waitFor("importCount", ms = 30_000)
            rule.onNodeWithTag("importTarget").assertTextContains("Imported")        // the existing section, not "New section…"
            gone("importNewName")

            tag("importTarget").clickAction()
            waitFor("importTarget:New section…")
            tag("importTarget:New section…").clickAction()
            waitFor("importNewName")
            tag("importNewName").performTextClearance()
            tag("importNewName").performTextInput("imported")
            waitFor("importNameError")
            tag("importMerge").assertIsNotEnabled()

            tag("importTarget").clickAction()
            waitFor("importTarget:Imported")
            tag("importTarget:Imported").clickAction()
            waitFor("importMerge", enabled = true)
            tag("importMerge").performScrollTo().clickAction()
            waitFor("add")
            val p = app.session.payload.value!!
            val imp = com.spirtosan.mobilevault.data.VaultEdits.sections(p).single { it.name == "Imported" }
            assertEquals(2, p.activeEntries.count { it.section == imp.id })
        }
        file.delete()
    }

    // ── optional recovery key (Part B) ───────────────────────────────────────
    private fun openSettings() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("removeRecoveryKey") or hasTestTag("addRecoveryKey"), 10_000)
    }

    private fun shownKey() = tag("recoveryKey").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun gone(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).assertDoesNotExist()

    /** "Skip": no recovery link, no reminder card (even with a stale flag), the Settings line; adding a key later works. */
    @Test
    fun skipRecoveryKeyThenAddLater() {
        resetVault()
        runBlocking { app.appSettings.setKitPending(true) }       // stale flag from an earlier vault
        ActivityScenario.launch(MainActivity::class.java).use {
            createUntilKeyStep()
            tag("skipKey").performClick()
            waitFor("skipConfirm")
            tag("skipConfirm").performClick()
            skipBackupStep()
            gone("kitPending")
            // no backup server: the red chip on the list
            assertEquals("No backup set up", tag("backupChipText").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })

            tag("lock").performClick()
            waitFor("unlockPw")
            gone("toRecovery")
            unlock()
            gone("kitPending")

            openSettings()
            waitFor("noRecoveryKey")
            tag("addRecoveryKey").performScrollTo().performClick()
            waitFor("newKeyPw")                                       // Task 08: password first (DEK rotation)
            tag("newKeyPw").performTextInput("not the password")
            tag("newKeyOk").performClick()
            rule.waitForIdle()
            gone("recoveryKey")
            tag("newKeyPw").performTextClearance()
            tag("newKeyPw").performTextInput(pw)
            waitFor("newKeyOk", enabled = true)
            tag("newKeyOk").performClick()
            waitFor("recoveryKey")
            confirmKit(shownKey(), newVault = false)
            tag("lock").performClick()
            waitFor("toRecovery")
        }
    }

    /** The reminder card offers "Remove recovery key" with the master-password check. */
    @Test
    fun removeRecoveryKeyFromReminderCard() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            createUntilKit()
            lockFromOutside()
            unlock()
            waitFor("kitPendingRemove")
            tag("kitPendingRemove").performClick()
            waitFor("removePw")
            tag("removePw").performTextInput("not the password")
            tag("removePwOk").performClick()
            rule.waitForIdle()
            gone("removeConfirm")                                    // wrong password: no confirm step
            tag("removePw").performTextInput(pw)
            waitFor("removePwOk", enabled = true)                    // the first check (Argon2) may still run
            tag("removePwOk").performClick()
            waitFor("removeConfirm")
            tag("removeConfirm").performClick()
            rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("kitPending"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

            tag("lock").performClick()
            waitFor("unlockPw")
            gone("toRecovery")
            unlock()
            gone("kitPending")
        }
    }

    /** Settings → Recovery key → Remove. */
    @Test
    fun removeRecoveryKeyInSettings() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())
            openSettings()
            tag("removeRecoveryKey").performScrollTo().performClick()
            waitFor("removePw")
            tag("removePw").performTextInput(pw)
            tag("removePwOk").performClick()
            waitFor("removeConfirm")
            tag("removeConfirm").performClick()
            waitFor("noRecoveryKey")
        }
    }

    // ── master password length (Part C) ──────────────────────────────────────
    /** 5 characters: blocked. 6 characters: allowed with the orange warning. A long password: no warning. */
    @Test
    fun sixCharacterPasswordWarnsFiveBlocked() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("create")
            tag("create").performClick()
            tag("pw").performTextInput("k7#Lp")
            tag("pw2").performTextInput("k7#Lp")
            rule.waitForIdle()
            tag("createSubmit").assertIsNotEnabled()
            gone("weakWarning")

            tag("pw").performTextInput("2")
            tag("pw2").performTextInput("2")
            waitFor("weakWarning")
            waitFor("createSubmit", enabled = true)

            tag("pw").performTextClearance(); tag("pw2").performTextClearance()
            val strong = "correct-horse-battery-staple-xk"      // (the suite's pw contains "password": Very weak)
            tag("pw").performTextInput(strong); tag("pw2").performTextInput(strong)
            rule.waitForIdle()
            gone("weakWarning")
            waitFor("createSubmit", enabled = true)
        }
    }

    // ── Task 13 ──────────────────────────────────────────────────────────────
    private fun vm(sc: ActivityScenario<MainActivity>): AppViewModel {
        var v: AppViewModel? = null
        sc.onActivity { v = ViewModelProvider(it)[AppViewModel::class.java] }
        return v!!
    }

    private fun textOf(t: String) = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** Fix 1: the pairing screen shows the admin command in debug, plain words for the user in release. */
    @Test
    fun pairingScreenTextForAdminAndUser() {
        resetVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("restore"); tag("restore").performClick()            // not paired: the pairing screen first
            waitFor("pairCommand")
            tag("pairCommand").assertTextContains("mvault-admin pair", substring = true)
        }
        AdminTexts.show = false                                          // what release builds show
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("restore"); tag("restore").performClick()
                waitFor("pairCommand")
                assertEquals("Ask the person who runs your backup server for a pairing QR code, then scan it here.", textOf("pairCommand"))
                assertTrue(rule.onAllNodes(hasText("mvault-admin", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty())
            }
        } finally { AdminTexts.show = BuildConfig.DEBUG }
    }

    /** Fix 3: "Vault from <device>", date · versions · short ID, most recent first, hint line. A fake list (no server). */
    @Test
    fun restoreListShowsDeviceDateAndId() {
        resetVault()
        val old = RemoteVault("aaaaaaaa-1111-4111-8111-111111111111", 7, "2026-10-01T08:00:00Z", 900, "test-old-phone", 7)
        val new = RemoteVault("bbbbbbbb-2222-4222-8222-222222222222", 42, "2026-11-03T09:30:00Z", 900, "test-phone", 42)
        val noName = RemoteVault("cccccccc-3333-4333-8333-333333333333", 1, "2026-09-01T08:00:00Z", 900, "", 1)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitFor("create")
            sc.onActivity { ViewModelProvider(it)[AppViewModel::class.java].debugShowRestoreList(listOf(noName, old, new)) }
            waitFor("vault:${new.vaultId}")
            assertEquals("Pick the vault from your old phone. Your Emergency Kit shows its ID.", textOf("restoreListHint"))
            assertEquals("Vault from test-phone", textOf("vaultTitle:${new.vaultId}"))
            assertEquals("Vault from test-old-phone", textOf("vaultTitle:${old.vaultId}"))
            assertEquals("Vault cccccccc…", textOf("vaultTitle:${noName.vaultId}"))          // no device name: the short ID
            val d = textOf("vaultDetails:${new.vaultId}")
            assertTrue(d, d.endsWith(" · 42 versions · ID bbbbbbbb…") && d.contains("2026"))
            assertTrue(textOf("vaultDetails:${noName.vaultId}").endsWith(" · 1 version · ID cccccccc…"))
            val top = { id: String -> tag("vault:$id").fetchSemanticsNode().boundsInRoot.top }
            assertTrue(top(new.vaultId) < top(old.vaultId) && top(old.vaultId) < top(noName.vaultId))      // most recent first
        }
    }

    /** Fix 4: the reason is shown right under the switch whenever the phone can't use a strong fingerprint. */
    @Test
    fun fingerprintSwitchShowsReason() {
        resetVault()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            confirmKit(createUntilKit())
            openSettings()
            waitFor("bioSwitch")
            val status = strongBiometricStatus(ctx)
            val reason = biometricReasonFor(status)
            if (reason == null) {
                gone("bioReason")
                tag("bioSwitch").assertIsEnabled()
                waitFor("bioOffHint")                                // the "off" state says it can be turned on
            } else {
                assertEquals(reason, textOf("bioReason"))
                tag("bioSwitch").assertIsNotEnabled()
                if (status == androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) waitFor("bioOpenSettings")
            }
        }
    }

    /**
     * Fix 5: Save a full copy → wipe → Restore from a file, with a wrong password, the master password and the
     * recovery key; every field survives (sections, notes, tags, favourites) and the Vault ID stays. A desktop file
     * is offered as "Create new vault".
     */
    @Test
    fun fullCopyAndRestoreFromFile() {
        resetVault()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val copy = ctx.cacheDir.resolve("task13-full.pvault").apply { delete() }
        val desktop = ctx.cacheDir.resolve("task13-desktop.vault")
        desktop.writeBytes(com.spirtosan.mobilevault.crypto.Pvault1.encrypt("Desk-pass-13".toCharArray(),
            com.spirtosan.mobilevault.crypto.VaultPayload(listOf(com.spirtosan.mobilevault.crypto.Entry(service = "Old", password = "o1")))))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val key = createUntilKit()
            confirmKit(key)
            runBlocking {
                app.session.update { p0 ->
                    val (p1, sec) = com.spirtosan.mobilevault.data.VaultEdits.addSection(p0, "Personal")
                    p1.copy(entries = p1.entries + listOf(
                        com.spirtosan.mobilevault.crypto.Entry(service = "Mail", username = "me", password = "m1", notes = "test note",
                            tags = listOf("home", "mail"), favourite = true, section = sec.id),
                        com.spirtosan.mobilevault.crypto.Entry(service = "Shop", password = "s1")))
                }
            }
            val expected = app.session.payload.value!!
            val vaultFile = ctx.filesDir.resolve("vault.pvault")
            val id = com.spirtosan.mobilevault.crypto.Pvault2.peekHeader(vaultFile.readBytes()).vaultId

            vm(sc).saveFullCopy(Uri.fromFile(copy))
            rule.waitUntil(10_000) { copy.length() > 0 && !vm(sc).busy }
            assertTrue(copy.readBytes().contentEquals(vaultFile.readBytes()))       // the exact saved file

            fun wipeAndPick(file: java.io.File) {
                resetVault()
                waitFor("restoreFile")
                vm(sc).restoreFilePicked(Uri.fromFile(file))
            }
            fun same() {
                val p = app.session.payload.value!!
                assertEquals(expected.entries, p.entries)
                assertEquals(listOf("Personal"), com.spirtosan.mobilevault.data.VaultEdits.sections(p).map { it.name })
                assertEquals(id, com.spirtosan.mobilevault.crypto.Pvault2.peekHeader(vaultFile.readBytes()).vaultId)
            }

            // wrong password, then the master password
            wipeAndPick(copy)
            waitFor("restorePw")
            tag("restoreWhat").assertTextContains("Full copy", substring = true)
            tag("restorePw").performTextInput("not-the-password")
            tag("restoreUnlock").clickAction()
            waitFor("restoreError", ms = 30_000)
            tag("restoreError").assertTextContains("Wrong password for this file", substring = true)
            tag("restorePw").performTextInput(pw)
            tag("restoreUnlock").clickAction()
            waitFor("add", ms = 30_000)
            same()

            // the recovery key (then a new master password, as after every recovery-key unlock)
            wipeAndPick(copy)
            waitFor("restoreUseKey")
            tag("restoreUseKey").clickAction()
            waitFor("restoreKey")
            tag("restoreKey").performTextInput(key.split("\n", "-").joinToString("-") { it.trim() })
            tag("restoreUnlock").clickAction()
            waitFor("pw", ms = 30_000)
            same()
            tag("pw").performTextInput(newPw)
            tag("pw2").performTextInput(newPw)
            waitFor("newPwSave", enabled = true)
            tag("newPwSave").performScrollTo().performClick()
            waitFor("add")

            // a desktop file: offered as a new vault with its entries (the desktop import flow)
            wipeAndPick(desktop)
            waitFor("restoreDesktopCreate")
            tag("restoreDesktopCreate").clickAction()
            waitFor("importPw")
        }
        copy.delete(); desktop.delete()
    }
}
