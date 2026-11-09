package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.MainActivity
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-launch intro on a brand-new install: shown once, skippable, and gone for good after it is
 * finished or skipped. The app is started with the intro NOT marked done (every other E2E test marks it
 * done in its setup instead of tapping through it).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture(transactions = emptyList(), onboardingDone = false)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("onboarding_screen")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun next() {
        rule.onNodeWithTag("onboarding_next").performClick()
        rule.waitForIdle()
    }

    private fun goToLastPage() {
        repeat(3) { next() }
        rule.waitForTag("onboarding_page_4")
    }

    private fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        rule.onNodeWithTag(tag).performTextInput(text)
    }

    private fun assertIntroGone() {
        assertEquals(0, rule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().size)
        rule.onNodeWithTag("tab_dashboard").assertIsDisplayed()
    }

    @Test fun firstLaunchShowsTheIntroInsteadOfTheApp() {
        assertEquals("1/4", rule.textOf("onboarding_step"))
        rule.onNodeWithTag("onboarding_page_1").assertIsDisplayed()
        assertEquals("Welcome to FinSight", rule.textOf("onboarding_title"))
        rule.onNodeWithText("Works offline", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("onboarding_skip").assertIsDisplayed()
        assertEquals("the app behind the intro is not reachable", 0, rule.onAllNodesWithTag("tab_dashboard").fetchSemanticsNodes().size)
        assertNull("nothing is marked done yet", app.database().getOnboardingCompleted())
    }

    @Test fun fourPagesWalkForwardAndBackWithAPointerToAiSetup() {
        next()
        assertEquals("2/4", rule.textOf("onboarding_step"))
        assertEquals("Log expenses, get insights", rule.textOf("onboarding_title"))

        next()
        assertEquals("3/4", rule.textOf("onboarding_step"))
        rule.onNodeWithText("On-device models").assertIsDisplayed()
        rule.onNodeWithText("Codex sign-in").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_ai_pointer").assertIsDisplayed()
        assertTrue(rule.textOf("onboarding_ai_pointer").contains("AI Setup"))

        next()
        assertEquals("4/4", rule.textOf("onboarding_step"))
        rule.onNodeWithTag("onboarding_finish").assertIsDisplayed()
        assertEquals("the last page has Finish, not Next", 0, rule.onAllNodesWithTag("onboarding_next").fetchSemanticsNodes().size)

        rule.onNodeWithTag("onboarding_back").performClick()
        rule.waitForTag("onboarding_page_3")
        assertEquals("3/4", rule.textOf("onboarding_step"))
        rule.onNodeWithTag("onboarding_back").performClick()
        rule.onNodeWithTag("onboarding_back").performClick()
        rule.waitForTag("onboarding_page_1")
        assertEquals("no Back on the first page", 0, rule.onAllNodesWithTag("onboarding_back").fetchSemanticsNodes().size)
    }

    @Test fun finishNeedsAnIncomeAndAHalfFilledGoalIsRefused() {
        goToLastPage()
        rule.onNodeWithTag("onboarding_finish").performClick()
        rule.waitForTag("onboarding_error")
        assertTrue(rule.textOf("onboarding_error").contains("monthly income"))

        type("onboarding_income", "abc")
        rule.onNodeWithTag("onboarding_finish").performClick()
        rule.waitForTag("onboarding_error")

        rule.onNodeWithTag("onboarding_income").performTextReplacement("52000")
        type("onboarding_goal_title", "Half a goal")
        rule.onNodeWithTag("onboarding_finish").performClick()
        rule.waitForTag("onboarding_error")
        assertTrue(rule.textOf("onboarding_error").contains("goal"))

        rule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        assertNull("a refused finish marks nothing done", app.database().getOnboardingCompleted())
        assertEquals("and saves nothing", 45_000.0, app.database().getMonthlyIncome(), 0.0)
        assertTrue(app.database().getAllGoals().isEmpty())
    }

    @Test fun skippingLandsOnTheEmptyDashboardKeepsDefaultsAndNeverComesBack() {
        rule.onNodeWithTag("onboarding_skip").performClick()
        rule.waitForTag("dashboard_outflow")
        assertIntroGone()

        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${Money.format(45_000.0)}", rule.textOf("dashboard_target"))
        rule.onNodeWithTag("dashboard_add_first_expense").assertIsDisplayed()
        rule.onNodeWithText("Add your first expense").assertIsDisplayed()

        val db = app.database()
        assertEquals(true, db.getOnboardingCompleted())
        assertEquals(45_000.0, db.getMonthlyIncome(), 0.0)
        assertEquals("", db.getUserName())
        assertTrue(db.getAllGoals().isEmpty())

        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_outflow")
        assertIntroGone()
    }

    @Test fun skippingFromTheLastPageAlsoSavesNothing() {
        type("onboarding_name", "Maria")
        goToLastPage()
        type("onboarding_income", "99000")
        rule.onNodeWithTag("onboarding_skip").performClick()
        rule.waitForTag("dashboard_outflow")

        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${Money.format(45_000.0)}", rule.textOf("dashboard_target"))
        assertEquals("", app.database().getUserName())
    }

    @Test fun finishingWithNameIncomeAndGoalSavesThemAndNeverShowsTheIntroAgain() {
        type("onboarding_name", "Maria")
        goToLastPage()
        type("onboarding_income", "52,500")
        type("onboarding_goal_title", "Emergency fund")
        type("onboarding_goal_target", "100000")
        rule.onNodeWithTag("onboarding_finish").performClick()
        rule.waitForTag("dashboard_outflow")
        assertIntroGone()

        assertEquals("Hi, Maria", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${Money.format(52_500.0)}", rule.textOf("dashboard_target"))
        rule.onNodeWithTag("dashboard_add_first_expense").assertIsDisplayed()

        val db = app.database()
        assertEquals(true, db.getOnboardingCompleted())
        assertEquals(52_500.0, db.getMonthlyIncome(), 0.0)
        assertEquals("Maria", db.getUserName())
        val goal = db.getAllGoals().single()
        assertEquals("Emergency fund", goal.title)
        assertEquals(100_000.0, goal.targetAmount, 0.0)
        assertEquals(0.0, goal.currentSaved, 0.0)

        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.scrollToTag("simulator_list", "goals_section").assertIsDisplayed()
        rule.onNodeWithText("Emergency fund").assertIsDisplayed()

        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_outflow")
        assertIntroGone()
        assertEquals("Hi, Maria", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${Money.format(52_500.0)}", rule.textOf("dashboard_target"))
    }

    @Test fun incomeOnlyIsEnoughToFinish() {
        goToLastPage()
        type("onboarding_income", "30000")
        rule.onNodeWithTag("onboarding_finish").performClick()
        rule.waitForTag("dashboard_outflow")
        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        assertEquals("Target: ${Money.format(30_000.0)}", rule.textOf("dashboard_target"))
        assertTrue(app.database().getAllGoals().isEmpty())
    }

    @Test fun theFirstExpenseButtonOpensTheEntryFormAndDisappearsOnceThereIsData() {
        rule.onNodeWithTag("onboarding_skip").performClick()
        rule.waitForTag("dashboard_add_first_expense")
        rule.onNodeWithTag("dashboard_add_first_expense").performClick()
        rule.waitForTag("tx_title")
        rule.onNodeWithTag("tx_title").performTextInput("First coffee")
        rule.onNodeWithTag("tx_amount").performTextInput("150")
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")

        assertEquals(Money.format(150.0), rule.textOf("dashboard_outflow"))
        rule.waitGone("dashboard_add_first_expense")
    }
}

/** Someone who already uses the app (their database holds transactions) is never forced through the intro. */
@RunWith(AndroidJUnit4::class)
class OnboardingExistingInstallE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture(transactions = SampleData.markSantosTransactions(), onboardingDone = false)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    @Test fun anInstallThatAlreadyHasTransactionsGoesStraightToTheDashboard() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
        assertEquals(0, rule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().size)
        assertEquals("Welcome back", rule.textOf("dashboard_greeting"))
        assertEquals("existing data is untouched", SampleData.markSantosTransactions().size, app.database().getAllTransactions().size)
        assertEquals("and it is remembered, so deleting everything later does not bring the intro back", true, app.database().getOnboardingCompleted())
        assertEquals(0, rule.onAllNodesWithTag("dashboard_add_first_expense").fetchSemanticsNodes().size)
    }
}
