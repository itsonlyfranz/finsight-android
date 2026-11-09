package com.example.finsightai.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.MainActivity
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Transactions tab in the real activity over the real database: add, validation, delete,
 * every category filter, and the CSV that Export CSV actually writes and shares.
 */
@RunWith(AndroidJUnit4::class)
class TransactionsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = AppFixture()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val seed = SampleData.markSantosTransactions()
    private val seedOutflow = seed.filter { it.isExpense && it.category != TransactionCategory.SALARY }.sumOf { it.amount }

    @Before fun setUp() {
        scenario = app.launch()
        rule.openTab("transactions")
        rule.waitForTag("tx_count")
    }

    @After fun tearDown() {
        scenario.close()
        app.cleanup()
    }

    private fun count() = rule.textOf("tx_count")

    private fun openAddDialog() {
        rule.onNodeWithTag("fab_add").performClick()
        rule.waitForTag("tx_title")
    }

    private fun addTransaction(title: String, amount: String, category: TransactionCategory? = null, notes: String = "") {
        openAddDialog()
        rule.onNodeWithTag("tx_title").performTextInput(title)
        rule.onNodeWithTag("tx_amount").performTextInput(amount)
        category?.let { rule.onNodeWithTag("tx_category_${it.name}").performClick() }
        if (notes.isNotEmpty()) rule.onNodeWithTag("tx_notes").performTextInput(notes)
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")
    }

    private fun outflowOnDashboard(): String {
        rule.openTab("dashboard")
        rule.waitForTag("dashboard_outflow")
        val text = rule.textOf("dashboard_outflow")
        rule.openTab("transactions")
        return text
    }

    // ---- list ----

    @Test fun seededTransactionsAreListedInPesos() {
        assertEquals("${seed.size} recorded entries", count())
        rule.scrollToTag("transactions_list", "tx_tx_rent_1")
        rule.assertCardShows("tx_tx_rent_1", "-${Money.format(13500.0)}")
        rule.scrollToTag("transactions_list", "tx_tx_salary_1")
        rule.assertCardShows("tx_tx_salary_1", "+${Money.format(45000.0)}")
        assertEquals("no dollar signs anywhere on the tab", 0, rule.onAllNodesWithText("$", substring = true).fetchSemanticsNodes().size)
    }

    // ---- add ----

    @Test fun addedExpenseAppearsWithAmountNotesAndRaisesTheOutflow() {
        val before = outflowOnDashboard()
        assertEquals(Money.format(seedOutflow), before)

        addTransaction("E2E Latte", "185.5", TransactionCategory.CAFES_DINING, notes = "with Ana")
        assertEquals("${seed.size + 1} recorded entries", count())
        rule.scrollToText("transactions_list", "E2E Latte")
        rule.onNodeWithText("E2E Latte").assertIsDisplayed()
        rule.onNodeWithText("-${Money.format(185.5)}").assertIsDisplayed()
        rule.onNodeWithText("with Ana").assertIsDisplayed()

        assertEquals(Money.format(seedOutflow + 185.5), outflowOnDashboard())
    }

    @Test fun addedSalaryIsIncomeAndDoesNotChangeTheOutflow() {
        addTransaction("E2E Bonus", "2500", TransactionCategory.SALARY)
        rule.scrollToText("transactions_list", "E2E Bonus")
        rule.onNodeWithText("+${Money.format(2500.0)}").assertIsDisplayed()
        assertEquals(Money.format(seedOutflow), outflowOnDashboard())
    }

    @Test fun addDialogRejectsAnEmptyTitleAndABadAmountAndCancelAddsNothing() {
        openAddDialog()
        rule.onNodeWithTag("tx_save").performClick()
        assertEquals("Please enter a transaction title", rule.textOf("tx_error"))

        rule.onNodeWithTag("tx_title").performTextInput("Needs amount")
        rule.onNodeWithTag("tx_save").performClick()
        val amountError = "Please enter a valid amount greater than ${Money.format(0.0)}"
        assertEquals(amountError, rule.textOf("tx_error"))

        rule.onNodeWithTag("tx_amount").performTextInput("0")
        rule.onNodeWithTag("tx_save").performClick()
        assertEquals(amountError, rule.textOf("tx_error"))

        rule.onNodeWithTag("tx_cancel").performClick()
        rule.waitGone("tx_title")
        assertEquals("${seed.size} recorded entries", count())
        assertEquals(0, rule.onAllNodesWithText("Needs amount").fetchSemanticsNodes().size)
    }

    // ---- delete ----

    @Test fun deleteAsksForConfirmationCancelKeepsAndConfirmRemoves() {
        val tech = seed.first { it.id == "tx_tech_1" }
        rule.scrollToTag("transactions_list", "delete_tx_tx_tech_1").performClick()
        rule.waitForText("Delete Transaction?")
        rule.onNodeWithText("Are you sure you want to remove \"${tech.title}\" (${Money.format(tech.amount)})?").assertIsDisplayed()

        rule.onNodeWithTag("cancel_delete_tx").performClick()
        rule.waitGone("confirm_delete_tx")
        assertEquals("${seed.size} recorded entries", count())

        rule.onNodeWithTag("delete_tx_tx_tech_1").performClick()
        rule.onNodeWithTag("confirm_delete_tx").performClick()
        rule.waitGone("confirm_delete_tx")
        assertEquals("${seed.size - 1} recorded entries", count())
        assertEquals(0, rule.onAllNodesWithText(tech.title).fetchSemanticsNodes().size)
        assertEquals(Money.format(seedOutflow - tech.amount), outflowOnDashboard())
    }

    // ---- edit ----

    private fun openEditor(id: String) {
        rule.scrollToTag("transactions_list", "edit_tx_$id").performClick()
        rule.waitForTag("tx_title")
    }

    private fun fieldText(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun stored(id: String) = app.database().getAllTransactions().single { it.id == id }

    private fun header(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US))

    @Test fun tappingARowOpensTheEditorFilledWithItsCurrentValues() {
        val tx = seed.first { it.id == "tx_tech_1" }
        openEditor(tx.id)
        rule.onNodeWithText("Edit Transaction").assertIsDisplayed()
        assertEquals(tx.title, fieldText("tx_title"))
        assertEquals("2950", fieldText("tx_amount"))
        assertEquals("Banana switches upgrade", fieldText("tx_notes"))
        rule.onNode(hasTestTag("tx_date") and hasText(header(tx.date), substring = true)).assertIsDisplayed()
        rule.onNodeWithTag("tx_save").assertIsDisplayed()
        rule.onNodeWithText("Save Changes").assertIsDisplayed()
    }

    @Test fun editingAmountCategoryAndNoteSavesThemEverywhere() {
        val tx = seed.first { it.id == "tx_tech_1" }
        openEditor(tx.id)
        rule.onNodeWithTag("tx_amount").performTextReplacement("3100.5")
        rule.onNodeWithTag("tx_category_TRANSPORT").performClick()
        rule.onNodeWithTag("tx_notes").performTextReplacement("Went with a cheaper one")
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")

        assertEquals("an edit does not add or remove rows", "${seed.size} recorded entries", count())
        listOf("-${Money.format(3100.5)}", "Transport", "Went with a cheaper one").forEach { text ->
            rule.waitForIdle()
            rule.scrollToTag("transactions_list", "tx_${tx.id}")
            rule.assertCardShows("tx_${tx.id}", text)
        }
        assertEquals(Money.format(seedOutflow - 2950.0 + 3100.5), outflowOnDashboard())

        val saved = stored(tx.id)
        assertEquals(tx.copy(amount = 3100.5, category = TransactionCategory.TRANSPORT, notes = "Went with a cheaper one"), saved)
    }

    @Test fun editingTheDateMovesTheRowUnderThatDay() {
        val tx = seed.first { it.id == "tx_groc_1" } // the 3rd of this month
        val newDay = if (tx.date.dayOfMonth == 15) 16 else 15
        val newDate = tx.date.withDayOfMonth(newDay)
        openEditor(tx.id)
        rule.onNodeWithTag("tx_date").performClick()
        rule.waitForTag("tx_date_confirm")
        rule.onAllNodes(hasText("$newDay"), useUnmergedTree = true).printToLog("DPDEBUG")
        rule.onNode(hasText("$newDay") and hasClickAction()).performClick()
        rule.onNodeWithTag("tx_date_confirm").performClick()
        rule.waitGone("tx_date_confirm")
        rule.onNode(hasTestTag("tx_date") and hasText(header(newDate), substring = true)).assertIsDisplayed()
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")

        rule.scrollToText("transactions_list", header(newDate))
        rule.onNodeWithText(header(newDate)).assertIsDisplayed()
        assertEquals(newDate, stored(tx.id).date)
        assertEquals("${seed.size} recorded entries", count())
    }

    @Test fun editingSalaryIntoAnExpenseCategoryCountsItAsSpending() {
        openEditor("tx_salary_1")
        rule.onNodeWithTag("tx_category_LIFESTYLE").performClick()
        rule.onNodeWithTag("tx_save").performClick()
        rule.waitGone("tx_title")
        rule.scrollToTag("transactions_list", "tx_tx_salary_1")
        rule.assertCardShows("tx_tx_salary_1", "-${Money.format(45000.0)}")
        assertEquals(Money.format(seedOutflow + 45000.0), outflowOnDashboard())
        assertEquals(true, stored("tx_salary_1").isExpense)
    }

    @Test fun editRejectsABlankTitleAndABadAmountAndCancelChangesNothing() {
        val tx = seed.first { it.id == "tx_tech_2" }
        openEditor(tx.id)
        rule.onNodeWithTag("tx_title").performTextReplacement("  ")
        rule.onNodeWithTag("tx_save").performClick()
        assertEquals("Please enter a transaction title", rule.textOf("tx_error"))

        rule.onNodeWithTag("tx_title").performTextReplacement("Renamed")
        rule.onNodeWithTag("tx_amount").performTextReplacement("0")
        rule.onNodeWithTag("tx_save").performClick()
        assertEquals("Please enter a valid amount greater than ${Money.format(0.0)}", rule.textOf("tx_error"))
        rule.onNodeWithTag("tx_amount").performTextReplacement("NaN")
        rule.onNodeWithTag("tx_save").performClick()
        assertEquals("Please enter a valid amount greater than ${Money.format(0.0)}", rule.textOf("tx_error"))

        rule.onNodeWithTag("tx_cancel").performClick()
        rule.waitGone("tx_title")
        assertEquals(tx, stored(tx.id))
        assertEquals(Money.format(seedOutflow), outflowOnDashboard())
    }

    @Test fun theDeleteIconStillDeletesInsteadOfOpeningTheEditor() {
        rule.scrollToTag("transactions_list", "delete_tx_tx_tech_3").performClick()
        rule.waitForText("Delete Transaction?")
        assertEquals(0, rule.onAllNodesWithTag("tx_title").fetchSemanticsNodes().size)
        rule.onNodeWithTag("cancel_delete_tx").performClick()
    }

    // ---- the Add Expense button vs the list ----

    @Test fun theAddExpenseButtonNeverSitsOverTheList() {
        val fab = rule.onNodeWithTag("fab_add").getBoundsInRoot()
        val list = rule.onNodeWithTag("transactions_list").getBoundsInRoot()
        assertEquals("the list ends above the button, so no row can sit under it", true, list.bottom <= fab.top)

        // Scroll the oldest rows (the bottom of the list) into view: neither delete icon is under the button.
        listOf("tx_salary_1", "tx_rent_1").forEach { id ->
            val delete = rule.scrollToTag("transactions_list", "delete_tx_$id").getBoundsInRoot()
            val overlaps = delete.left < fab.right && delete.right > fab.left && delete.top < fab.bottom && delete.bottom > fab.top
            assertEquals("$id's delete icon must not be covered by Add Expense", false, overlaps)
        }
    }

    // ---- filters ----

    @Test fun everyCategoryFilterShowsOnlyThatCategoryAndAllRestoresTheList() {
        TransactionCategory.values().forEach { category ->
            selectFilter(category)
            val inCategory = seed.filter { it.category == category }
            assertEquals("${inCategory.size} recorded entries", count())
            if (inCategory.isEmpty()) {
                rule.onNodeWithTag("tx_empty").assertIsDisplayed()
            } else {
                inCategory.forEach { rule.scrollToTag("transactions_list", "tx_${it.id}") }
                seed.filter { it.category != category }.forEach {
                    assertFalse("${it.title} must be hidden under ${category.displayName}", rule.hasTag("tx_${it.id}"))
                }
            }
        }
        selectFilter(null)
        assertEquals("${seed.size} recorded entries", count())
    }

    @Test fun filterSurvivesLeavingAndReturningToTheTab() {
        selectFilter(TransactionCategory.SUBSCRIPTIONS)
        val expected = seed.count { it.category == TransactionCategory.SUBSCRIPTIONS }
        assertEquals("$expected recorded entries", count())
        rule.openTab("dashboard")
        rule.openTab("transactions")
        assertEquals("$expected recorded entries", count())
    }

    private fun selectFilter(category: TransactionCategory?) {
        val chip = rule.onNodeWithTag(if (category == null) "filter_all" else "filter_${category.name}")
        chip.performScrollTo() // the chip row scrolls horizontally
        chip.performClick()
        rule.waitForIdle()
    }

    // ---- CSV ----

    /** Captures the share chooser instead of letting the system UI open, and returns the shared intent. */
    private fun exportAndCapture(): Intent {
        val captured = java.util.concurrent.atomic.AtomicReference<Intent?>()
        // The no-arg monitor constructor routes every startActivity through onStartActivity; answering
        // the chooser there keeps the system share sheet from opening over the app.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithTag("export_csv").performClick()
            rule.waitUntil(10_000) { captured.get() != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        @Suppress("DEPRECATION")
        return captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    }

    private fun sharedCsv(send: Intent): String {
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the CSV is shared as a stream", uri)
        assertEquals("${app.context.packageName}.fileprovider", uri!!.authority)
        return app.context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    }

    @Test fun exportCsvSharesEveryRowWithPlainNumericAmounts() {
        val send = exportAndCapture()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        assertEquals("FinSight AI Transactions Export", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val lines = sharedCsv(send).trimEnd('\n').split('\n')
        assertEquals("ID,Date,Title,Category,Type,Amount,Notes", lines.first())
        assertEquals("one row per transaction", seed.size, lines.size - 1)
        val keyboard = lines.single { it.startsWith("tx_tech_1,") }
        assertEquals("tx_tech_1,${seed.first { it.id == "tx_tech_1" }.date},Keychron Q1 Pro Mechanical Keyboard,Tech & Gadgets,Expense,2950.00,Banana switches upgrade", keyboard)
        val salary = lines.single { it.startsWith("tx_salary_1,") }
        assertTrue(salary, salary.contains(",Salary") && salary.contains(",Income,45000.00,"))
        assertFalse("amounts are plain numbers, no peso sign", lines.any { it.contains(Money.SYMBOL) })
        assertFalse("no thousands separators inside amounts", lines.drop(1).any { Regex(",\\d{1,3},\\d{3}\\.\\d{2},").containsMatchIn(it) })
        // The file really is on disk in the cache exports folder.
        assertTrue(File(app.context.cacheDir, "exports").listFiles().orEmpty().any { it.name.endsWith(".csv") })
    }

    @Test fun exportCsvFollowsTheActiveFilterAndEscapesCommasAndQuotes() {
        addTransaction("Lunch, \"team\" treat", "420", TransactionCategory.CAFES_DINING, notes = "line one")
        selectFilter(TransactionCategory.CAFES_DINING)
        val expected = seed.count { it.category == TransactionCategory.CAFES_DINING } + 1
        assertEquals("$expected recorded entries", count())

        val lines = sharedCsv(exportAndCapture()).trimEnd('\n').split('\n')
        assertEquals("only the filtered rows are exported", expected, lines.size - 1)
        assertTrue(lines.drop(1).all { it.contains(",Cafes & Dining,") })
        assertTrue(lines.any { it.contains(",\"Lunch, \"\"team\"\" treat\",Cafes & Dining,Expense,420.00,line one") })
    }
}
