package com.example.finsightai.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The real SQLite store behind the repository. The app has no screen for editing a transaction (see
 * the PR notes), but the repository supports it, so edit is exercised here: write through one
 * repository, then read everything back through a brand-new one, as a restart would.
 */
@RunWith(AndroidJUnit4::class)
class SqlitePersistenceE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "e2e-repo-${System.nanoTime()}.db"

    private fun repository() = DefaultFinSightRepository(FinSightDatabaseHelper(context, dbName))

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    @Test fun addEditAndDeleteAreVisibleToANewRepositoryOverTheSameDatabase() {
        val first = repository()
        val seedCount = first.transactions.value.size
        val tx = Transaction(id = "e2e-1", title = "Original", amount = 100.0, category = TransactionCategory.GROCERIES, date = LocalDate.of(2026, 1, 5), notes = "n1")
        first.addTransaction(tx)

        val edited = tx.copy(title = "Edited, with \"quotes\"", amount = 250.75, category = TransactionCategory.TRANSPORT, notes = "n2", date = LocalDate.of(2026, 1, 6))
        first.updateTransaction(edited)

        val second = repository()
        assertEquals(seedCount + 1, second.transactions.value.size)
        assertEquals(edited, second.transactions.value.single { it.id == "e2e-1" })

        second.deleteTransaction("e2e-1")
        val third = repository()
        assertEquals(seedCount, third.transactions.value.size)
        assertNull(third.transactions.value.firstOrNull { it.id == "e2e-1" })
    }

    @Test fun themeIncomeAndBudgetsComeBackAfterReopening() {
        val first = repository()
        first.updateTheme(AppTheme.MIDNIGHT_BLUE)
        first.updateMonthlyIncome(52_500.0)
        first.updateBudget(TransactionCategory.GROCERIES, 6_000.0)

        val second = repository()
        assertEquals(AppTheme.MIDNIGHT_BLUE, second.currentTheme.value)
        assertEquals(52_500.0, second.monthlyIncome.value, 0.0)
        assertEquals(6_000.0, second.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0)
        assertNotNull(second.insights.value.firstOrNull())
    }

    @Test fun anUnknownStoredThemeFallsBackToTheDefault() {
        FinSightDatabaseHelper(context, dbName).setSetting("app_theme", "NOT_A_THEME")
        assertEquals(AppTheme.DECK_EMERALD, repository().currentTheme.value)
    }
}
