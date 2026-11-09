package com.example.finsightai.data

import android.content.Context
import com.example.finsightai.ai.AiInsightsEngine
import com.example.finsightai.ai.ForwardSimulatorEngine
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface FinSightRepository {
    val transactions: StateFlow<List<Transaction>>
    val categories: StateFlow<List<TransactionCategory>>
    val currentBudget: StateFlow<Map<TransactionCategory, Double>>
    val monthlyIncome: StateFlow<Double>
    val insights: StateFlow<List<AiInsight>>
    val currentTheme: StateFlow<AppTheme>

    fun addTransaction(transaction: Transaction)
    fun deleteTransaction(transactionId: String)
    fun updateTransaction(transaction: Transaction)
    fun getTransactionsGroupedByMonth(): Map<String, List<Transaction>>
    fun getCategoryBreakdown(): Map<TransactionCategory, Double>
    fun updateBudget(category: TransactionCategory, newBudget: Double)
    fun updateMonthlyIncome(newIncome: Double)
    fun updateTheme(theme: AppTheme)
    fun refreshInsights(): List<AiInsight>
    fun simulateSavings(targetReductions: Map<TransactionCategory, Float>): SimulationResult
}

class DefaultFinSightRepository(
    private val dbHelper: FinSightDatabaseHelper? = null,
    private val aiInsightsEngine: AiInsightsEngine = AiInsightsEngine(),
    private val forwardSimulatorEngine: ForwardSimulatorEngine = ForwardSimulatorEngine()
) : FinSightRepository {

    constructor(context: Context) : this(FinSightDatabaseHelper(context))

    private val defaultBudgets = TransactionCategory.values()
        .filter { it != TransactionCategory.SALARY }
        .associateWith { it.defaultMonthlyBudget }

    private val _monthlyIncome: MutableStateFlow<Double>
    override val monthlyIncome: StateFlow<Double>

    private val _categories = MutableStateFlow(TransactionCategory.values().toList())
    override val categories: StateFlow<List<TransactionCategory>> = _categories.asStateFlow()

    private val _currentBudget: MutableStateFlow<Map<TransactionCategory, Double>>
    override val currentBudget: StateFlow<Map<TransactionCategory, Double>>

    private val _transactions: MutableStateFlow<List<Transaction>>
    override val transactions: StateFlow<List<Transaction>>

    private val _currentTheme: MutableStateFlow<AppTheme>
    override val currentTheme: StateFlow<AppTheme>

    private val _insights = MutableStateFlow<List<AiInsight>>(emptyList())
    override val insights: StateFlow<List<AiInsight>> = _insights.asStateFlow()

    init {
        if (dbHelper != null) {
            // A fresh install starts empty: no demo transactions are ever written or re-written here.
            _transactions = MutableStateFlow(dbHelper.getAllTransactions())

            val dbBudgets = dbHelper.getAllBudgets()
            _currentBudget = MutableStateFlow(if (dbBudgets.isNotEmpty()) dbBudgets else defaultBudgets)

            _monthlyIncome = MutableStateFlow(dbHelper.getMonthlyIncome())

            val themeStr = dbHelper.getSetting("app_theme", AppTheme.DECK_EMERALD.name)
            val theme = try {
                AppTheme.valueOf(themeStr)
            } catch (e: Exception) {
                AppTheme.DECK_EMERALD
            }
            _currentTheme = MutableStateFlow(theme)
        } else {
            _transactions = MutableStateFlow(emptyList())
            _currentBudget = MutableStateFlow(defaultBudgets)
            _monthlyIncome = MutableStateFlow(45000.0)
            _currentTheme = MutableStateFlow(AppTheme.DECK_EMERALD)
        }

        monthlyIncome = _monthlyIncome.asStateFlow()
        currentBudget = _currentBudget.asStateFlow()
        transactions = _transactions.asStateFlow()
        currentTheme = _currentTheme.asStateFlow()

        refreshInsights()
    }

    override fun addTransaction(transaction: Transaction) {
        dbHelper?.insertTransaction(transaction)
        val updated = if (dbHelper != null) {
            dbHelper.getAllTransactions()
        } else {
            _transactions.value + transaction
        }
        _transactions.value = updated.sortedByDescending { it.date }
        refreshInsights()
    }

    override fun deleteTransaction(transactionId: String) {
        dbHelper?.deleteTransaction(transactionId)
        val updated = if (dbHelper != null) {
            dbHelper.getAllTransactions()
        } else {
            _transactions.value.filterNot { it.id == transactionId }
        }
        _transactions.value = updated
        refreshInsights()
    }

    override fun updateTransaction(transaction: Transaction) {
        dbHelper?.updateTransaction(transaction)
        val updated = if (dbHelper != null) {
            dbHelper.getAllTransactions()
        } else {
            _transactions.value.map {
                if (it.id == transaction.id) transaction else it
            }
        }
        _transactions.value = updated.sortedByDescending { it.date }
        refreshInsights()
    }

    override fun getTransactionsGroupedByMonth(): Map<String, List<Transaction>> {
        return _transactions.value.groupBy {
            val monthStr = it.date.monthValue.toString().padStart(2, '0')
            "${it.date.year}-$monthStr"
        }
    }

    override fun getCategoryBreakdown(): Map<TransactionCategory, Double> {
        return _transactions.value
            .filter { it.isExpense && it.category != TransactionCategory.SALARY }
            .groupBy { it.category }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
    }

    override fun updateBudget(category: TransactionCategory, newBudget: Double) {
        dbHelper?.setBudget(category, newBudget)
        val updated = _currentBudget.value.toMutableMap()
        updated[category] = newBudget
        _currentBudget.value = updated
        refreshInsights()
    }

    override fun updateMonthlyIncome(newIncome: Double) {
        dbHelper?.setMonthlyIncome(newIncome)
        _monthlyIncome.value = newIncome
        refreshInsights()
    }

    override fun updateTheme(theme: AppTheme) {
        dbHelper?.setSetting("app_theme", theme.name)
        _currentTheme.value = theme
    }

    override fun refreshInsights(): List<AiInsight> {
        val newInsights = aiInsightsEngine.generateInsights(
            transactions = _transactions.value,
            monthlyIncome = _monthlyIncome.value,
            customBudgets = _currentBudget.value
        )
        _insights.value = newInsights
        return newInsights
    }

    override fun simulateSavings(targetReductions: Map<TransactionCategory, Float>): SimulationResult {
        val baselineSpending = getCategoryBreakdown()
        return forwardSimulatorEngine.simulate(
            baselineSpending = baselineSpending,
            targetReductions = targetReductions
        )
    }
}
