package com.example.finsightai.ui.viewmodel

import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FinSightViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: DefaultFinSightRepository
    private lateinit var viewModel: FinSightViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = SampleData.repository()
        viewModel = FinSightViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasSeedDataAndProjections() = runTest(testDispatcher) {
        advanceUntilIdle()

        val transactions = viewModel.transactions.value
        assertTrue("Transactions should not be empty", transactions.isNotEmpty())

        val monthlyIncome = viewModel.monthlyIncome.value
        assertEquals(45000.0, monthlyIncome, 0.001)

        val totalSpent = viewModel.totalSpent.value
        assertTrue("Total spent should be greater than 0", totalSpent > 0.0)

        val insights = viewModel.insights.value
        assertTrue("Insights should be generated", insights.isNotEmpty())

        val simulation = viewModel.simulationResult.value
        assertNotNull("Simulation result should exist", simulation)
        assertTrue("Monthly savings should be positive", simulation.monthlySavings > 0.0)
        assertTrue("Compound 12M savings should exceed monthly savings", simulation.projectedSavings12M > simulation.monthlySavings)
    }

    @Test
    fun addTransaction_updatesTotalSpentAndInsights() = runTest(testDispatcher) {
        advanceUntilIdle()
        val initialCount = viewModel.transactions.value.size
        val initialSpent = viewModel.totalSpent.value

        viewModel.addTransaction(
            title = "Test Mechanical Keyboard",
            amount = 150.0,
            category = TransactionCategory.TECH_GADGETS,
            date = LocalDate.now(),
            notes = "Custom switches"
        )
        advanceUntilIdle()

        val newTransactions = viewModel.transactions.value
        assertEquals(initialCount + 1, newTransactions.size)

        val newSpent = viewModel.totalSpent.value
        assertEquals(initialSpent + 150.0, newSpent, 0.01)

        val addedTx = newTransactions.find { it.title == "Test Mechanical Keyboard" }
        assertNotNull(addedTx)
        assertEquals(150.0, addedTx!!.amount, 0.001)
        assertEquals(TransactionCategory.TECH_GADGETS, addedTx.category)
    }

    @Test
    fun deleteTransaction_removesTransaction() = runTest(testDispatcher) {
        advanceUntilIdle()
        val initialTransactions = viewModel.transactions.value
        val toDelete = initialTransactions.first()

        viewModel.deleteTransaction(toDelete.id)
        advanceUntilIdle()

        val remaining = viewModel.transactions.value
        assertFalse("Deleted transaction should not be in list", remaining.any { it.id == toDelete.id })
        assertEquals(initialTransactions.size - 1, remaining.size)
    }

    @Test
    fun updateTransaction_savesEditedAmountCategoryDateAndNote() = runTest(testDispatcher) {
        advanceUntilIdle()
        val original = viewModel.transactions.value.first { it.id == "tx_cafe_1" }
        val spentBefore = viewModel.totalSpent.value

        val saved = viewModel.updateTransaction(
            original = original,
            title = "  Corrected title ",
            amount = 999.5,
            category = TransactionCategory.GROCERIES,
            date = LocalDate.of(2026, 3, 14),
            notes = "  edited "
        )
        advanceUntilIdle()

        assertTrue(saved)
        val edited = viewModel.transactions.value.single { it.id == "tx_cafe_1" }
        assertEquals("Corrected title", edited.title)
        assertEquals(999.5, edited.amount, 0.0)
        assertEquals(TransactionCategory.GROCERIES, edited.category)
        assertEquals(LocalDate.of(2026, 3, 14), edited.date)
        assertEquals("edited", edited.notes)
        assertTrue(edited.isExpense)
        assertEquals(spentBefore - original.amount + 999.5, viewModel.totalSpent.value, 0.01)
    }

    @Test
    fun updateTransaction_followsTheCategoryForIncomeVersusExpense() = runTest(testDispatcher) {
        advanceUntilIdle()
        val spentBefore = viewModel.totalSpent.value
        val salary = viewModel.transactions.value.first { it.category == TransactionCategory.SALARY }
        assertFalse(salary.isExpense)

        viewModel.updateTransaction(salary, salary.title, salary.amount, TransactionCategory.LIFESTYLE, salary.date, salary.notes)
        advanceUntilIdle()
        val asExpense = viewModel.transactions.value.single { it.id == salary.id }
        assertTrue(asExpense.isExpense)
        assertEquals(spentBefore + salary.amount, viewModel.totalSpent.value, 0.01)

        viewModel.updateTransaction(asExpense, asExpense.title, asExpense.amount, TransactionCategory.SALARY, asExpense.date, asExpense.notes)
        advanceUntilIdle()
        assertFalse(viewModel.transactions.value.single { it.id == salary.id }.isExpense)
        assertEquals(spentBefore, viewModel.totalSpent.value, 0.01)
    }

    @Test
    fun updateTransaction_rejectsABlankTitleAndABadAmountWithoutWriting() = runTest(testDispatcher) {
        advanceUntilIdle()
        val original = viewModel.transactions.value.first { it.id == "tx_cafe_1" }
        val before = viewModel.transactions.value

        for ((title, amount) in listOf("   " to 10.0, "ok" to 0.0, "ok" to -5.0, "ok" to Double.NaN, "ok" to Double.POSITIVE_INFINITY)) {
            assertFalse("$title / $amount must be refused", viewModel.updateTransaction(original, title, amount, original.category, original.date, original.notes))
        }
        advanceUntilIdle()
        assertEquals(before, viewModel.transactions.value)
    }

    @Test
    fun quickCutProjection_isWorkedOutFromTheUsersOwnSpendingAndZeroWhenThereIsNone() = runTest(testDispatcher) {
        advanceUntilIdle()
        // Sample data: PHP 8,800 cafes + PHP 5,900 tech in one month, 20% = 2,940 saved monthly; annuity-due
        // future value at 4.5%/12 over 12 months: 2,940 * ((1.00375^12 - 1) / 0.00375) * 1.00375 = 36,151.89.
        assertEquals(36151.89, viewModel.quickCutProjection12M.value, 0.01)

        val empty = FinSightViewModel(DefaultFinSightRepository())
        advanceUntilIdle()
        assertEquals(0.0, empty.quickCutProjection12M.value, 0.0)
        assertTrue(empty.transactions.value.isEmpty())
        assertTrue(empty.insights.value.isEmpty())
    }

    @Test
    fun quickCutProjection_usesAverageMonthlySpendingAcrossMultipleMonths() = runTest(testDispatcher) {
        val repo = DefaultFinSightRepository()
        val thisMonth = LocalDate.now().withDayOfMonth(1)
        (0L..2L).forEach { back ->
            repo.addTransaction(Transaction(title = "Cafe $back", amount = 3000.0, category = TransactionCategory.CAFES_DINING, date = thisMonth.minusMonths(back)))
        }
        val vm = FinSightViewModel(repo)
        advanceUntilIdle()
        // 9,000 over 3 months = 3,000/month, 20% = 600/month; 600 * ((1.00375^12 - 1) / 0.00375) * 1.00375 = 7,377.94
        // (the all-time total would give 1,800/month and about 22,134).
        assertEquals(7377.94, vm.quickCutProjection12M.value, 0.01)
    }

    @Test
    fun simulatorHasNoPresetSavingsGoalsWithOrWithoutData() = runTest(testDispatcher) {
        advanceUntilIdle()
        // The app ships no goals: neither a fresh install nor one with spending shows invented progress.
        assertTrue(viewModel.simulationResult.value.goalProjections.isEmpty())
        val empty = FinSightViewModel(DefaultFinSightRepository())
        advanceUntilIdle()
        assertTrue(empty.simulationResult.value.goalProjections.isEmpty())
    }

    @Test
    fun updateSliderReduction_recomputesSimulation() = runTest(testDispatcher) {
        advanceUntilIdle()
        val initialSim = viewModel.simulationResult.value
        val initialMonthly = initialSim.monthlySavings

        // Increase cafe reduction from 20% to 50%
        viewModel.updateSliderReduction(TransactionCategory.CAFES_DINING, 0.50f)
        advanceUntilIdle()

        val updatedReductions = viewModel.sliderReductions.value
        assertEquals(0.50f, updatedReductions[TransactionCategory.CAFES_DINING] ?: 0f, 0.001f)

        val updatedSim = viewModel.simulationResult.value
        assertTrue("Higher reduction should yield higher monthly savings", updatedSim.monthlySavings >= initialMonthly)
    }

    @Test
    fun filterCategory_filtersTransactionsAccurately() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.filterCategory(TransactionCategory.CAFES_DINING)
        advanceUntilIdle()

        assertEquals(TransactionCategory.CAFES_DINING, viewModel.selectedCategoryFilter.value)
        val filtered = viewModel.filteredTransactions.value

        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { it.category == TransactionCategory.CAFES_DINING })

        // Clear filter
        viewModel.filterCategory(null)
        advanceUntilIdle()
        val allTx = viewModel.filteredTransactions.value
        assertEquals(viewModel.transactions.value.size, allTx.size)
    }

    @Test
    fun setAppTheme_updatesCurrentThemeFlow() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(com.example.finsightai.theme.AppTheme.DECK_EMERALD, viewModel.appTheme.value)

        viewModel.setAppTheme(com.example.finsightai.theme.AppTheme.CYBER_SLATE)
        advanceUntilIdle()
        assertEquals(com.example.finsightai.theme.AppTheme.CYBER_SLATE, viewModel.appTheme.value)

        viewModel.setAppTheme(com.example.finsightai.theme.AppTheme.MIDNIGHT_BLUE)
        advanceUntilIdle()
        assertEquals(com.example.finsightai.theme.AppTheme.MIDNIGHT_BLUE, viewModel.appTheme.value)
    }
}
