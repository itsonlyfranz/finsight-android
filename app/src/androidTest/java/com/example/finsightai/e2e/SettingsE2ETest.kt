package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Name and monthly income can be changed after the intro, so a skip or a typo is never permanent. */
@RunWith(AndroidJUnit4::class)
class SettingsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun openSettings() {
        rule.onNodeWithTag("settings_button").performClick()
        rule.waitForTag("settings_screen")
    }

    private fun closeSettings() {
        rule.onNodeWithTag("settings_back").performClick()
        rule.waitGone("settings_screen")
        rule.waitForTag("dashboard_target")
    }

    @Test fun incomeShowsItsCurrentValueAndCanBeChangedAndSurvivesARestart() {
        assertEquals("Target: ${Money.format(45_000.0)}", rule.textOf("dashboard_target"))
        openSettings()
        rule.onNodeWithTag("settings_income").assertIsDisplayed()
        assertEquals("45000", rule.editableTextOf("settings_income"))

        rule.onNodeWithTag("settings_income").performTextReplacement("61000.5")
        rule.onNodeWithTag("settings_income_save").performClick()
        rule.waitForTag("settings_income_status")
        assertEquals("Saved", rule.textOf("settings_income_status"))
        assertEquals(61_000.5, app.database().getMonthlyIncome(), 0.0)

        closeSettings()
        assertEquals("Target: ${Money.format(61_000.5)}", rule.textOf("dashboard_target"))

        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_target")
        assertEquals("Target: ${Money.format(61_000.5)}", rule.textOf("dashboard_target"))
        openSettings()
        assertEquals("61000.5", rule.editableTextOf("settings_income"))
    }

    @Test fun anInvalidIncomeIsRefusedAndTheOldOneStays() {
        openSettings()
        listOf("0", "abc", "").forEach { bad ->
            rule.onNodeWithTag("settings_income").performTextReplacement(bad)
            rule.onNodeWithTag("settings_income_save").performClick()
            rule.waitForTag("settings_income_error")
        }
        assertEquals(45_000.0, app.database().getMonthlyIncome(), 0.0)
        closeSettings()
        assertEquals("Target: ${Money.format(45_000.0)}", rule.textOf("dashboard_target"))
    }

    @Test fun theNameCanBeSetChangedAndClearedAndTheGreetingFollows() {
        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        openSettings()
        rule.onNodeWithTag("settings_name").performTextReplacement("  Juan  ")
        rule.onNodeWithTag("settings_name_save").performClick()
        rule.waitForTag("settings_name_status")
        assertEquals("Juan", app.database().getUserName())
        closeSettings()
        assertEquals("Hi, Juan", rule.textOf("dashboard_greeting"))

        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_greeting")
        assertEquals("Hi, Juan", rule.textOf("dashboard_greeting"))

        openSettings()
        rule.onNodeWithTag("settings_name").performTextReplacement("")
        rule.onNodeWithTag("settings_name_save").performClick()
        rule.waitForTag("settings_name_status")
        closeSettings()
        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
    }

    @Test fun switchingTabsLeavesSettings() {
        openSettings()
        rule.openTab("transactions")
        assertEquals(0, rule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().size)
        rule.onNodeWithTag("tx_count").assertIsDisplayed()
    }
}

/** The text currently inside an editable field. */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.editableTextOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
