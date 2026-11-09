package com.example.finsightai.e2e

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import com.example.finsightai.util.Money
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One scenario in two halves. In the normal suite both halves run in one process with an in-app
 * restart between them. `scripts/e2e-process-restart.sh` runs them as two separate `am instrument`
 * processes with `am force-stop` in between, i.e. a real process death:
 *   -e restartPhase write   then   -e restartPhase verify
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestartE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val phase: String? = InstrumentationRegistry.getArguments().getString("restartPhase")
    private val namespace = "process-restart"
    private val seed = DefaultFinSightRepository.seedMarkSantosData()

    private fun write(app: AppFixture) = app.launch().let { scenario ->
        rule.openTab("transactions")
        rule.waitForTag("tx_count")
        rule.onNodeWithTag("fab_add").performClick()
        rule.waitForTag("tx_title")
        rule.onNodeWithTag("tx_title").performTextInput("Restart Probe")
        rule.onNodeWithTag("tx_amount").performTextInput("777.77")
        rule.onNodeWithTag("tx_category_TRANSPORT").performClick()
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")
        rule.scrollToTag("transactions_list", "delete_tx_tx_sub_7").performClick()
        rule.onNodeWithTag("confirm_delete_tx").performClick()
        rule.waitGone("confirm_delete_tx")

        rule.openTab("dashboard")
        rule.onNodeWithTag("theme_chip").performClick()
        rule.onNodeWithTag("theme_option_${AppTheme.MIDNIGHT_BLUE.name}").performClick()

        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "backend_codex").performClick()
        rule.waitUntil(10_000) { app.prefs.choice.value == BackendChoice.CODEX }
        rule.scrollToTag("ai_setup_list", "open_hf_search").performClick()
        rule.waitForTag("hf_token_field")
        rule.onNodeWithTag("hf_token_field").performTextInput("hf_restart_probe")
        rule.onNodeWithTag("hf_token_save").performClick()
        rule.waitForTag("hf_token_saved")
        scenario
    }

    private fun verify(app: AppFixture) {
        rule.waitForTag("tab_dashboard")
        rule.waitForTag("dashboard_outflow")
        rule.onNodeWithText(AppTheme.MIDNIGHT_BLUE.shortName).assertExists()
        val spent = seed.filter { it.isExpense && it.category != TransactionCategory.SALARY }.sumOf { it.amount } - 280.0 + 777.77
        assertEquals(Money.format(spent), rule.textOf("dashboard_outflow"))

        rule.openTab("transactions")
        assertEquals("${seed.size} recorded entries", rule.textOf("tx_count"))
        rule.scrollToText("transactions_list", "Restart Probe")
        assertEquals(false, rule.hasTag("tx_tx_sub_7"))

        rule.openTab("ai_setup")
        rule.scrollToTag("ai_setup_list", "backend_codex").assertIsSelected()
        assertEquals(BackendChoice.CODEX, app.prefs.choice.value)
        assertEquals("hf_restart_probe", app.hfTokens.get())
    }

    @Test fun dataAndSettingsComeBackAfterTheAppRestarts() {
        when (phase) {
            "write" -> {
                val app = AppFixture(namespace = namespace)
                write(app)
                // Left on disk on purpose for the verify process.
            }
            "verify" -> {
                val app = AppFixture(namespace = namespace, fresh = false)
                app.launch()
                try { verify(app) } finally { app.cleanup() }
            }
            else -> {
                val app = AppFixture(namespace = namespace)
                try {
                    val scenario = write(app)
                    app.restart(scenario)
                    verify(app)
                } finally { app.cleanup() }
            }
        }
    }
}
