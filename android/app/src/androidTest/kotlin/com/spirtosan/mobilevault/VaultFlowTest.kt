package com.spirtosan.mobilevault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** create → lock → unlock → add entry → lock → unlock shows the entry. Wipes the app's vault first. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VaultFlowTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val pw = "Test-master-password-42"

    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun waitFor(t: String, enabled: Boolean = false, ms: Long = 20_000) =
        rule.waitUntilAtLeastOneExists(if (enabled) hasTestTag(t) and isEnabled() else hasTestTag(t), ms)

    private fun unlock() {
        waitFor("unlockPw")
        tag("unlockPw").performTextInput(pw)
        tag("unlock").performClick()
        waitFor("add")
    }

    @Test
    fun createLockUnlockAddEntry() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.filesDir.resolve("vault.pvault").delete()
        ctx.filesDir.resolve("snapshots").deleteRecursively()
        val app = ctx.applicationContext as MobileVaultApp
        app.session.lock()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("create")
            tag("create").performClick()
            tag("pw").performTextInput(pw)
            tag("pw2").performTextInput(pw)
            waitFor("createSubmit", enabled = true)
            tag("createSubmit").performScrollTo().performClick()

            // Emergency Kit: read the key, type the 3 requested groups
            waitFor("recoveryKey")
            val key = tag("recoveryKey").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
            val groups = key.split("\n", "-").map { it.trim() }
            assertTrue(groups.size == 9)
            var typed = 0
            for (g in 0 until 9) {
                val n = rule.onAllNodes(hasTestTag("group:$g"), useUnmergedTree = true).fetchSemanticsNodes()
                if (n.isNotEmpty()) { tag("group:$g").performScrollTo().performTextInput(groups[g]); typed++ }
            }
            assertTrue(typed == 3)
            tag("kitDone").performScrollTo().performClick()
            waitFor("add")

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
}
