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
import com.example.finsightai.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real savings goals: the "Add a goal" invite on the empty goals state, editing, deleting and persistence. */
@RunWith(AndroidJUnit4::class)
class GoalsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture(transactions = emptyList())
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        scenario = app.launch()
        rule.waitForTag("dashboard_outflow")
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun fill(tag: String, text: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        rule.onNodeWithTag(tag).performTextReplacement(text)
    }

    @Test fun theEmptyGoalsStateInvitesTheFirstGoal() {
        rule.scrollToTag("simulator_list", "goals_empty").assertIsDisplayed()
        rule.onNodeWithTag("goal_add").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("goals_section").fetchSemanticsNodes().size)
    }

    @Test fun aGoalCanBeAddedEditedAndDeletedAndSurvivesARestart() {
        rule.scrollToTag("simulator_list", "goal_add").performClick()
        rule.waitForTag("goal_title")

        // Validation first: nothing is saved for a blank name or a missing target.
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitForTag("goal_error")
        fill("goal_title", "Laptop")
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitForTag("goal_error")
        assertTrue(app.database().getAllGoals().isEmpty())

        fill("goal_target", "50,000")
        fill("goal_saved", "10000")
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitGone("goal_title")

        rule.scrollToTag("simulator_list", "goals_section").assertIsDisplayed()
        rule.onNodeWithText("Laptop").assertIsDisplayed()
        rule.onNodeWithText("${Money.format(10_000.0)} of ${Money.format(50_000.0)}").assertIsDisplayed()
        val goal = app.database().getAllGoals().single()
        assertEquals("Laptop", goal.title)
        assertEquals(50_000.0, goal.targetAmount, 0.0)
        assertEquals(10_000.0, goal.currentSaved, 0.0)
        assertEquals(0, rule.onAllNodesWithTag("goals_empty").fetchSemanticsNodes().size)

        // Persisted across a restart.
        scenario = app.restart(scenario)
        rule.waitForTag("dashboard_outflow")
        rule.openTab("simulator")
        rule.waitForTag("simulator_list")
        rule.onNodeWithText("Laptop").assertExists()

        // Edit.
        rule.scrollToTag("simulator_list", "goal_edit_${goal.id}").performClick()
        rule.waitForTag("goal_title")
        fill("goal_title", "Gaming laptop")
        fill("goal_target", "60000")
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitGone("goal_title")
        rule.onNodeWithText("Gaming laptop").assertIsDisplayed()
        rule.onNodeWithText("${Money.format(10_000.0)} of ${Money.format(60_000.0)}").assertIsDisplayed()
        val edited = app.database().getAllGoals().single()
        assertEquals(goal.id, edited.id)
        assertEquals("Gaming laptop", edited.title)
        assertEquals(60_000.0, edited.targetAmount, 0.0)

        // Delete (with a confirmation), after which the invite is back.
        rule.scrollToTag("simulator_list", "goal_edit_${goal.id}").performClick()
        rule.waitForTag("goal_delete")
        rule.onNodeWithTag("goal_delete").performScrollTo().performClick()
        rule.waitForTag("confirm_delete_goal")
        rule.onNodeWithTag("confirm_delete_goal").performClick()
        rule.waitGone("goal_title")
        rule.scrollToTag("simulator_list", "goals_empty").assertIsDisplayed()
        assertTrue(app.database().getAllGoals().isEmpty())
    }

    @Test fun anotherGoalCanBeAddedNextToTheFirst() {
        rule.scrollToTag("simulator_list", "goal_add").performClick()
        rule.waitForTag("goal_title")
        rule.onNodeWithTag("goal_title").performTextInput("Trip")
        fill("goal_target", "20000")
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitGone("goal_title")

        rule.scrollToTag("simulator_list", "goal_add").performClick()
        rule.waitForTag("goal_title")
        rule.onNodeWithTag("goal_title").performTextInput("Fund")
        fill("goal_target", "80000")
        rule.onNodeWithTag("goal_save").performClick()
        rule.waitGone("goal_title")

        assertEquals(listOf("Trip", "Fund"), app.database().getAllGoals().map { it.title })
        rule.onNodeWithText("Trip").assertExists()
        rule.onNodeWithText("Fund").assertExists()
    }
}
