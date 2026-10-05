package com.example.finsightai.ui.viewmodel

import com.example.finsightai.data.DefaultFinSightRepository
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
        repository = DefaultFinSightRepository()
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
        assertEquals(3200.0, monthlyIncome, 0.001)

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
}
