package com.example.finsightai.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.llm.ModelSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device models over real HTTP to a fake Hugging Face that serves tiny GGUF files: search and its
 * compatibility filter, download, cancel, resume, retry, select, delete (including the active model)
 * and what survives an app restart. Only the server and the llama.cpp runtime are fake.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private val q4 = tinyGguf(96 * 1024, seed = 1)
    private val q8 = tinyGguf(128 * 1024, seed = 2)
    private val chat = tinyGguf(64 * 1024, seed = 3)
    private val gatedBytes = tinyGguf(48 * 1024, seed = 4)
    private val corrupt = tinyGguf(40 * 1024, seed = 5)

    private val hub = FakeHubServer(
        listOf(
            FakeRepo(
                "acme/Tiny-1B-GGUF", downloads = 9_000,
                files = listOf(
                    FakeFile("Tiny-1B-Q4_K_M.gguf", q4),
                    FakeFile("Tiny-1B-Q8_0.gguf", q8),
                    FakeFile("Tiny-1B-mmproj-F16.gguf", tinyGguf(1024)),
                    FakeFile("Tiny-1B-F16.gguf", tinyGguf(1024), reportedSize = 2_400_000_000L),
                ),
            ),
            FakeRepo("acme/Tiny-Chat-GGUF", downloads = 5_000, files = listOf(FakeFile("Tiny-Chat-Q4_K_M.gguf", chat))),
            FakeRepo("acme/Tiny-Gated-GGUF", downloads = 4_000, gated = true, files = listOf(FakeFile("Tiny-Gated-Q4_K_M.gguf", gatedBytes))),
            FakeRepo(
                "acme/Tiny-Corrupt-GGUF", downloads = 3_000,
                files = listOf(FakeFile("Tiny-Corrupt-Q4_K_M.gguf", corrupt, reportedSha256 = "0".repeat(64))),
            ),
            FakeRepo("acme/Tiny-Embed-GGUF", downloads = 8_000, pipelineTag = "feature-extraction", architecture = "bert", files = listOf(FakeFile("Tiny-Embed-Q4_K_M.gguf", tinyGguf(1024)))),
            FakeRepo("acme/Tiny-Huge-GGUF", downloads = 7_000, files = listOf(FakeFile("Tiny-Huge-Q4_K_M.gguf", tinyGguf(1024), reportedSize = 6_000_000_000L))),
            FakeRepo("acme/Tiny-Weights", downloads = 6_000, tags = listOf("safetensors"), architecture = null, files = listOf(FakeFile("model.safetensors", tinyGguf(1024)))),
        )
    )
    private val app = AppFixture(hub)
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val q4Id = "acme/Tiny-1B-GGUF/Tiny-1B-Q4_K_M.gguf"
    private val q8Id = "acme/Tiny-1B-GGUF/Tiny-1B-Q8_0.gguf"
    private val chatId = "acme/Tiny-Chat-GGUF/Tiny-Chat-Q4_K_M.gguf"
    private val gatedId = "acme/Tiny-Gated-GGUF/Tiny-Gated-Q4_K_M.gguf"
    private val corruptId = "acme/Tiny-Corrupt-GGUF/Tiny-Corrupt-Q4_K_M.gguf"

    @Before fun setUp() {
        scenario = app.launch()
        rule.openTab("ai_setup")
        rule.waitForTag("ai_setup_list")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
        hub.close()
    }

    // ---- helpers ----

    private fun openSearch() {
        rule.scrollToTag("ai_setup_list", "open_hf_search").performClick()
        rule.waitForTag("search_field")
    }

    private fun search(query: String) {
        rule.onNodeWithTag("search_field").performTextReplacement(query)
        rule.onNodeWithTag("search_button").performClick()
    }

    private fun searchTiny() { openSearch(); search("tiny"); rule.scrollUntilTag("hf_search_list", "result_acme/Tiny-1B-GGUF") }

    private fun download(id: String) {
        rule.scrollToTag("hf_search_list", "download_$id").performClick()
    }

    private fun installFromSearch(id: String) {
        download(id)
        rule.waitForTag("installed_size_$id", 20_000)
    }

    private fun backToSetup() {
        rule.scrollToTag("hf_search_list", "search_back").performClick()
        rule.waitForTag("ai_setup_list")
    }

    private fun effectiveStatus(): String {
        rule.scrollToTag("ai_setup_list", "effective_status")
        return rule.textOf("effective_status")
    }

    private fun waitForStatus(prefix: String) =
        rule.waitUntil(10_000) { runCatching { effectiveStatus().startsWith(prefix) }.getOrDefault(false) }

    private fun spec(id: String): ModelSpec = app.store.find(id)!!.spec

    // ---- search ----

    @Test fun searchOffersOnlyModelsThisPhoneCanRun() {
        searchTiny()
        rule.scrollUntilTag("hf_search_list", "result_acme/Tiny-Chat-GGUF")
        rule.onAllNodesWithTag("result_acme/Tiny-Embed-GGUF").assertCountEquals(0)
        rule.onAllNodesWithTag("result_acme/Tiny-Huge-GGUF").assertCountEquals(0)
        rule.onAllNodesWithTag("result_acme/Tiny-Weights").assertCountEquals(0)
        rule.scrollToTag("hf_search_list", "download_$q4Id").assertIsDisplayed()
        rule.scrollToTag("hf_search_list", "download_$q8Id").assertIsDisplayed()
        assertEquals("mmproj and 2.4 GB F16 files are not offered", 0, rule.onAllNodesWithText("mmproj", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("F16", substring = true).fetchSemanticsNodes().size)
        // A gated repo is listed but flagged.
        rule.scrollToText("hf_search_list", "gated", substring = true)
    }

    @Test fun searchWithNoMatchShowsTheEmptyState() {
        openSearch()
        search("zzzz")
        rule.waitForTag("search_empty")
    }

    @Test fun tooShortAQueryDoesNotCallTheHub() {
        openSearch()
        search("a")
        rule.waitForIdle()
        assertTrue(hub.requests.none { it.path.startsWith("/api/models?") })
    }

    @Test fun searchFailuresAreShownAndSearchRecovers() {
        openSearch()
        hub.searchStatus = 500
        search("tiny")
        rule.waitForTag("search_error")
        assertTrue(rule.textOf("search_error").contains("500"))

        hub.searchStatus = 429
        rule.onNodeWithTag("search_button").performClick()
        rule.waitUntil(10_000) { runCatching { rule.textOf("search_error").contains("rate limiting") }.getOrDefault(false) }

        hub.searchStatus = 200
        rule.onNodeWithTag("search_button").performClick()
        rule.waitForTag("result_acme/Tiny-1B-GGUF")
    }

    @Test fun theHubIsOnlySentATokenOnceOneIsSaved() {
        openSearch()
        search("tiny")
        rule.scrollUntilTag("hf_search_list", "result_acme/Tiny-1B-GGUF")
        assertTrue(hub.requests.filter { it.path.startsWith("/api/models") }.all { it.authorization == null })

        rule.onNodeWithTag("hf_token_field").performTextInput("hf_e2e_token_value")
        rule.onNodeWithTag("hf_token_save").performClick()
        rule.waitForTag("hf_token_saved")
        hub.requests.clear()
        rule.onNodeWithTag("search_button").performClick()
        rule.waitUntil(10_000) { hub.requests.any { it.path.startsWith("/api/models?") } }
        assertTrue(hub.requests.filter { it.path.startsWith("/api/models") }.all { it.authorization == "Bearer hf_e2e_token_value" })
    }

    // ---- download ----

    @Test fun downloadShowsProgressThenInstallsAVerifiedFileAndActivatesIt() {
        hub.delivery = Delivery.HoldAfter(30_000)
        searchTiny()
        download(q4Id)
        rule.waitForTag("progress_$q4Id")
        rule.scrollToTag("hf_search_list", "cancel_$q4Id").assertIsDisplayed()

        hub.release()
        rule.waitForTag("installed_size_$q4Id", 20_000)
        val installed = app.store.find(q4Id)!!
        assertArrayEquals("the exact bytes the server sent", q4, installed.file.readBytes())
        assertEquals("the first installed model is activated", q4Id, app.store.activeModel.value?.id)
        rule.scrollToTag("hf_search_list", "active_badge_$q4Id").assertIsDisplayed()
        assertFalse("no .part file is left behind", app.store.partFile(installed.spec).exists())
    }

    @Test fun cancellingADownloadRemovesItsPartialFileAndInstallsNothing() {
        hub.delivery = Delivery.HoldAfter(30_000)
        searchTiny()
        download(q4Id)
        rule.waitForTag("progress_$q4Id")
        val partFile = app.store.partFile(pathsFor(q4Id))
        rule.waitUntil(10_000) { partFile.isFile && partFile.length() > 0 }

        rule.scrollToTag("hf_search_list", "cancel_$q4Id").performClick()
        rule.waitForTag("download_$q4Id")
        rule.waitUntil(10_000) { !partFile.exists() }
        assertFalse(app.store.isInstalled(q4Id))
        assertNull(app.store.activeModel.value)
    }

    @Test fun aPartialFileLeftByAKilledProcessIsResumedWithARangeRequest() {
        searchTiny()
        val partFile = app.store.partFile(pathsFor(q4Id))
        partFile.parentFile!!.mkdirs()
        partFile.writeBytes(q4.copyOfRange(0, 40_000)) // what a download killed mid-way leaves on disk
        hub.requests.clear()

        download(q4Id)
        rule.waitForTag("installed_size_$q4Id", 20_000)
        val request = hub.fileRequests().single()
        assertEquals("bytes=40000-", request.range)
        assertArrayEquals("resumed file is byte-identical to the original", q4, app.store.find(q4Id)!!.file.readBytes())
    }

    @Test fun aDroppedConnectionResumesAutomaticallyFromTheBytesAlreadyOnDisk() {
        hub.delivery = Delivery.DropAfter(30_000)
        searchTiny()
        download(q8Id)
        rule.waitForTag("installed_size_$q8Id", 30_000)
        val requests = hub.fileRequests()
        assertEquals(2, requests.size)
        assertNull("first request is a full download", requests[0].range)
        assertTrue("retry resumes with Range: ${requests[1].range}", requests[1].range!!.startsWith("bytes=") && requests[1].range != "bytes=0-")
        assertArrayEquals(q8, app.store.find(q8Id)!!.file.readBytes())
    }

    @Test fun aFileThatFailsItsChecksumIsRejectedAndLeavesNothing() {
        searchTiny()
        search("corrupt")
        rule.scrollUntilTag("hf_search_list", "result_acme/Tiny-Corrupt-GGUF")
        download(corruptId)
        rule.waitForTag("error_$corruptId", 20_000)
        assertEquals("The downloaded file failed its integrity check.", rule.textOf("error_$corruptId"))
        assertFalse(app.store.isInstalled(corruptId))
        assertTrue("no partial or committed file remains", app.context.filesDir.walkTopDown().none { it.name.startsWith("Tiny-Corrupt") })
    }

    @Test fun aServerErrorShowsRetryAndTheRetrySucceedsOnceTheServerRecovers() {
        hub.delivery = Delivery.Status(500)
        searchTiny()
        search("chat")
        rule.scrollUntilTag("hf_search_list", "result_acme/Tiny-Chat-GGUF")
        download(chatId)
        rule.waitForTag("error_$chatId", 30_000)
        assertTrue(rule.textOf("error_$chatId").startsWith("Network problem: HTTP 500"))
        assertFalse(app.store.isInstalled(chatId))

        hub.delivery = Delivery.Normal
        rule.scrollToTag("hf_search_list", "download_$chatId").performClick() // the button reads "Retry"
        rule.waitForTag("installed_size_$chatId", 20_000)
        assertArrayEquals(chat, app.store.find(chatId)!!.file.readBytes())
    }

    @Test fun aGatedModelAsksForATokenAndDownloadsOnceOneIsSaved() {
        searchTiny()
        rule.scrollToText("hf_search_list", "gated", substring = true)
        hub.delivery = Delivery.Status(401)
        download(gatedId)
        rule.waitForTag("error_$gatedId", 20_000)
        assertEquals("This model is gated: add a Hugging Face token below.", rule.textOf("error_$gatedId"))

        rule.scrollToTag("hf_search_list", "hf_token_field").performTextInput("hf_gated_token")
        rule.scrollToTag("hf_search_list", "hf_token_save").performClick()
        rule.waitForTag("hf_token_saved")
        hub.delivery = Delivery.Normal
        hub.requests.clear()
        download(gatedId)
        rule.waitForTag("installed_size_$gatedId", 20_000)
        assertEquals("Bearer hf_gated_token", hub.fileRequests().first().authorization)
        assertArrayEquals(gatedBytes, app.store.find(gatedId)!!.file.readBytes())
    }

    // ---- select / delete ----

    private fun installTwoModels() {
        searchTiny()
        installFromSearch(q4Id) // becomes active
        installFromSearch(q8Id) // stays inactive
        backToSetup()
    }

    @Test fun selectingAnotherInstalledModelMovesTheActiveBadge() {
        installTwoModels()
        assertEquals(q4Id, app.store.activeModel.value?.id)
        rule.scrollToTag("ai_setup_list", "active_badge_$q4Id")
        rule.scrollToTag("ai_setup_list", "use_$q8Id").performClick()
        rule.waitUntil(10_000) { app.store.activeModel.value?.id == q8Id }
        rule.scrollToTag("ai_setup_list", "active_badge_$q8Id").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("active_badge_$q4Id").fetchSemanticsNodes().size)
        waitForStatus("Writing insights with ${spec(q8Id).displayName}")
    }

    @Test fun deletingAnInactiveModelKeepsTheActiveOne() {
        installTwoModels()
        val file = app.store.find(q8Id)!!.file
        rule.scrollToTag("ai_setup_list", "delete_$q8Id").performClick()
        rule.waitForTag("confirm_delete_dialog")
        assertTrue("nothing is removed before confirming", file.isFile)
        rule.onNodeWithTag("confirm_delete").performClick()
        rule.waitGone("installed_size_$q8Id")
        assertFalse(file.exists())
        assertEquals(q4Id, app.store.activeModel.value?.id)
    }

    @Test fun deletingTheActiveModelFallsBackToBuiltInRulesAndKeepsTheOtherInstalled() {
        installTwoModels()
        val activeFile = app.store.find(q4Id)!!.file
        val activeName = spec(q4Id).displayName
        waitForStatus("Writing insights with $activeName")

        rule.scrollToTag("ai_setup_list", "delete_$q4Id").performClick()
        rule.waitForTag("confirm_delete_dialog")
        rule.onNodeWithTag("confirm_delete").performClick()
        rule.waitGone("installed_size_$q4Id")

        assertFalse(activeFile.exists())
        assertNull("no model is active any more", app.store.activeModel.value)
        assertTrue("the other model is untouched and not auto-activated", app.store.isInstalled(q8Id))
        waitForStatus("Using built-in rules: No on-device model is installed and selected")
        rule.waitUntil(10_000) { app.runtime.loadedModelId == null }
    }

    @Test fun afterDeletingTheActiveModelInsightsAreWrittenByTheRulesAgain() {
        installTwoModels()
        rule.openTab("insights")
        rule.waitUntil(15_000) { rule.countTagsStartingWith("insight_generated_by_") > 0 }

        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "delete_$q4Id").performClick()
        rule.onNodeWithTag("confirm_delete").performClick()
        rule.waitGone("installed_size_$q4Id")

        rule.openTab("insights")
        rule.waitUntil(15_000) { rule.countTagsStartingWith("insight_generated_by_") == 0 }
        rule.scrollToText("insights_list", "High Cafe & Dining Velocity")
        assertEquals(0, rule.onAllNodesWithText("Rewritten on-device by the fake model.", substring = true).fetchSemanticsNodes().size)
    }

    // ---- restart ----

    @Test fun installedModelsAndTheActiveChoiceSurviveAnAppRestart() {
        installTwoModels()
        rule.scrollToTag("ai_setup_list", "use_$q8Id").performClick()
        rule.waitUntil(10_000) { app.store.activeModel.value?.id == q8Id }
        val q4File = app.store.find(q4Id)!!.file
        val q8File = app.store.find(q8Id)!!.file

        scenario = app.restart(scenario)
        rule.openTab("ai_setup")
        rule.waitForTag("ai_setup_list")
        assertTrue(q4File.isFile && q8File.isFile)
        rule.scrollToTag("ai_setup_list", "installed_size_$q4Id").assertIsDisplayed()
        rule.scrollToTag("ai_setup_list", "installed_size_$q8Id").assertIsDisplayed()
        rule.scrollToTag("ai_setup_list", "active_badge_$q8Id").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("active_badge_$q4Id").fetchSemanticsNodes().size)
        assertNotNull(app.store.find(q4Id))
        waitForStatus("Writing insights with ${spec(q8Id).displayName}")
    }
}

/** A spec carrying only what [com.example.finsightai.llm.ModelStore] needs to locate [id]'s files. */
private fun pathsFor(id: String): ModelSpec = ModelSpec(
    id = id, displayName = id, family = com.example.finsightai.llm.ModelFamily.COMMUNITY,
    repoId = id.substringBeforeLast('/'), fileName = id.substringAfterLast('/'),
    sizeBytes = 0, quantization = "", minRamMb = 0, recommendedRamMb = 0, gated = false, license = "", description = "",
    source = com.example.finsightai.llm.ModelSource.SEARCH,
)
