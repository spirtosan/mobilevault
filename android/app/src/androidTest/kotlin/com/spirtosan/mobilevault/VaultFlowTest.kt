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
        runBlocking { app.appSettings.setKitPending(false); app.appSettings.clearFailures() }
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
}
