package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.codex.CodexAuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** AI settings that must still be there after the app is restarted (new process, same storage). */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        scenario = app.launch()
        rule.openTab("ai_setup")
        rule.waitForTag("ai_setup_list")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun restartIntoAiSetup() {
        scenario = app.restart(scenario)
        rule.openTab("ai_setup")
        rule.waitForTag("ai_setup_list")
    }

    @Test fun theBackendChoiceSurvivesRecreationAndRestart() {
        rule.scrollToTag("ai_setup_list", "backend_codex").performClick()
        rule.waitUntil(10_000) { app.prefs.choice.value == BackendChoice.CODEX }

        scenario.recreate()
        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "backend_codex").assertIsSelected()

        restartIntoAiSetup()
        assertEquals(BackendChoice.CODEX, app.prefs.choice.value)
        rule.scrollToTag("ai_setup_list", "backend_codex").assertIsSelected()
    }

    @Test fun theHuggingFaceTokenSurvivesARestartAndIsNeverStoredInPlaintext() {
        rule.scrollToTag("ai_setup_list", "open_hf_search").performClick()
        rule.waitForTag("hf_token_field")
        rule.onNodeWithTag("hf_token_field").performTextInput("hf_persist_secret_123")
        rule.onNodeWithTag("hf_token_save").performClick()
        rule.waitForTag("hf_token_saved")

        restartIntoAiSetup()
        assertEquals("hf_persist_secret_123", app.hfTokens.get())
        rule.scrollToTag("ai_setup_list", "open_hf_search").performClick()
        rule.waitForTag("hf_token_saved")
        assertEquals("the token is never rendered", 0, rule.onAllNodesWithText("hf_persist_secret_123", substring = true).fetchSemanticsNodes().size)
        val raw = app.context.getSharedPreferences("e2e_secrets_${secretsNamespace()}", 0).all.values.joinToString()
        assertFalse("token must be encrypted at rest: $raw", raw.contains("hf_persist_secret_123"))

        rule.onNodeWithTag("hf_token_clear").performClick()
        rule.waitForTag("hf_token_field")
        restartIntoAiSetup()
        assertEquals(null, app.hfTokens.get())
    }

    /** The fixture namespaces its preference files; find the secrets file by suffix. */
    private fun secretsNamespace(): String {
        val dir = java.io.File(app.context.applicationInfo.dataDir, "shared_prefs")
        val name = dir.listFiles().orEmpty().map { it.name }.first { it.startsWith("e2e_secrets_") }
        return name.removePrefix("e2e_secrets_").removeSuffix(".xml")
    }

    @Test fun codexSignInAndTheChosenAccountModelSurviveARestart() {
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_code")
        app.codexClient.approved = true
        rule.waitForTag("codex_sign_out", 15_000)
        rule.scrollUntilTag("ai_setup_list", "codex_model_gpt-fake-mini").performClick()
        rule.waitUntil(10_000) { app.codex.selectedModel.value == "gpt-fake-mini" }

        restartIntoAiSetup()
        rule.waitUntil(10_000) { app.codexAuth.state.value is CodexAuthState.SignedIn }
        rule.scrollUntilTag("ai_setup_list", "codex_sign_out")
        rule.scrollToTag("ai_setup_list", "codex_status")
        assertTrue(rule.textOf("codex_status").contains("mark@example.com"))
        rule.scrollUntilTag("ai_setup_list", "codex_model_gpt-fake-mini").assertIsSelected()
    }

    @Test fun signingOutIsRememberedAcrossARestart() {
        rule.scrollToTag("ai_setup_list", "codex_sign_in").performClick()
        rule.waitForTag("codex_code")
        app.codexClient.approved = true
        rule.scrollUntilTag("ai_setup_list", "codex_sign_out").performClick()
        rule.scrollUntilTag("ai_setup_list", "codex_sign_in")

        restartIntoAiSetup()
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedOut)
        rule.scrollUntilTag("ai_setup_list", "codex_sign_in")
    }
}
