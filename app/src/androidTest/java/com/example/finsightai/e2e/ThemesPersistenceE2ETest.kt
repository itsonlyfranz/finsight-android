package com.example.finsightai.e2e

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.theme.AppTheme
import com.example.finsightai.theme.getFinSightColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Themes, and data that has to outlive the screen: an activity recreation (rotation, dark-mode flip)
 * and an app restart (new process: new object graph over what is on disk).
 */
@RunWith(AndroidJUnit4::class)
class ThemesPersistenceE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val seedSize = DefaultFinSightRepository.seedMarkSantosData().size

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("theme_chip")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun restart() {
        scenario = app.restart(scenario)
        rule.waitForTag("tab_dashboard")
    }

    private fun recreate() {
        scenario.recreate()
        rule.waitForTag("tab_dashboard")
    }

    private fun pickTheme(theme: AppTheme) {
        rule.onNodeWithTag("theme_chip").performClick()
        rule.onNodeWithTag("theme_option_${theme.name}").performClick()
        rule.waitForIdle()
    }

    /** The colour actually painted in the dashboard's top-left corner (padding area: plain background). */
    private fun paintedBackground(): Int {
        val bitmap = rule.onNodeWithTag("dashboard_list").captureToImage().asAndroidBitmap()
        return bitmap.getPixel(3, 3)
    }

    private fun assertThemeApplied(theme: AppTheme) {
        rule.onNodeWithTag("theme_chip").assertIsDisplayed()
        rule.onNodeWithText(theme.shortName).assertIsDisplayed()
        val expected = getFinSightColorScheme(theme).background.toArgb()
        assertEquals(
            "background painted for ${theme.displayName}: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(paintedBackground())}",
            expected, paintedBackground()
        )
    }

    // ---- themes ----

    @Test fun freshInstallStartsOnTheEmeraldDashboard() {
        assertThemeApplied(AppTheme.DECK_EMERALD)
        assertEquals(AppTheme.DECK_EMERALD.name, app.database().getSetting("app_theme", ""))
    }

    @Test fun everyThemeCanBeSelectedPaintsTheScreenAndIsSaved() {
        // Visit every theme twice in an order that includes each transition, ending back on the default.
        listOf(
            AppTheme.CYBER_SLATE, AppTheme.MIDNIGHT_BLUE, AppTheme.DECK_EMERALD,
            AppTheme.MIDNIGHT_BLUE, AppTheme.CYBER_SLATE, AppTheme.DECK_EMERALD,
        ).forEach { theme ->
            pickTheme(theme)
            assertThemeApplied(theme)
            assertEquals("theme persisted to the database", theme.name, app.database().getSetting("app_theme", ""))
        }
    }

    @Test fun themeMenuListsAllThemesWithTheirNames() {
        rule.onNodeWithTag("theme_chip").performClick()
        AppTheme.values().forEach { rule.onNodeWithTag("theme_option_${it.name}").assertIsDisplayed() }
        AppTheme.values().forEach { assertTrue(rule.onAllNodesWithText(it.displayName).fetchSemanticsNodes().isNotEmpty()) }
        rule.onNodeWithTag("theme_option_${AppTheme.DECK_EMERALD.name}").performClick()
    }

    @Test fun selectedThemeSurvivesRecreationAndRestartForEveryTheme() {
        AppTheme.values().forEach { theme ->
            pickTheme(theme)
            recreate()
            assertThemeApplied(theme)
            restart()
            assertThemeApplied(theme)
        }
    }

    @Test fun themeAppliesToEveryTabNotJustTheDashboard() {
        pickTheme(AppTheme.CYBER_SLATE)
        listOf("transactions", "insights", "simulator", "ai_setup", "dashboard").forEach { tab ->
            rule.openTab(tab)
            rule.waitForIdle()
        }
        assertThemeApplied(AppTheme.CYBER_SLATE)
    }

    // ---- transactions ----

    private fun addTransaction(title: String, amount: String) {
        rule.openTab("transactions")
        rule.onNodeWithTag("fab_add").performClick()
        rule.waitForTag("tx_title")
        rule.onNodeWithTag("tx_title").performTextInput(title)
        rule.onNodeWithTag("tx_amount").performTextInput(amount)
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")
    }

    @Test fun addedAndDeletedTransactionsSurviveRecreationAndRestart() {
        addTransaction("Persisted Coffee", "123.45")
        rule.openTab("transactions")
        rule.scrollToText("transactions_list", "Persisted Coffee")
        // Delete one of the demo rows as well.
        rule.scrollToTag("transactions_list", "delete_tx_tx_dining_6").performClick()
        rule.waitForTag("confirm_delete_tx")
        rule.onNodeWithTag("confirm_delete_tx").performClick()
        rule.waitGone("confirm_delete_tx")
        assertEquals("$seedSize recorded entries", rule.textOf("tx_count"))

        recreate()
        rule.openTab("transactions")
        assertEquals("$seedSize recorded entries", rule.textOf("tx_count"))

        restart()
        rule.openTab("transactions")
        assertEquals("$seedSize recorded entries", rule.textOf("tx_count"))
        rule.scrollToText("transactions_list", "Persisted Coffee")
        rule.onNodeWithText("Persisted Coffee").assertIsDisplayed()
        assertFalse("the deleted demo row stays deleted", rule.hasTag("tx_tx_dining_6"))
        rule.openTab("dashboard")
        // Spending recomputed from the stored rows after the restart.
        val seed = DefaultFinSightRepository.seedMarkSantosData()
        val spent = seed.filter { it.isExpense && it.category != com.example.finsightai.model.TransactionCategory.SALARY }.sumOf { it.amount } -
            1000.0 + 123.45
        assertEquals(com.example.finsightai.util.Money.format(spent), rule.textOf("dashboard_outflow"))
    }

    @Test fun theSelectedTabSurvivesRecreationButTheAppRestartsOnTheDashboard() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        recreate()
        rule.waitForTag("simulator_list")
        restart()
        rule.waitForTag("dashboard_outflow")
    }

    @Test fun demoDataAndIncomeAreSeededOnceAndNotAgainAfterARestart() {
        val db = app.database()
        assertEquals(45000.0, db.getMonthlyIncome(), 0.0)
        assertEquals(seedSize, db.getAllTransactions().size)
        restart()
        assertEquals("a restart must not seed the demo data a second time", seedSize, app.database().getAllTransactions().size)
    }
}
