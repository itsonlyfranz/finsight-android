package com.example.finsightai.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.MainActivity
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.llm.CuratedModels
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.theme.AppTheme
import com.example.finsightai.util.CsvExporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The data controls on the Settings screen against the real SQLite database and the real on-disk AI
 * settings: Export transactions (CSV), Delete all transactions, Delete all savings goals, Delete all data
 * and Reset everything. Each path is checked on screen, in the database and after an app restart, and
 * none of them may ever bring sample data back.
 */
@RunWith(AndroidJUnit4::class)
class DataControlsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private val seed = SampleData.markSantosTransactions()
    private val goalA = SavingsGoal("goal-a", "Emergency fund", 100_000.0, 5_000.0, "🎯", "")
    private val goalB = SavingsGoal("goal-b", "New laptop", 60_000.0, 0.0, "💻", "")
    private val app = AppFixture(goals = listOf(goalA, goalB))
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val chooserIntents = java.util.concurrent.CopyOnWriteArrayList<Intent>()
    private val shareMonitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) return null
            chooserIntents += intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    /** A tiny stand-in for a downloaded model, so there is an "active model" to clear. */
    private val spec = CuratedModels.lfm3.copy(sizeBytes = 2048)

    @Before fun setUp() {
        // Fresh exports from earlier tests (kept for 10 minutes by design) must not leak into this one.
        File(app.context.cacheDir, "exports").deleteRecursively()
        // The system share sheet never opens: the monitor answers it and keeps the intent the app tried to start.
        InstrumentationRegistry.getInstrumentation().addMonitor(shareMonitor)
        db {
            it.setUserName("Juan")
            it.setMonthlyIncome(61_000.0)
            it.setSetting("app_theme", AppTheme.CYBER_SLATE.name)
        }
    }

    @After fun tearDown() {
        runCatching { scenario.close() }
        InstrumentationRegistry.getInstrumentation().removeMonitor(shareMonitor)
        app.cleanup()
    }

    // ---- helpers ----

    /** Runs [block] on a short-lived handle to the same database file the app uses, then closes it. */
    private fun <T> db(block: (FinSightDatabaseHelper) -> T): T {
        val helper = app.database()
        try {
            return block(helper)
        } finally {
            helper.close()
        }
    }

    private fun launchApp() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
    }

    private fun restart() {
        scenario = app.restart(scenario)
        rule.waitUntil(10_000) { rule.hasTag("tab_dashboard") || rule.hasTag("onboarding_screen") }
    }

    private fun openSettings() {
        rule.onNodeWithTag("settings_button").performClick()
        rule.waitForTag("settings_screen")
    }

    private fun tapOnSettings(tag: String) {
        rule.scrollToTag("settings_list", tag).performClick()
    }

    private fun assertDialog(title: String) {
        rule.waitForTag("data_dialog")
        assertEquals(title, rule.textOf("data_dialog_title"))
    }

    private fun confirm() {
        rule.onNodeWithTag("data_dialog_confirm").performClick()
        rule.waitGone("data_dialog")
    }

    private fun cancel() {
        rule.onNodeWithTag("data_dialog_cancel").performClick()
        rule.waitGone("data_dialog")
    }

    private fun installModelAndSignInToAi() {
        runBlocking {
            val part = app.store.partFile(spec)
            part.parentFile?.mkdirs()
            part.writeBytes(ByteArray(2048) { 7 })
            app.store.commit(spec, part)
            app.store.setActive(spec.id)
            app.hfTokens.set("hf_e2e_token")
            app.codexClient.approved = true
            app.codexAuth.signInWithDeviceCode()
            app.codex.availableModels()
            app.codex.select("gpt-fake-mini")
        }
        app.prefs.select(BackendChoice.CODEX)
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedIn)
        assertEquals("gpt-fake-mini", app.codex.selectedModel.value)
        assertEquals(spec.id, app.store.activeModel.value?.id)
    }

    private fun assertAiSettingsKept() {
        assertEquals(BackendChoice.CODEX, app.prefs.choice.value)
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedIn)
        // The account's model is resolved from the saved choice on first use, as AI Setup does.
        runBlocking { app.codex.availableModels() }
        assertEquals("gpt-fake-mini", app.codex.selectedModel.value)
        assertEquals("hf_e2e_token", app.hfTokens.get())
        assertEquals(spec.id, app.store.activeModel.value?.id)
    }

    private fun assertAiSettingsCleared() {
        assertEquals(BackendChoice.ON_DEVICE, app.prefs.choice.value)
        assertTrue(app.codexAuth.state.value is CodexAuthState.SignedOut)
        assertNull(app.codex.selectedModel.value)
        assertNull(app.hfTokens.get())
        assertFalse(app.hfTokens.hasToken())
        assertNull(app.store.activeModel.value)
        // ... and on disk, not just in memory.
        val codexPrefs = app.context.getSharedPreferences(app.codexPrefsName, android.content.Context.MODE_PRIVATE)
        assertNull(codexPrefs.getString("codex_model", null))
        val modelPrefs = app.context.getSharedPreferences(app.modelPrefsName, android.content.Context.MODE_PRIVATE)
        assertNull(modelPrefs.getString("active_model_id", null))
    }

    private fun exportedFiles(): List<File> =
        File(app.context.cacheDir, "exports").listFiles()?.toList().orEmpty()

    /** The CSV the share sheet was last asked to send, read back through the app's FileProvider. */
    private fun sharedCsv(): String {
        rule.waitUntil(10_000) { chooserIntents.isNotEmpty() }
        val chooser = chooserIntents.last()
        chooserIntents.clear()
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the CSV is attached as a file", uri)
        return app.context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun assertNoSampleDataAnywhere() {
        val db = app.database()
        try {
            assertEquals(emptyList<Any>(), db.getAllTransactions())
            assertEquals(emptyList<Any>(), db.getAllGoals())
        } finally {
            db.close()
        }
    }

    private fun skipIntro() {
        rule.waitForTag("onboarding_screen")
        rule.onNodeWithTag("onboarding_skip").performClick()
        rule.waitForTag("dashboard_outflow")
    }

    // ---- Export transactions (CSV) ----

    @Test fun exportingFromSettingsSharesEveryTransactionAsACsvFile() {
        launchApp()
        openSettings()
        rule.scrollToTag("settings_list", "settings_export_csv").assertIsEnabled().performClick()

        val csv = sharedCsv()
        assertEquals(CsvExporter.generateCsvContent(db { it.getAllTransactions() }), csv)
        val lines = csv.trimEnd().lines()
        assertEquals("ID,Date,Title,Category,Type,Amount,Notes", lines.first())
        assertEquals("a header plus one row per transaction", seed.size + 1, lines.size)
        assertTrue(csv.contains("Apex Systems SWE Paycheck"))
        assertEquals("exporting deletes nothing", seed.size, db { it.getAllTransactions() }.size)
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test fun withNoTransactionsThereIsNothingToExport() {
        db { it.deleteAllTransactions() }
        launchApp()
        openSettings()
        rule.scrollToTag("settings_list", "settings_export_csv").assertIsNotEnabled()
        rule.scrollToTag("settings_list", "settings_export_empty").assertIsDisplayed()
    }

    // ---- Delete all transactions ----

    @Test fun deleteAllTransactionsAsksFirstThenClearsOnlyTransactions() {
        launchApp()
        openSettings()

        tapOnSettings("settings_delete_transactions")
        assertDialog("Delete all transactions?")
        assertTrue(rule.textOf("data_dialog_message_0").contains("all ${seed.size} transactions"))
        cancel()
        assertEquals("cancel deletes nothing", seed.size, db { it.getAllTransactions() }.size)

        tapOnSettings("settings_delete_transactions")
        confirm()
        rule.waitForTag("settings_data_status")
        assertEquals("All transactions deleted", rule.textOf("settings_data_status"))

        db { db ->
            assertEquals(emptyList<Any>(), db.getAllTransactions())
            assertEquals("goals are kept", listOf(goalA.id, goalB.id), db.getAllGoals().map { it.id })
            assertEquals("Juan", db.getUserName())
            assertEquals(61_000.0, db.getMonthlyIncome(), 0.0)
            assertEquals(true, db.getOnboardingCompleted())
            assertEquals(AppTheme.CYBER_SLATE.name, db.getSetting("app_theme", ""))
        }

        rule.onNodeWithTag("settings_back").performClick()
        rule.waitGone("settings_screen")
        assertEquals("Hi, Juan", rule.textOf("dashboard_greeting"))
        rule.openTab("transactions")
        rule.waitForTag("tx_empty")
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.scrollToTag("simulator_list", "goal_edit_goal-a").assertIsDisplayed()

        restart()
        rule.waitForTag("tab_dashboard")
        assertEquals("nothing comes back after a restart", emptyList<Any>(), db { it.getAllTransactions() })
        rule.openTab("transactions")
        rule.waitForTag("tx_empty")
    }

    @Test fun deleteAllTransactionsCanExportFirstAndTheExportSurvivesTheDelete() {
        launchApp()
        openSettings()
        tapOnSettings("settings_delete_transactions")
        rule.waitForTag("data_dialog_export")
        rule.onNodeWithTag("data_dialog_export").performClick()
        assertEquals(CsvExporter.generateCsvContent(db { it.getAllTransactions() }), sharedCsv())
        assertEquals("the temporary CSV exists while sharing", 1, exportedFiles().size)
        rule.onNodeWithTag("data_dialog").assertIsDisplayed()

        confirm()
        rule.waitForTag("settings_data_status")
        assertEquals(emptyList<Any>(), db { it.getAllTransactions() })
        assertEquals("the export outlives the delete", 1, exportedFiles().size)
        assertTrue("and is still readable", exportedFiles().single().readText().lines().size > 2)

        val old = File(app.context.cacheDir, "exports/old.csv").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 11 * 60 * 1000L)
        }
        restart()
        rule.waitUntil(10_000) { !old.exists() }
        assertEquals("only the fresh export is kept", 1, exportedFiles().size)
    }

    // ---- Delete all savings goals ----

    @Test fun deleteAllGoalsAsksFirstThenClearsOnlyGoals() {
        launchApp()
        openSettings()

        tapOnSettings("settings_delete_goals")
        assertDialog("Delete all savings goals?")
        assertEquals("no export offered for goals", 0, rule.onAllNodesWithTag("data_dialog_export").fetchSemanticsNodes().size)
        cancel()
        assertEquals(2, db { it.getAllGoals() }.size)

        tapOnSettings("settings_delete_goals")
        confirm()
        rule.waitForTag("settings_data_status")
        assertEquals("All savings goals deleted", rule.textOf("settings_data_status"))

        db { db ->
            assertEquals(emptyList<Any>(), db.getAllGoals())
            assertEquals("transactions are kept", seed.size, db.getAllTransactions().size)
            assertEquals("Juan", db.getUserName())
            assertEquals(61_000.0, db.getMonthlyIncome(), 0.0)
            assertEquals(AppTheme.CYBER_SLATE.name, db.getSetting("app_theme", ""))
        }

        rule.onNodeWithTag("settings_back").performClick()
        rule.openTab("simulator")
        rule.waitForTag("goals_empty")

        restart()
        rule.waitForTag("tab_dashboard")
        assertEquals(emptyList<Any>(), db { it.getAllGoals() })
        assertEquals(seed.size, db { it.getAllTransactions() }.size)
        rule.openTab("simulator")
        rule.waitForTag("goals_empty")
    }

    // ---- Delete all data ----

    @Test fun deleteAllDataExportsFirstThenWipesEverythingAndReturnsToTheIntroKeepingThemeAndAi() {
        installModelAndSignInToAi()
        launchApp()
        openSettings()

        tapOnSettings("settings_delete_all")
        assertDialog("Delete all data?")
        assertEquals(
            "This permanently deletes all your FinSight data on this phone. This can't be undone.",
            rule.textOf("data_dialog_message_0")
        )
        rule.onNodeWithTag("data_dialog_cancel").assertIsDisplayed()
        rule.onNodeWithTag("data_dialog_confirm").assertIsDisplayed()
        assertEquals("Delete", rule.textOf("data_dialog_confirm"))

        // Cancel first: nothing happens.
        cancel()
        assertEquals(seed.size, db { it.getAllTransactions() }.size)
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()

        // Export first, then delete.
        tapOnSettings("settings_delete_all")
        rule.waitForTag("data_dialog_export")
        rule.onNodeWithTag("data_dialog_export").performClick()
        val csv = sharedCsv()
        assertEquals(seed.size + 1, csv.trimEnd().lines().size)
        rule.onNodeWithTag("data_dialog").assertIsDisplayed()

        confirm()
        rule.waitForTag("onboarding_screen")
        assertEquals("1/4", rule.textOf("onboarding_step"))

        db { db ->
            assertEquals(emptyList<Any>(), db.getAllTransactions())
            assertEquals(emptyList<Any>(), db.getAllGoals())
            assertEquals("", db.getUserName())
            assertEquals(45_000.0, db.getMonthlyIncome(), 0.0)
            assertEquals(false, db.getOnboardingCompleted())
            assertEquals(TransactionCategory.values().size - 1, db.getAllBudgets().size)
            assertEquals("the theme is kept", AppTheme.CYBER_SLATE.name, db.getSetting("app_theme", ""))
        }
        assertAiSettingsKept()
        assertEquals("the export made before deleting survives", 1, exportedFiles().size)
        assertTrue(exportedFiles().single().readText().lines().size > 2)

        // A restart still shows the intro, and skipping it lands on an empty dashboard (no sample data).
        restart()
        rule.waitForTag("onboarding_screen")
        assertNoSampleDataAnywhere()
        assertEquals(AppTheme.CYBER_SLATE.name, db { it.getSetting("app_theme", "") })
        assertAiSettingsKept()
        skipIntro()
        rule.waitForTag("dashboard_greeting")
        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${com.example.finsightai.util.Money.format(45_000.0)}", rule.textOf("dashboard_target"))
        rule.waitForTag("breakdown_empty")
        rule.openTab("transactions")
        rule.waitForTag("tx_empty")
        rule.openTab("simulator")
        rule.waitForTag("goals_empty")
        assertNoSampleDataAnywhere()
    }

    @Test fun deleteAllDataLandsOnTheDashboardNotOnSettingsOnceTheIntroIsDone() {
        launchApp()
        openSettings()
        tapOnSettings("settings_delete_all")
        confirm()
        rule.waitForTag("onboarding_screen")
        rule.onNodeWithTag("onboarding_skip").performClick()
        rule.waitForTag("dashboard_outflow")
        assertEquals("settings is not left open behind the intro", 0, rule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().size)
        rule.onNodeWithTag("settings_button").assertIsDisplayed()
    }

    // ---- Reset everything ----

    @Test fun resetEverythingStatesWhatItRemovesAndAlsoClearsThemeAndAiSettings() {
        installModelAndSignInToAi()
        launchApp()
        openSettings()

        tapOnSettings("settings_reset_everything")
        assertDialog("Reset everything?")
        val message = rule.textOf("data_dialog_message_0")
        assertTrue(message, message.contains("AI sign-in and theme are also removed"))
        assertTrue(rule.textOf("data_dialog_message_1").contains("Downloaded model files stay"))

        cancel()
        assertAiSettingsKept()
        assertEquals(seed.size, db { it.getAllTransactions() }.size)

        tapOnSettings("settings_reset_everything")
        confirm()
        rule.waitForTag("onboarding_screen")
        rule.waitUntil(10_000) { app.codexAuth.state.value is CodexAuthState.SignedOut }

        db { db ->
            assertEquals(emptyList<Any>(), db.getAllTransactions())
            assertEquals(emptyList<Any>(), db.getAllGoals())
            assertEquals("", db.getUserName())
            assertEquals(45_000.0, db.getMonthlyIncome(), 0.0)
            assertEquals(false, db.getOnboardingCompleted())
            assertEquals("the theme is reset", AppTheme.DECK_EMERALD.name, db.getSetting("app_theme", AppTheme.DECK_EMERALD.name))
            assertFalse(db.hasSetting("app_theme"))
        }
        assertAiSettingsCleared()
        assertTrue("downloaded model files are kept", app.store.installed.value.any { it.id == spec.id })

        // The cleared settings are on disk, not just in memory: a new process sees them too.
        restart()
        rule.waitForTag("onboarding_screen")
        assertAiSettingsCleared()
        assertNoSampleDataAnywhere()
        skipIntro()
        rule.onNodeWithText(AppTheme.DECK_EMERALD.shortName).assertIsDisplayed()
        rule.openTab("ai_setup")
        rule.waitForTag("ai_setup_list")
        rule.scrollToTag("ai_setup_list", "codex_status")
        assertEquals("Signed out", rule.textOf("codex_status"))
        rule.openTab("transactions")
        rule.waitForTag("tx_empty")
    }
}
