package com.example.finsightai.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dashboard, Forward Simulator (sliders + savings goals) and the peso display in the real activity.
 * Expected numbers are worked out here from the demo transactions, not read back from the engines.
 */
@RunWith(AndroidJUnit4::class)
class DashboardSimulatorE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val seed = DefaultFinSightRepository.seedMarkSantosData()

    private fun spent(category: TransactionCategory) = seed.filter { it.category == category && it.isExpense }.sumOf { it.amount }
    private val outflow = seed.filter { it.isExpense && it.category != TransactionCategory.SALARY }.sumOf { it.amount }

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    // ---- dashboard ----

    @Test fun dashboardShowsTheDemoProfileOutflowTargetAndBuffer() {
        rule.onNodeWithText("Welcome back, Mark Santos").assertIsDisplayed()
        assertEquals(Money.format(outflow), rule.textOf("dashboard_outflow"))
        assertEquals("Target: ${Money.format(45000.0)}", rule.textOf("dashboard_target"))
        val percent = (outflow / 45000.0 * 100).toInt()
        rule.onNodeWithText("$percent% of income spent").assertIsDisplayed()
        rule.onNodeWithText("${Money.format(45000.0 - outflow)} buffer remaining").assertIsDisplayed()
    }

    @Test fun categoryBreakdownListsEverySpendingCategoryInPesos() {
        rule.scrollToText("dashboard_list", "Category Breakdown")
        val spending = seed.filter { it.isExpense && it.category != TransactionCategory.SALARY }.map { it.category }.distinct()
        rule.onNodeWithText("${spending.size} categories").assertIsDisplayed()
        spending.forEach { category ->
            rule.scrollToText("dashboard_list", Money.format(spent(category)))
            rule.onAllNodesWithText(category.displayName).onFirst().assertIsDisplayed()
            rule.onAllNodesWithText(Money.format(spent(category))).onFirst().assertIsDisplayed()
        }
    }

    @Test fun dashboardLinksOpenTheSimulatorAndTheInsights() {
        rule.scrollToText("dashboard_list", "Forward Simulator")
        rule.onNodeWithText("Forward Simulator").performClick()
        rule.waitForTag("simulator_list")
        rule.scrollToText("simulator_list", "Lifestyle Inflation Levers")

        rule.openTab("dashboard")
        rule.scrollToText("dashboard_list", "View All")
        rule.onNodeWithText("View All").performClick()
        rule.waitForText("AI Financial Intelligence")
    }

    @Test fun noScreenShowsADollarSign() {
        listOf("dashboard", "transactions", "insights", "simulator", "ai_setup").forEach { tab ->
            rule.openTab(tab)
            rule.waitForIdle()
            assertEquals("$tab shows no \$", 0, rule.onAllNodesWithText("$", substring = true).fetchSemanticsNodes().size)
        }
    }

    // ---- simulator ----

    private fun setCut(category: TransactionCategory, fraction: Float) {
        rule.scrollToTag("simulator_list", "slider_${category.name}")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        rule.waitForIdle()
    }

    private fun freed(): String {
        rule.scrollToTag("simulator_list", "sim_monthly_freed")
        return rule.textOf("sim_monthly_freed")
    }

    @Test fun defaultLeversFreeTwentyTwentyAndFifteenPercentOfTheirCategories() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        val expected = spent(TransactionCategory.CAFES_DINING) * 0.20 +
            spent(TransactionCategory.TECH_GADGETS) * 0.20 +
            spent(TransactionCategory.SUBSCRIPTIONS) * 0.15
        assertEquals("+${Money.format(expected)}/mo", freed())
        rule.scrollToText("simulator_list", "Current: ${Money.format(spent(TransactionCategory.CAFES_DINING))}/mo")
    }

    @Test fun movingTheSlidersRecomputesTheFreedCashflow() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        setCut(TransactionCategory.CAFES_DINING, 0.5f)
        val half = spent(TransactionCategory.CAFES_DINING) * 0.5 +
            spent(TransactionCategory.TECH_GADGETS) * 0.20 + spent(TransactionCategory.SUBSCRIPTIONS) * 0.15
        assertEquals("+${Money.format(half)}/mo", freed())

        setCut(TransactionCategory.CAFES_DINING, 0f)
        setCut(TransactionCategory.TECH_GADGETS, 0f)
        setCut(TransactionCategory.SUBSCRIPTIONS, 0f)
        assertEquals("+${Money.format(0.0)}/mo", freed())
    }

    @Test fun anAddedExpenseChangesTheSimulatorBaseline() {
        rule.openTab("transactions")
        rule.onNodeWithTag("fab_add").performClick()
        rule.waitForTag("tx_title")
        rule.onNodeWithTag("tx_title").performTextInput("E2E Brunch")
        rule.onNodeWithTag("tx_amount").performTextInput("1000")
        rule.onNodeWithTag("tx_category_CAFES_DINING").performClick()
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")

        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        val cafes = spent(TransactionCategory.CAFES_DINING) + 1000.0
        rule.scrollToText("simulator_list", "Current: ${Money.format(cafes)}/mo")
        val expected = cafes * 0.20 + spent(TransactionCategory.TECH_GADGETS) * 0.20 + spent(TransactionCategory.SUBSCRIPTIONS) * 0.15
        assertEquals("+${Money.format(expected)}/mo", freed())
    }

    // ---- goals ----

    @Test fun savingsGoalsShowTheirProgressInPesos() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.scrollToTag("simulator_list", "goals_section")
        SavingsGoal.PREPOPULATED_GOALS.forEach { goal ->
            rule.scrollToText("simulator_list", goal.title)
            rule.onNodeWithText(goal.title).assertIsDisplayed()
            rule.onNodeWithText("${Money.format(goal.currentSaved)} of ${Money.format(goal.targetAmount)}").assertIsDisplayed()
        }
    }

    @Test fun goalTimelineBadgesFollowTheFreedCashflow() {
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        val freed = spent(TransactionCategory.CAFES_DINING) * 0.20 +
            spent(TransactionCategory.TECH_GADGETS) * 0.20 + spent(TransactionCategory.SUBSCRIPTIONS) * 0.15
        SavingsGoal.PREPOPULATED_GOALS.forEach { goal ->
            rule.scrollToText("simulator_list", goal.title)
            rule.waitForText("at +${Money.formatWhole(freed)}/mo", substring = true)
        }

        setCut(TransactionCategory.CAFES_DINING, 0f)
        setCut(TransactionCategory.TECH_GADGETS, 0f)
        setCut(TransactionCategory.SUBSCRIPTIONS, 0f)
        SavingsGoal.PREPOPULATED_GOALS.forEach { goal ->
            rule.scrollToText("simulator_list", goal.title)
            rule.waitForText("at baseline", substring = true)
            assertEquals(0, rule.onAllNodesWithText("at +", substring = true).fetchSemanticsNodes().size)
        }
    }
}
