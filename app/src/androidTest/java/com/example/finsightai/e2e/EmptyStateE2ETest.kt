package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/**
 * A brand-new install: no transactions, no demo persona, and every screen says what to do next instead
 * of showing zeros or made-up numbers. Nothing here comes back after the user adds and removes data.
 */
@RunWith(AndroidJUnit4::class)
class EmptyStateE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture(transactions = emptyList())
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun assertNoDemoPersona() {
        listOf("Mark Santos", "Apex", "Junior SWE").forEach {
            assertEquals("'$it' must not appear", 0, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun dashboardIsEmptyAndExplainsItself() {
        assertEquals(Money.format(0.0), rule.textOf("dashboard_outflow"))
        rule.onNodeWithText("Welcome back").assertIsDisplayed()
        assertNoDemoPersona()

        // Without spending, the simulator card does not promise a figure.
        rule.scrollToText("dashboard_list", "Forward Simulator")
        assertEquals(
            "Model how small cutbacks on cafes, tech and subscriptions compound over 12 months",
            rule.textsOf("simulator_card").last()
        )
        rule.scrollToTag("dashboard_list", "breakdown_empty").assertIsDisplayed()
        rule.scrollToTag("dashboard_list", "insights_preview_empty").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("35,000", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun transactionsTabInvitesTheFirstEntry() {
        rule.openTab("transactions")
        assertEquals("0 recorded entries", rule.textOf("tx_count"))
        rule.onNodeWithTag("tx_empty").assertIsDisplayed()
        rule.onNodeWithText("No transactions yet").assertIsDisplayed()
        rule.onNodeWithTag("fab_add").assertIsDisplayed()
        assertNoDemoPersona()
    }

    @Test fun insightsTabHasAnEmptyStateInsteadOfInventedAdvice() {
        rule.openTab("insights")
        rule.onNodeWithTag("insights_empty").assertIsDisplayed()
        rule.onNodeWithText("No insights yet").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Emergency Fund on Track", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun simulatorTabSaysWhyEverythingIsZero() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.onNodeWithTag("sim_empty_hint").assertIsDisplayed()
        assertEquals("+${Money.format(0.0)}/mo", run {
            rule.scrollToTag("simulator_list", "sim_monthly_freed")
            rule.textOf("sim_monthly_freed")
        })
    }

    @Test fun simulatorTabShowsNoPresetSavingsGoals() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.scrollToTag("simulator_list", "goals_empty")
        rule.onNodeWithTag("goals_empty").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("goals_section").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Emergency Reserve", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun addingThenDeletingEverythingGoesBackToEmptyAndStaysThereAfterARestart() {
        rule.openTab("transactions")
        rule.onNodeWithTag("fab_add").performClick()
        rule.waitForTag("tx_title")
        rule.onNodeWithTag("tx_title").performTextInput("First coffee")
        rule.onNodeWithTag("tx_amount").performTextInput("150")
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")
        assertEquals("1 recorded entries", rule.textOf("tx_count"))
        assertEquals(0, rule.onAllNodesWithText("No transactions yet").fetchSemanticsNodes().size)

        rule.openTab("insights")
        assertEquals("with data the insights come back", 0, rule.onAllNodesWithText("No insights yet").fetchSemanticsNodes().size)

        rule.openTab("transactions")
        val id = app.database().getAllTransactions().single().id
        rule.scrollToTag("transactions_list", "delete_tx_$id").performClick()
        rule.onNodeWithTag("confirm_delete_tx").performClick()
        rule.waitGone("confirm_delete_tx")
        assertEquals("0 recorded entries", rule.textOf("tx_count"))
        rule.onNodeWithText("No transactions yet").assertIsDisplayed()

        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_outflow")
        rule.openTab("transactions")
        assertEquals("deleting everything never brings demo data back", "0 recorded entries", rule.textOf("tx_count"))
        assertEquals(0, app.database().getAllTransactions().size)
        assertNoDemoPersona()
    }
}
