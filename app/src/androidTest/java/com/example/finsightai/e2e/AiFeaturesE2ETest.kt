package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainNavigation
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.llm.CuratedModels
import com.example.finsightai.theme.FinSightAITheme
import com.example.finsightai.ui.aisetup.AiSetupViewModel
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whole-app tests: the AI Insights and Simulator tabs react to the backend the user picked, and fall
 * back to the deterministic engines when no model is active.
 */
@RunWith(AndroidJUnit4::class)
class AiFeaturesE2ETest {
    @get:Rule val rule = createComposeRule()
    private lateinit var h: TestHarness
    private val lfm = CuratedModels.lfm3

    @Before fun setUp() {
        h = TestHarness()
        lateinit var finsight: FinSightViewModel
        lateinit var setup: AiSetupViewModel
        rule.runOnUiThread {
            finsight = FinSightViewModel(DefaultFinSightRepository(), h.services.router, h.services.signal)
            setup = AiSetupViewModel(h.services)
        }
        rule.setContent { FinSightAITheme { MainNavigation(viewModel = finsight, aiSetupViewModel = setup) } }
    }

    @After fun tearDown() = h.close()

    private fun count(tagPrefix: String): Int =
        rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag starts with $tagPrefix") { node ->
            (androidx.compose.ui.semantics.SemanticsProperties.TestTag in node.config) && node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(tagPrefix)
        }).fetchSemanticsNodes().size

    private fun assertInsightTextShown(text: String) {
        rule.onNodeWithTag("insights_list").performScrollToNode(hasText(text, substring = true))
        rule.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    private fun waitUntilGeneratedBy(text: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun installModel() {
        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("download_${lfm.id}"))
        rule.onNodeWithTag("download_${lfm.id}").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("progress_${lfm.id}").fetchSemanticsNodes().isNotEmpty() }
        h.downloader.release()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("installed_size_${lfm.id}").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun withoutAModelInsightsUseTheDeterministicEngine() {
        rule.onNodeWithTag("tab_insights").performClick()
        rule.waitForIdle()
        assertInsightTextShown("High Cafe & Dining Velocity")
        assertEquals(0, count("insight_generated_by_"))
    }

    @Test fun installedModelRewritesInsightsSimulatorAndGoalContext() {
        installModel()

        rule.onNodeWithTag("tab_insights").performClick()
        waitUntilGeneratedBy("Written by ${lfm.displayName}")
        assertInsightTextShown("Rewritten on-device by the fake model.")

        rule.onNodeWithTag("tab_simulator").performClick()
        rule.onNodeWithTag("simulator_list").performScrollToNode(hasTestTag("sim_ai_explanation"))
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("sim_generated_by").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("simulator_list").performScrollToNode(hasTestTag("goals_section"))
        rule.waitUntil(15_000) { count("goal_ai_context_") > 0 }
    }

    @Test fun deletingTheActiveModelRestoresDeterministicText() {
        installModel()
        rule.onNodeWithTag("tab_insights").performClick()
        waitUntilGeneratedBy("Written by")
        assertEquals("the model was loaded to write the insights", lfm.id, h.runtime.loadedModelId)

        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("delete_${lfm.id}"))
        rule.onNodeWithTag("delete_${lfm.id}").performClick()
        rule.onNodeWithTag("confirm_delete").performClick()
        rule.waitUntil(5_000) { h.runtime.loadedModelId == null && h.runtime.unloads > 0 }

        rule.onNodeWithTag("tab_insights").performClick()
        rule.waitUntil(10_000) { count("insight_generated_by_") == 0 }
        assertInsightTextShown("High Cafe & Dining Velocity")
    }

    private fun useSignedInCodex() {
        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("backend_codex"))
        rule.onNodeWithTag("backend_codex").performClick()
        h.codexClient.approved = true
        h.codexAuth.startSignIn()
        rule.waitUntil(10_000) { h.codexAuth.isSignedIn }
    }

    @Test fun pickingAnotherCodexModelRewritesTheInsightsWithIt() {
        useSignedInCodex()
        rule.onNodeWithTag("tab_insights").performClick()
        waitUntilGeneratedBy("Written by Codex (codex-auto-review)")

        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("codex_card"))
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("codex_model_gpt-fake-mini").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("codex_model_gpt-fake-mini"))
        rule.onNodeWithTag("codex_model_gpt-fake-mini").performClick()

        rule.onNodeWithTag("tab_insights").performClick()
        waitUntilGeneratedBy("Written by Codex (gpt-fake-mini)")
    }

    @Test fun codexModelListThatFailedToLoadIsRetriedWhenAiSetupIsShownAgain() {
        h.codex.models = emptyList()
        useSignedInCodex()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("codex_card"))
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("codex_models_error").fetchSemanticsNodes().isNotEmpty() }

        h.codex.models = listOf("codex-auto-review", "gpt-fake-mini")
        rule.onNodeWithTag("tab_insights").performClick()
        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("codex_card"))
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("codex_model_codex-auto-review").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun switchingToCodexUsesCodexWhenSignedInAndFallsBackWhenNot() {
        installModel()
        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("backend_codex"))
        rule.onNodeWithTag("backend_codex").performClick()

        // Codex selected but signed out: deterministic text.
        rule.onNodeWithTag("tab_insights").performClick()
        rule.waitUntil(10_000) { count("insight_generated_by_") == 0 }

        // Sign in (network mocked), then the same tab is rewritten by Codex.
        h.codexClient.approved = true
        h.codexAuth.startSignIn()
        rule.waitUntil(10_000) { h.codexAuth.isSignedIn }
        waitUntilGeneratedBy("Written by Codex (codex-auto-review)")
        assertInsightTextShown("Rewritten by Codex.")

        // Back to on-device.
        rule.onNodeWithTag("tab_ai_setup").performClick()
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("backend_on_device"))
        rule.onNodeWithTag("backend_on_device").performClick()
        rule.onNodeWithTag("tab_insights").performClick()
        waitUntilGeneratedBy("Written by ${lfm.displayName}")
    }
}
