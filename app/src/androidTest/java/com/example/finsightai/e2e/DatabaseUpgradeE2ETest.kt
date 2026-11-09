package com.example.finsightai.e2e

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * An installed app (database version 1, written by the shipped 1.1.0 schema) upgrades to the version
 * with goals and the intro settings without losing or rewriting a single row.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "e2e-upgrade-${System.nanoTime()}.db"

    @Before fun setUp() { context.deleteDatabase(dbName) }
    @After fun tearDown() { context.deleteDatabase(dbName) }

    /** Creates the exact version-1 schema and fills it the way the shipped app would have. */
    private fun createVersion1Database(withTransactions: Boolean) {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        try {
            db.execSQL("CREATE TABLE transactions (id TEXT PRIMARY KEY, title TEXT NOT NULL, amount REAL NOT NULL, category TEXT NOT NULL, date TEXT NOT NULL, is_expense INTEGER NOT NULL, notes TEXT)")
            db.execSQL("CREATE TABLE budgets (category TEXT PRIMARY KEY, amount REAL NOT NULL)")
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            if (withTransactions) {
                db.insert("transactions", null, ContentValues().apply {
                    put("id", "old-1"); put("title", "Old coffee"); put("amount", 123.5); put("category", "CAFES_DINING")
                    put("date", "2026-10-03"); put("is_expense", 1); put("notes", "kept")
                })
            }
            db.insert("budgets", null, ContentValues().apply { put("category", "GROCERIES"); put("amount", 7777.0) })
            db.insert("settings", null, ContentValues().apply { put("key", "monthly_income"); put("value", "52000.0") })
            db.insert("settings", null, ContentValues().apply { put("key", "app_theme"); put("value", "CYBER_SLATE") })
            db.version = 1
        } finally {
            db.close()
        }
    }

    @Test fun upgradingKeepsEveryExistingRowAndSkipsTheIntroForAnInstallWithData() {
        createVersion1Database(withTransactions = true)

        val repo = DefaultFinSightRepository(FinSightDatabaseHelper(context, dbName))
        val tx = repo.transactions.value.single()
        assertEquals("old-1", tx.id)
        assertEquals("Old coffee", tx.title)
        assertEquals(123.5, tx.amount, 0.0)
        assertEquals(TransactionCategory.CAFES_DINING, tx.category)
        assertEquals(LocalDate.of(2026, 10, 3), tx.date)
        assertEquals("kept", tx.notes)
        assertEquals(7777.0, repo.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0)
        assertEquals(52_000.0, repo.monthlyIncome.value, 0.0)
        assertEquals(AppTheme.CYBER_SLATE, repo.currentTheme.value)

        assertTrue("the intro is not forced on an install that already has data", repo.onboardingCompleted.value)
        assertTrue(repo.goals.value.isEmpty())
        assertEquals("", repo.userName.value)

        // Goals now work on the upgraded database and are still there next time.
        repo.addGoal(SavingsGoal("g1", "Laptop", 50_000.0, 1_000.0, "💻", "note"))
        val reopened = DefaultFinSightRepository(FinSightDatabaseHelper(context, dbName))
        assertEquals("Laptop", reopened.goals.value.single().title)
        assertEquals(1, reopened.transactions.value.size)
        assertTrue(reopened.onboardingCompleted.value)
    }

    @Test fun anUpgradedInstallWithNoTransactionsYetStillGetsTheIntroButKeepsItsSettings() {
        createVersion1Database(withTransactions = false)

        val repo = DefaultFinSightRepository(FinSightDatabaseHelper(context, dbName))
        assertFalse(repo.onboardingCompleted.value)
        assertEquals(52_000.0, repo.monthlyIncome.value, 0.0)
        assertEquals(AppTheme.CYBER_SLATE, repo.currentTheme.value)
        assertEquals(7777.0, repo.currentBudget.value[TransactionCategory.GROCERIES]!!, 0.0)
        assertNull("nothing is written until the user finishes or skips", FinSightDatabaseHelper(context, dbName).getOnboardingCompleted())

        repo.completeOnboarding()
        assertTrue(DefaultFinSightRepository(FinSightDatabaseHelper(context, dbName)).onboardingCompleted.value)
    }
}
