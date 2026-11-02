package com.spirtosan.mobilevault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ActivityScenario
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

    private fun waitFor(t: String, enabled: Boolean = false, ms: Long = 20_000) =
        rule.waitUntilAtLeastOneExists(if (enabled) hasTestTag(t) and isEnabled() else hasTestTag(t), ms)

    private val newPw = "Brand-new-master-password-7"

    private fun unlock(password: String = pw) {
        waitFor("unlockPw")
        tag("unlockPw").performTextInput(password)
        tag("unlock").performClick()
        waitFor("add")
    }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileVaultApp

    private fun resetVault() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.filesDir.resolve("vault.pvault").delete()
        ctx.filesDir.resolve("snapshots").deleteRecursively()
        app.session.lock()
        runBlocking { app.appSettings.setKitPending(false); app.appSettings.clearFailures() }
    }

    /** Lock the way the screen-off receiver or the background timer does. */
    private fun lockFromOutside() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.session.lock() }
        rule.waitForIdle()
    }

    /** Onboarding → Create → Emergency Kit. Returns the recovery key as shown (3 lines of 3 groups). */
    private fun createUntilKit(): String {
        waitFor("create")
        tag("create").performClick()
        tag("pw").performTextInput(pw)
        tag("pw2").performTextInput(pw)
        waitFor("createSubmit", enabled = true)
        tag("createSubmit").performScrollTo().performClick()
        waitFor("recoveryKey")
        return tag("recoveryKey").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    }

    private fun confirmKit(key: String) {
        val groups = key.split("\n", "-").map { it.trim() }
        assertEquals(9, groups.size)
        var typed = 0
        for (g in 0 until 9) {
            val n = rule.onAllNodes(hasTestTag("group:$g"), useUnmergedTree = true).fetchSemanticsNodes()
            if (n.isNotEmpty()) { tag("group:$g").performScrollTo().performTextInput(groups[g].lowercase()); typed++ }
        }
        assertEquals(3, typed)
        tag("kitDone").performScrollTo().performClick()
        waitFor("add")
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
            tag("recoveryUnlock").performClick()

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
            tag("recoveryUnlock").performClick()
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
            tag("recoveryUnlock").performClick()
            waitFor("newPwSave")
        }
    }
}
