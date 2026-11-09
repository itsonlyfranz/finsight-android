package com.example.finsightai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import java.time.LocalDate

class FinSightDatabaseHelper(
    context: Context,
    dbName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "finsight_ai.db"
        const val DATABASE_VERSION = 1
        const val DEFAULT_MONTHLY_INCOME = 45000.0

        const val TABLE_TRANSACTIONS = "transactions"
        const val COL_TX_ID = "id"
        const val COL_TX_TITLE = "title"
        const val COL_TX_AMOUNT = "amount"
        const val COL_TX_CATEGORY = "category"
        const val COL_TX_DATE = "date"
        const val COL_TX_IS_EXPENSE = "is_expense"
        const val COL_TX_NOTES = "notes"

        const val TABLE_BUDGETS = "budgets"
        const val COL_BUDGET_CATEGORY = "category"
        const val COL_BUDGET_AMOUNT = "amount"

        const val TABLE_SETTINGS = "settings"
        const val COL_SETTING_KEY = "key"
        const val COL_SETTING_VALUE = "value"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRANSACTIONS (
                $COL_TX_ID TEXT PRIMARY KEY,
                $COL_TX_TITLE TEXT NOT NULL,
                $COL_TX_AMOUNT REAL NOT NULL,
                $COL_TX_CATEGORY TEXT NOT NULL,
                $COL_TX_DATE TEXT NOT NULL,
                $COL_TX_IS_EXPENSE INTEGER NOT NULL,
                $COL_TX_NOTES TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_BUDGETS (
                $COL_BUDGET_CATEGORY TEXT PRIMARY KEY,
                $COL_BUDGET_AMOUNT REAL NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_SETTINGS (
                $COL_SETTING_KEY TEXT PRIMARY KEY,
                $COL_SETTING_VALUE TEXT NOT NULL
            )
            """.trimIndent()
        )

        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRANSACTIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BUDGETS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
        onCreate(db)
    }

    /**
     * Writes the neutral defaults a new database needs (per-category budget targets and settings).
     * Transactions are deliberately not seeded: a fresh install starts with none, and nothing ever
     * adds sample data back later, so deleting everything leaves the list empty.
     */
    private fun seedDefaults(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            // Default budgets
            for (category in TransactionCategory.values()) {
                if (category != TransactionCategory.SALARY) {
                    val cv = ContentValues().apply {
                        put(COL_BUDGET_CATEGORY, category.name)
                        put(COL_BUDGET_AMOUNT, category.defaultMonthlyBudget)
                    }
                    db.insertWithOnConflict(TABLE_BUDGETS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }

            // Default settings
            val defaultSettings = mapOf(
                "monthly_income" to DEFAULT_MONTHLY_INCOME.toString(),
                "app_theme" to "DECK_EMERALD"
            )
            for ((key, value) in defaultSettings) {
                val cv = ContentValues().apply {
                    put(COL_SETTING_KEY, key)
                    put(COL_SETTING_VALUE, value)
                }
                db.insertWithOnConflict(TABLE_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // CRUD operations: Transactions
    fun getAllTransactions(): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_TRANSACTIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TX_DATE DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(COL_TX_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TX_TITLE))
                val amount = it.getDouble(it.getColumnIndexOrThrow(COL_TX_AMOUNT))
                val catStr = it.getString(it.getColumnIndexOrThrow(COL_TX_CATEGORY))
                val dateStr = it.getString(it.getColumnIndexOrThrow(COL_TX_DATE))
                val isExpense = it.getInt(it.getColumnIndexOrThrow(COL_TX_IS_EXPENSE)) == 1
                val notes = it.getString(it.getColumnIndexOrThrow(COL_TX_NOTES)) ?: ""

                val category = try {
                    TransactionCategory.valueOf(catStr)
                } catch (e: Exception) {
                    TransactionCategory.CAFES_DINING
                }
                val date = try {
                    LocalDate.parse(dateStr)
                } catch (e: Exception) {
                    LocalDate.now()
                }

                list.add(
                    Transaction(
                        id = id,
                        title = title,
                        amount = amount,
                        category = category,
                        date = date,
                        isExpense = isExpense,
                        notes = notes
                    )
                )
            }
        }
        return list
    }

    fun insertTransaction(tx: Transaction) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TX_ID, tx.id)
            put(COL_TX_TITLE, tx.title)
            put(COL_TX_AMOUNT, tx.amount)
            put(COL_TX_CATEGORY, tx.category.name)
            put(COL_TX_DATE, tx.date.toString())
            put(COL_TX_IS_EXPENSE, if (tx.isExpense) 1 else 0)
            put(COL_TX_NOTES, tx.notes)
        }
        db.insertWithOnConflict(TABLE_TRANSACTIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateTransaction(tx: Transaction) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_TX_TITLE, tx.title)
            put(COL_TX_AMOUNT, tx.amount)
            put(COL_TX_CATEGORY, tx.category.name)
            put(COL_TX_DATE, tx.date.toString())
            put(COL_TX_IS_EXPENSE, if (tx.isExpense) 1 else 0)
            put(COL_TX_NOTES, tx.notes)
        }
        db.update(TABLE_TRANSACTIONS, cv, "$COL_TX_ID = ?", arrayOf(tx.id))
    }

    fun deleteTransaction(id: String) {
        val db = writableDatabase
        db.delete(TABLE_TRANSACTIONS, "$COL_TX_ID = ?", arrayOf(id))
    }

    // CRUD operations: Budgets
    fun getAllBudgets(): Map<TransactionCategory, Double> {
        val map = mutableMapOf<TransactionCategory, Double>()
        val db = readableDatabase
        val cursor = db.query(TABLE_BUDGETS, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val catStr = it.getString(it.getColumnIndexOrThrow(COL_BUDGET_CATEGORY))
                val amount = it.getDouble(it.getColumnIndexOrThrow(COL_BUDGET_AMOUNT))
                try {
                    val category = TransactionCategory.valueOf(catStr)
                    map[category] = amount
                } catch (ignored: Exception) {}
            }
        }
        return map
    }

    fun setBudget(category: TransactionCategory, amount: Double) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_BUDGET_CATEGORY, category.name)
            put(COL_BUDGET_AMOUNT, amount)
        }
        db.insertWithOnConflict(TABLE_BUDGETS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // CRUD operations: Settings
    fun getSetting(key: String, defaultValue: String): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SETTINGS,
            arrayOf(COL_SETTING_VALUE),
            "$COL_SETTING_KEY = ?",
            arrayOf(key),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0) ?: defaultValue
            }
        }
        return defaultValue
    }

    fun setSetting(key: String, value: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_SETTING_KEY, key)
            put(COL_SETTING_VALUE, value)
        }
        db.insertWithOnConflict(TABLE_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMonthlyIncome(): Double {
        val str = getSetting("monthly_income", DEFAULT_MONTHLY_INCOME.toString())
        return str.toDoubleOrNull() ?: DEFAULT_MONTHLY_INCOME
    }

    fun setMonthlyIncome(income: Double) {
        setSetting("monthly_income", income.toString())
    }
}
