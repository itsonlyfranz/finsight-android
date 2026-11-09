package com.example.finsightai.data

import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class FinSightRepositoryTest {

    private lateinit var repository: DefaultFinSightRepository

    @Before
    fun setUp() {
        repository = DefaultFinSightRepository()
    }

    @Test
    fun `preseeded Mark Santos data has expected income and expenses`() {
        val transactions = repository.transactions.value
        val salaryTx = transactions.find { it.category == TransactionCategory.SALARY }
        assertNotNull("Salary transaction must exist", salaryTx)
        assertEquals(45000.0, salaryTx?.amount ?: 0.0, 0.01)

        val rentTx = transactions.find { it.category == TransactionCategory.HOUSING_RENT }
        assertNotNull("Rent transaction must exist", rentTx)
        assertEquals(13500.0, rentTx?.amount ?: 0.0, 0.01)

        val breakdown = repository.getCategoryBreakdown()
        // Mark has ₱4,000 in cafes + ₱4,800 dining = ₱8,800 in CAFES_DINING
        assertEquals(8800.0, breakdown[TransactionCategory.CAFES_DINING] ?: 0.0, 0.01)
        assertEquals(5900.0, breakdown[TransactionCategory.TECH_GADGETS] ?: 0.0, 0.01)
        assertEquals(3496.0, breakdown[TransactionCategory.SUBSCRIPTIONS] ?: 0.0, 0.01)
        assertEquals(4200.0, breakdown[TransactionCategory.GROCERIES] ?: 0.0, 0.01)
    }

    @Test
    fun `addTransaction adds item and updates insights`() {
        val initialCount = repository.transactions.value.size
        val newTx = Transaction(
            id = "new_tx_1",
            title = "Specialty Coffee Roasters",
            amount = 180.0,
            category = TransactionCategory.CAFES_DINING,
            date = LocalDate.now(),
            isExpense = true
        )

        repository.addTransaction(newTx)
        assertEquals(initialCount + 1, repository.transactions.value.size)
        assertTrue(repository.transactions.value.any { it.id == "new_tx_1" })
    }

    @Test
    fun `deleteTransaction removes item and updates list`() {
        val initialCount = repository.transactions.value.size
        repository.deleteTransaction("tx_cafe_1")
        assertEquals(initialCount - 1, repository.transactions.value.size)
        assertTrue(repository.transactions.value.none { it.id == "tx_cafe_1" })
    }

    @Test
    fun `updateTransaction modifies existing entry`() {
        val updated = Transaction(
            id = "tx_cafe_2",
            title = "Philz Mint Mojito Large",
            amount = 220.0,
            category = TransactionCategory.CAFES_DINING,
            date = LocalDate.now(),
            isExpense = true
        )
        repository.updateTransaction(updated)
        val found = repository.transactions.value.find { it.id == "tx_cafe_2" }
        assertEquals("Philz Mint Mojito Large", found?.title)
        assertEquals(220.0, found?.amount ?: 0.0, 0.01)
    }

    @Test
    fun `getTransactionsGroupedByMonth groups transactions correctly`() {
        val grouped = repository.getTransactionsGroupedByMonth()
        assertTrue("Expected non-empty monthly groups", grouped.isNotEmpty())
        val currentMonthKey = "${LocalDate.now().year}-${LocalDate.now().monthValue.toString().padStart(2, '0')}"
        assertTrue("Expected key for current month $currentMonthKey", grouped.containsKey(currentMonthKey))
    }

    @Test
    fun `simulateSavings uses repository breakdown and computes projection`() {
        val simulation = repository.simulateSavings(
            mapOf(TransactionCategory.CAFES_DINING to 0.20f)
        )
        // 20% of ₱8,800 = ₱1,760
        assertEquals(1760.0, simulation.monthlySavings, 0.01)
        assertTrue(simulation.projectedSavings12M > 1760 * 12)
        assertTrue(simulation.aiExplanation.isNotEmpty())
    }

    @Test
    fun `updateTheme updates repository currentTheme state`() {
        assertEquals(com.example.finsightai.theme.AppTheme.DECK_EMERALD, repository.currentTheme.value)
        repository.updateTheme(com.example.finsightai.theme.AppTheme.CYBER_SLATE)
        assertEquals(com.example.finsightai.theme.AppTheme.CYBER_SLATE, repository.currentTheme.value)
    }

    @Test
    fun `updateMonthlyIncome updates income state`() {
        assertEquals(45000.0, repository.monthlyIncome.value, 0.01)
        repository.updateMonthlyIncome(50000.0)
        assertEquals(50000.0, repository.monthlyIncome.value, 0.01)
    }

    @Test
    fun `updateBudget updates category budget`() {
        repository.updateBudget(TransactionCategory.CAFES_DINING, 3000.0)
        assertEquals(3000.0, repository.currentBudget.value[TransactionCategory.CAFES_DINING] ?: 0.0, 0.01)
    }
}
