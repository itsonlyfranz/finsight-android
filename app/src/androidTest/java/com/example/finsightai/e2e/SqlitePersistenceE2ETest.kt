package com.example.finsightai.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.testdata.SampleData
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
 * The real SQLite store behind the repository: write through one repository, then read everything back
 * through a brand-new one, as a restart would. A fresh database starts with no transactions at all, and
 * nothing in the app ever puts sample data back.
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
        assertEquals("a fresh install starts with no transactions", 0, seedCount)
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
        first.addTransaction(Transaction(id = "t1", title = "Coffee", amount = 100.0, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(2026, 1, 5)))
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

    @Test fun aFreshDatabaseHasNoTransactionsButKeepsItsNeutralDefaults() {
        val db = FinSightDatabaseHelper(context, dbName)
        assertEquals(emptyList<Transaction>(), db.getAllTransactions())
        assertEquals(45000.0, db.getMonthlyIncome(), 0.0)
        assertEquals(TransactionCategory.values().size - 1, db.getAllBudgets().size)
        val repo = DefaultFinSightRepository(db)
        assertEquals(AppTheme.DECK_EMERALD, repo.currentTheme.value)
        assertEquals(emptyList<Transaction>(), repo.transactions.value)
        assertEquals(emptyList<Any>(), repo.insights.value)
    }

    @Test fun deletingEverythingLeavesTheDatabaseEmptyAcrossReopens() {
        val first = repository()
        val rows = listOf(
            Transaction(id = "d1", title = "One", amount = 10.0, category = TransactionCategory.GROCERIES, date = LocalDate.of(2026, 2, 1)),
            Transaction(id = "d2", title = "Two", amount = 20.0, category = TransactionCategory.TRANSPORT, date = LocalDate.of(2026, 2, 2)),
        )
        rows.forEach(first::addTransaction)
        assertEquals(2, repository().transactions.value.size)

        rows.forEach { first.deleteTransaction(it.id) }
        repeat(3) { assertEquals("reopen #$it", 0, repository().transactions.value.size) }
        assertEquals(0, FinSightDatabaseHelper(context, dbName).getAllTransactions().size)
    }

    @Test fun anExistingDatabaseIsOpenedExactlyAsItWasLeft() {
        // An install that already has data: transactions, a changed theme/income and a changed budget.
        val seeded = FinSightDatabaseHelper(context, dbName)
        val rows = SampleData.markSantosTransactions()
        rows.forEach(seeded::insertTransaction)
        seeded.setMonthlyIncome(61_250.0)
        seeded.setSetting("app_theme", AppTheme.CYBER_SLATE.name)
        seeded.setBudget(TransactionCategory.GROCERIES, 7_777.0)
        seeded.close()

        val repo = repository()
        assertEquals(rows.sortedBy { it.id }, repo.transactions.value.sortedBy { it.id })
        assertEquals(61_250.0, repo.monthlyIncome.value, 0.0)
        assertEquals(AppTheme.CYBER_SLATE, repo.currentTheme.value)
        assertEquals(7_777.0, repo.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0)
        assertEquals(rows.size, FinSightDatabaseHelper(context, dbName).getAllTransactions().size)
    }
}
