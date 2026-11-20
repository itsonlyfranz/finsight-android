package com.example.finsightai.data

import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The deletion controls on the repository: what each one clears, what it keeps, and that nothing is re-seeded. */
class DataDeletionTest {

    private lateinit var repository: DefaultFinSightRepository
    private val goal = SavingsGoal("g1", "Emergency fund", 100_000.0, 5_000.0, "🎯", "")

    @Before
    fun setUp() {
        repository = SampleData.repository().apply {
            addGoal(goal)
            updateUserName("Juan")
            updateMonthlyIncome(61_000.0)
            updateBudget(TransactionCategory.GROCERIES, 9_999.0)
            updateTheme(AppTheme.CYBER_SLATE)
            completeOnboarding()
        }
    }

    @Test
    fun `deleting all transactions keeps goals, name, income, budgets and theme`() {
        repository.deleteAllTransactions()

        assertTrue(repository.transactions.value.isEmpty())
        assertTrue(repository.insights.value.isEmpty())
        assertTrue(repository.getCategoryBreakdown().isEmpty())
        assertEquals(listOf(goal), repository.goals.value)
        assertEquals("Juan", repository.userName.value)
        assertEquals(61_000.0, repository.monthlyIncome.value, 0.0)
        assertEquals(9_999.0, repository.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0)
        assertEquals(AppTheme.CYBER_SLATE, repository.currentTheme.value)
        assertTrue(repository.onboardingCompleted.value)
    }

    @Test
    fun `deleting all goals keeps everything else`() {
        val transactionCount = repository.transactions.value.size

        repository.deleteAllGoals()

        assertTrue(repository.goals.value.isEmpty())
        assertEquals(transactionCount, repository.transactions.value.size)
        assertEquals("Juan", repository.userName.value)
        assertEquals(61_000.0, repository.monthlyIncome.value, 0.0)
        assertEquals(AppTheme.CYBER_SLATE, repository.currentTheme.value)
        assertTrue(repository.onboardingCompleted.value)
    }

    @Test
    fun `delete all data clears the user's data, returns to the intro and keeps the theme`() {
        repository.deleteAllData()

        assertTrue(repository.transactions.value.isEmpty())
        assertTrue(repository.goals.value.isEmpty())
        assertTrue(repository.insights.value.isEmpty())
        assertEquals("", repository.userName.value)
        assertEquals(45_000.0, repository.monthlyIncome.value, 0.0)
        assertEquals(
            TransactionCategory.GROCERIES.defaultMonthlyBudget,
            repository.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0
        )
        assertFalse(repository.onboardingCompleted.value)
        assertEquals(AppTheme.CYBER_SLATE, repository.currentTheme.value)
    }

    @Test
    fun `reset of the theme puts it back to the default`() {
        repository.deleteAllData(resetTheme = true)

        assertEquals(AppTheme.DECK_EMERALD, repository.currentTheme.value)
        assertFalse(repository.onboardingCompleted.value)
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `nothing comes back after deleting everything and finishing the intro again`() {
        repository.deleteAllData()
        repository.completeOnboarding()

        assertTrue(repository.transactions.value.isEmpty())
        assertTrue(repository.goals.value.isEmpty())
        assertTrue(repository.onboardingCompleted.value)
    }
}
