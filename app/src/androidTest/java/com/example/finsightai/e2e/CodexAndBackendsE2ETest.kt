package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.ai.backend.AiBackendException
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.codex.CodexAuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Codex sign-in (fake device-code server) and backend switching, seen from the real activity: who
 * writes the AI Insights / Simulator text, and the fallback to the rules text whenever no backend is ready.
 */
@RunWith(AndroidJUnit4::class)
class CodexAndBackendsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val rulesText = "High Cafe & Dining Velocity"

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("tab_ai_setup")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun pickBackend(choice: BackendChoice) {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "backend_${choice.name.lowercase()}").performClick()
        rule.waitUntil(10_000) { app.prefs.choice.value == choice }
    }

    private fun effectiveStatus(): String {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "effective_status")
        return rule.textOf("effective_status")
    }

    private fun waitForStatus(expected: String) {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "effective_status")
        rule.waitForNode(hasTestTag("effective_status") and hasText(expected))
    }

    private fun signInThroughTheUi() {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_code")
        assertEquals("ABCD-1234", rule.textOf("codex_code"))
        rule.scrollToTag("ai_setup_list", "codex_open_page").assertIsDisplayed()
        app.codexClient.approved = true
        rule.waitForTag("codex_sign_out", 15_000)
    }

    private fun insightsShowRulesText() {
        rule.openTab("insights")
        rule.waitForTag("insights_list")
        rule.scrollToText("insights_list", rulesText)
        rule.waitUntil(10_000) { rule.countTagsStartingWith("insight_generated_by_") == 0 }
        assertFalse(rule.hasTag("ai_status_line"))
    }

    private fun insightsWrittenBy(label: String) {
        rule.openTab("insights")
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Written by $label", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToText("insights_list", "Rewritten by Codex.", substring = true)
    }

    // ---- no backend ready ----

    @Test fun withNoModelInstalledInsightsAndSimulatorUseTheRulesText() {
        assertEquals(BackendChoice.ON_DEVICE, app.prefs.choice.value)
        assertEquals("Using built-in rules: No on-device model is installed and selected", effectiveStatus())
        insightsShowRulesText()
        assertFalse(rule.hasTag("ai_error_line"))

        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.scrollToTag("simulator_list", "sim_ai_explanation").assertIsDisplayed()
        assertFalse("rules text is not labelled as model-written", rule.hasTag("sim_generated_by"))
        assertEquals(0, rule.countTagsStartingWith("goal_ai_context_"))
    }

    @Test fun codexSelectedButSignedOutFallsBackToTheRulesText() {
        pickBackend(BackendChoice.CODEX)
        waitForStatus("Using built-in rules: Not signed in to Codex")
        insightsShowRulesText()
    }

    // ---- sign-in flow ----

    @Test fun signedOutCardOffersSignInAndNoAccountModels() {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "codex_status")
        assertEquals("Signed out", rule.textOf("codex_status"))
        rule.scrollToTag("ai_setup_list", "codex_sign_in").assertIsDisplayed()
        assertEquals(0, rule.countTagsStartingWith("codex_model_"))
    }

    @Test fun signInShowsTheCodeWaitsForApprovalThenSignsInAndSignOutWipesTheTokens() {
        signInThroughTheUi()
        rule.scrollToTag("ai_setup_list", "codex_status")
        assertTrue(rule.textOf("codex_status").contains("mark@example.com"))
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedIn)

        rule.scrollToTag("ai_setup_list", "codex_sign_out").performClick()
        rule.waitForTag("codex_sign_in")
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedOut)
        assertNull(app.codex.selectedModel.value)
    }

    @Test fun cancellingSignInReturnsToSignedOut() {
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_code")
        rule.scrollToTag("ai_setup_list", "codex_cancel").performClick()
        rule.waitForTag("codex_sign_in")
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedOut)
    }

    @Test fun aFailedSignInStartShowsTheErrorAndCanBeRetried() {
        app.codexClient.failRequest = true
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_error")
        assertTrue(rule.textOf("codex_error").contains("503"))
        app.codexClient.failRequest = false
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_code")
    }

    // ---- Codex writes the text ----

    @Test fun afterSignInCodexWritesInsightsAndSimulatorTextAndTheAccountModelCanBeChanged() {
        pickBackend(BackendChoice.CODEX)
        signInThroughTheUi()
        waitForStatus("Writing insights with Codex (codex-auto-review)")
        insightsWrittenBy("Codex (codex-auto-review)")

        rule.openTab("simulator")
        rule.scrollToTag("simulator_list", "sim_ai_explanation")
        rule.waitForTag("sim_generated_by", 15_000)

        rule.openTab("ai_setup")
        rule.scrollUntilTag("ai_setup_list", "codex_model_gpt-fake-mini")
        rule.scrollToTag("ai_setup_list", "codex_model_codex-auto-review").assertIsSelected()
        rule.scrollToTag("ai_setup_list", "codex_model_gpt-fake-mini").performClick()
        waitForStatus("Writing insights with Codex (gpt-fake-mini)")
        insightsWrittenBy("Codex (gpt-fake-mini)")
    }

    @Test fun signingOutOfCodexRestoresTheRulesTextAndKeepsTheBackendChoice() {
        pickBackend(BackendChoice.CODEX)
        signInThroughTheUi()
        insightsWrittenBy("Codex (codex-auto-review)")

        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "codex_sign_out").performClick()
        rule.waitForTag("codex_sign_in")
        assertEquals(BackendChoice.CODEX, app.prefs.choice.value)
        waitForStatus("Using built-in rules: Not signed in to Codex")
        insightsShowRulesText()
    }

    @Test fun aCodexFailureFallsBackToTheRulesTextShowsWhyAndRecovers() {
        pickBackend(BackendChoice.CODEX)
        signInThroughTheUi()
        insightsWrittenBy("Codex (codex-auto-review)")

        // Codex starts failing (e.g. the plan expired): switching away and back makes the app ask it again.
        app.codex.failure = AiBackendException("Codex plan expired")
        pickBackend(BackendChoice.ON_DEVICE)
        pickBackend(BackendChoice.CODEX)
        rule.openTab("insights")
        rule.waitForTag("ai_error_line", 15_000)
        assertEquals("Model unavailable, showing built-in rules: Codex plan expired", rule.textOf("ai_error_line"))
        rule.scrollToText("insights_list", rulesText)
        assertEquals(0, rule.countTagsStartingWith("insight_generated_by_"))

        app.codex.failure = null
        pickBackend(BackendChoice.ON_DEVICE)
        pickBackend(BackendChoice.CODEX)
        insightsWrittenBy("Codex (codex-auto-review)")
        assertFalse(rule.hasTag("ai_error_line"))
    }

    @Test fun backendChoiceIsShownAsSelectedInTheRadioList() {
        pickBackend(BackendChoice.CODEX)
        rule.scrollToTag("ai_setup_list", "backend_codex").assertIsSelected()
        pickBackend(BackendChoice.ON_DEVICE)
        rule.scrollToTag("ai_setup_list", "backend_on_device").assertIsSelected()
    }
}
