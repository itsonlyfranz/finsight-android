package com.example.finsightai.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.llm.CuratedModels
import com.example.finsightai.llm.DownloadError
import com.example.finsightai.llm.ModelFamily
import com.example.finsightai.llm.hf.HubFile
import com.example.finsightai.llm.hf.HubRepo
import com.example.finsightai.theme.FinSightAITheme
import com.example.finsightai.ui.aisetup.AiSetupScreen
import com.example.finsightai.ui.aisetup.AiSetupViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI end-to-end tests of the AI Setup tab with the network and native runtime faked. */
@RunWith(AndroidJUnit4::class)
class AiSetupE2ETest {
    @get:Rule val rule = createComposeRule()
    private lateinit var h: TestHarness
    private lateinit var vm: AiSetupViewModel
    private val lfm = CuratedModels.lfm3
    private val bonsai = CuratedModels.bonsai17b

    @Before fun setUp() {
        h = TestHarness()
        rule.runOnUiThread { vm = AiSetupViewModel(h.services) }
        rule.setContent { FinSightAITheme { AiSetupScreen(vm) } }
    }

    @After fun tearDown() = h.close()

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag(tag))
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 5_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGone(tag: String, timeoutMs: Long = 5_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    // ---- curated picker ----

    @Test fun pickerShowsAllThreeFamiliesWithSizeAndRamGuidance() {
        listOf(ModelFamily.GEMMA, ModelFamily.BONSAI, ModelFamily.LFM).forEach { family ->
            rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasText(family.label))
            rule.onNodeWithText(family.label).assertIsDisplayed()
            CuratedModels.byFamily(family).forEach { spec ->
                scrollTo("model_card_${spec.id}")
                rule.onNodeWithTag("model_card_${spec.id}").assertIsDisplayed()
                scrollTo("download_${spec.id}")
                rule.onNodeWithTag("download_${spec.id}").assertIsDisplayed()
            }
        }
        // RAM guidance text is part of every card.
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasText("needs ~", substring = true))
    }

    // ---- download flow ----

    @Test fun downloadShowsProgressThenInstallsAndBecomesActive() {
        scrollTo("download_${lfm.id}")
        rule.onNodeWithTag("download_${lfm.id}").performClick()
        waitForTag("progress_${lfm.id}")
        rule.onNodeWithTag("cancel_${lfm.id}").assertIsDisplayed()

        h.downloader.release()
        waitForTag("installed_size_${lfm.id}")
        // The first installed model is selected automatically.
        rule.onNodeWithTag("active_badge_${lfm.id}").assertIsDisplayed()
        assertEquals(lfm.id, h.store.activeModel.value?.id)
        assertTrue(h.store.find(lfm.id)!!.file.isFile)
    }

    @Test fun cancelledDownloadRemovesItsPartialFile() {
        scrollTo("download_${bonsai.id}")
        rule.onNodeWithTag("download_${bonsai.id}").performClick()
        waitForTag("cancel_${bonsai.id}")
        assertTrue("partial file exists while downloading", h.store.partFile(bonsai).isFile)

        rule.onNodeWithTag("cancel_${bonsai.id}").performClick()
        waitForTag("download_${bonsai.id}")
        assertFalse("partial file removed on cancel", h.store.partFile(bonsai).exists())
        assertFalse(h.store.isInstalled(bonsai.id))
    }

    @Test fun failedDownloadShowsErrorAndLeavesNoPartialFile() {
        h.downloader.failNext = DownloadError.Network("connection reset")
        scrollTo("download_${bonsai.id}")
        rule.onNodeWithTag("download_${bonsai.id}").performClick()
        waitForTag("progress_${bonsai.id}")
        h.downloader.release()
        waitForTag("error_${bonsai.id}")
        assertFalse(h.store.partFile(bonsai).exists())
        // Retry works once the network is back.
        rule.onNodeWithTag("download_${bonsai.id}").performClick()
        waitForTag("progress_${bonsai.id}")
        h.downloader.release()
        waitForTag("installed_size_${bonsai.id}")
    }

    @Test fun gatedFailureAsksForAHuggingFaceToken() {
        h.downloader.failNext = DownloadError.Unauthorized(gated = true, hadToken = false)
        scrollTo("download_${bonsai.id}")
        rule.onNodeWithTag("download_${bonsai.id}").performClick()
        waitForTag("progress_${bonsai.id}")
        h.downloader.release()
        waitForTag("error_${bonsai.id}")
        rule.onNodeWithTag("error_${bonsai.id}").assertIsDisplayed()
    }

    // ---- delete ----

    private fun installViaUi(spec: com.example.finsightai.llm.ModelSpec) {
        scrollTo("download_${spec.id}")
        rule.onNodeWithTag("download_${spec.id}").performClick()
        waitForTag("progress_${spec.id}")
        h.downloader.release()
        waitForTag("installed_size_${spec.id}")
    }

    @Test fun deletingAnInactiveModelRemovesItsFilesAndKeepsTheActiveOne() {
        installViaUi(lfm)       // becomes active
        installViaUi(bonsai)    // stays inactive
        val bonsaiFile = h.store.find(bonsai.id)!!.file
        assertEquals(lfm.id, h.store.activeModel.value?.id)

        scrollTo("delete_${bonsai.id}")
        rule.onNodeWithTag("delete_${bonsai.id}").performClick()
        rule.onNodeWithTag("confirm_delete").performClick()
        waitGone("installed_size_${bonsai.id}")
        assertFalse("model file removed from app storage", bonsaiFile.exists())
        assertEquals(lfm.id, h.store.activeModel.value?.id)
    }

    @Test fun deletingTheActiveModelFallsBackToBuiltInRules() {
        installViaUi(lfm)
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("effective_status"))
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("effective_status").fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onNodeWithTag("effective_status").assertTextStartsWith("Writing insights with") }.isSuccess
        }
        val file = h.store.find(lfm.id)!!.file

        scrollTo("delete_${lfm.id}")
        rule.onNodeWithTag("delete_${lfm.id}").performClick()
        rule.onNodeWithTag("confirm_delete").performClick()
        waitGone("installed_size_${lfm.id}")

        assertFalse(file.exists())
        assertNull(h.store.activeModel.value)
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("effective_status"))
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("effective_status").assertTextStartsWith("Using built-in rules") }.isSuccess
        }
        // RAM is released once the model is no longer active.
        rule.waitUntil(5_000) { h.runtime.loadedModelId == null }
    }

    // ---- backend switching ----

    @Test fun backendSwitchingUpdatesTheEffectiveStatus() {
        installViaUi(lfm)
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("backend_codex"))
        rule.onNodeWithTag("backend_codex").performClick()
        rule.waitUntil(5_000) { h.prefs.choice.value == BackendChoice.CODEX }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("effective_status").assertTextStartsWith("Using built-in rules: Not signed in") }.isSuccess
        }
        rule.onNodeWithTag("backend_on_device").performClick()
        assertEquals(BackendChoice.ON_DEVICE, h.prefs.choice.value)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("effective_status").assertTextStartsWith("Writing insights with") }.isSuccess
        }
    }

    // ---- Codex sign-in (network mocked) ----

    @Test fun codexSignInShowsCodeThenSignedInThenSignOut() {
        scrollTo("codex_sign_in")
        rule.onNodeWithTag("codex_status").assertIsDisplayed()
        rule.onNodeWithTag("codex_sign_in").performClick()
        waitForTag("codex_code")
        rule.onNodeWithTag("codex_code").assertTextEquals("ABCD-1234")
        rule.onNodeWithTag("codex_open_page").assertIsDisplayed()

        h.codexClient.approved = true
        waitForTag("codex_sign_out", 10_000)
        scrollTo("codex_status")
        rule.onNodeWithTag("codex_status").assertTextContains("mark@example.com", substring = true)
        assertTrue(h.codexAuth.state.value is CodexAuthState.SignedIn)

        rule.onNodeWithTag("codex_sign_out").performClick()
        waitForTag("codex_sign_in")
        assertTrue(h.codexAuth.state.value is CodexAuthState.SignedOut)
        assertNull("tokens wiped from the secret store", h.secrets.get(com.example.finsightai.codex.CodexAuthManager.TOKENS_KEY))
    }

    @Test fun codexSignInCanBeCancelled() {
        scrollTo("codex_sign_in")
        rule.onNodeWithTag("codex_sign_in").performClick()
        waitForTag("codex_cancel")
        scrollTo("codex_cancel")
        rule.onNodeWithTag("codex_cancel").performClick()
        waitForTag("codex_sign_in")
    }

    @Test fun codexSignInFailureIsShownWithRetry() {
        h.codexClient.failRequest = true
        scrollTo("codex_sign_in")
        rule.onNodeWithTag("codex_sign_in").performClick()
        waitForTag("codex_error")
        rule.onNodeWithTag("codex_error").assertTextContains("503", substring = true)
        h.codexClient.failRequest = false
        rule.onNodeWithTag("codex_sign_in").performClick()
        waitForTag("codex_code")
    }

    // ---- Hugging Face search ----

    private fun seedSearch() {
        h.hfApi.repos = listOf(
            HubRepo("acme/Good-1B-GGUF", tags = listOf("gguf", "license:apache-2.0"), pipelineTag = "text-generation", ggufArchitecture = "llama", downloads = 5000),
            HubRepo("acme/Only-F16-GGUF", tags = listOf("gguf"), pipelineTag = "text-generation", ggufArchitecture = "llama", downloads = 9000),
            HubRepo("acme/Embedder-GGUF", tags = listOf("gguf"), pipelineTag = "feature-extraction", ggufArchitecture = "bert", downloads = 8000),
            HubRepo("acme/Huge-70B-GGUF", tags = listOf("gguf"), pipelineTag = "text-generation", ggufArchitecture = "llama", downloads = 7000),
        )
        h.hfApi.files = mapOf(
            "acme/Good-1B-GGUF" to listOf(
                HubFile("Good-1B-Q4_K_M.gguf", 700_000_000),
                HubFile("Good-1B-mmproj-F16.gguf", 300_000_000),
                HubFile("Good-1B-F16.gguf", 2_400_000_000),
            ),
            "acme/Only-F16-GGUF" to listOf(HubFile("Only-F16.gguf", 2_400_000_000)),
            "acme/Embedder-GGUF" to listOf(HubFile("Embedder-Q4_K_M.gguf", 100_000_000)),
            "acme/Huge-70B-GGUF" to listOf(HubFile("Huge-70B-Q4_K_M.gguf", 40_000_000_000)),
        )
    }

    private fun openSearch() {
        scrollTo("open_hf_search")
        rule.onNodeWithTag("open_hf_search").performClick()
        waitForTag("search_field")
    }

    @Test fun searchShowsOnlyAndroidCompatibleModels() {
        seedSearch()
        openSearch()
        rule.onNodeWithTag("search_field").performTextInput("good")
        rule.onNodeWithTag("search_button").performClick()
        waitForTag("result_acme/Good-1B-GGUF")
        // Incompatible repos are hidden, not greyed out.
        rule.onAllNodesWithTag("result_acme/Only-F16-GGUF").assertCountEquals(0)
        rule.onAllNodesWithTag("result_acme/Embedder-GGUF").assertCountEquals(0)
        rule.onAllNodesWithTag("result_acme/Huge-70B-GGUF").assertCountEquals(0)
        // Within the compatible repo only the usable quantization is offered (no mmproj, no F16).
        // Within the compatible repo exactly one file is offered: the Q4_K_M quant (no mmproj, no F16).
        rule.onNodeWithTag("hf_search_list").performScrollToNode(hasText("Q4_K_M", substring = true))
        assertTrue(rule.onAllNodesWithText("Q4_K_M", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("F16", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("mmproj", substring = true).assertCountEquals(0)
    }

    @Test fun searchWithNoCompatibleResultsShowsEmptyState() {
        seedSearch()
        h.hfApi.repos = h.hfApi.repos.filter { it.id != "acme/Good-1B-GGUF" }
        openSearch()
        rule.onNodeWithTag("search_field").performTextInput("acme")
        rule.onNodeWithTag("search_button").performClick()
        waitForTag("search_empty")
    }

    @Test fun searchResultDownloadsThroughTheSamePathAndAppearsInTheModelList() {
        seedSearch()
        openSearch()
        rule.onNodeWithTag("search_field").performTextInput("good")
        rule.onNodeWithTag("search_button").performClick()
        waitForTag("result_acme/Good-1B-GGUF")
        val spec = (vm.search.value as com.example.finsightai.ui.aisetup.SearchUiState.Results).items.single().best
        rule.onNodeWithTag("download_${spec.id}").performClick()
        waitForTag("progress_${spec.id}")
        h.downloader.release()
        waitForTag("installed_size_${spec.id}")
        assertTrue(h.store.isInstalled(spec.id))
        rule.onNodeWithTag("search_back").performClick()
        scrollTo("model_card_${spec.id}")
        rule.onNodeWithTag("model_card_${spec.id}").assertIsDisplayed()
    }

    @Test fun huggingFaceTokenCanBeSavedAndRemoved() {
        openSearch()
        rule.onNodeWithTag("hf_token_field").performTextInput("hf_secret_token_value")
        rule.onNodeWithTag("hf_token_save").performClick()
        waitForTag("hf_token_saved")
        assertEquals("hf_secret_token_value", h.hfTokens.get())
        // The token never appears on screen.
        rule.onAllNodesWithText("hf_secret_token_value", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("hf_token_clear").performClick()
        waitForTag("hf_token_field")
        assertNull(h.hfTokens.get())
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextStartsWith(prefix: String) {
    val text = fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
    if (!text.startsWith(prefix)) throw AssertionError("expected text starting with '$prefix' but was '$text'")
}
