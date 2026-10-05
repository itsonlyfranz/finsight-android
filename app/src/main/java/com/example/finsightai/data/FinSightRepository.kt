package com.example.finsightai.data

import com.example.finsightai.ai.AiInsightsEngine
import com.example.finsightai.ai.ForwardSimulatorEngine
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

interface FinSightRepository {
    val transactions: StateFlow<List<Transaction>>
    val categories: StateFlow<List<TransactionCategory>>
    val currentBudget: StateFlow<Map<TransactionCategory, Double>>
    val monthlyIncome: StateFlow<Double>
    val insights: StateFlow<List<AiInsight>>

    fun addTransaction(transaction: Transaction)
    fun deleteTransaction(transactionId: String)
    fun updateTransaction(transaction: Transaction)
    fun getTransactionsGroupedByMonth(): Map<String, List<Transaction>>
    fun getCategoryBreakdown(): Map<TransactionCategory, Double>
    fun updateBudget(category: TransactionCategory, newBudget: Double)
    fun updateMonthlyIncome(newIncome: Double)
    fun refreshInsights(): List<AiInsight>
    fun simulateSavings(targetReductions: Map<TransactionCategory, Float>): SimulationResult
}

class DefaultFinSightRepository(
    private val aiInsightsEngine: AiInsightsEngine = AiInsightsEngine(),
    private val forwardSimulatorEngine: ForwardSimulatorEngine = ForwardSimulatorEngine()
) : FinSightRepository {

    private val _monthlyIncome = MutableStateFlow(3200.0)
    override val monthlyIncome: StateFlow<Double> = _monthlyIncome.asStateFlow()

    private val _categories = MutableStateFlow(TransactionCategory.values().toList())
    override val categories: StateFlow<List<TransactionCategory>> = _categories.asStateFlow()

    private val defaultBudgets = TransactionCategory.values()
        .filter { it != TransactionCategory.SALARY }
        .associateWith { it.defaultMonthlyBudget }

    private val _currentBudget = MutableStateFlow<Map<TransactionCategory, Double>>(defaultBudgets)
    override val currentBudget: StateFlow<Map<TransactionCategory, Double>> = _currentBudget.asStateFlow()

    private val _transactions = MutableStateFlow<List<Transaction>>(seedMarkSantosData())
    override val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _insights = MutableStateFlow<List<AiInsight>>(emptyList())
    override val insights: StateFlow<List<AiInsight>> = _insights.asStateFlow()

    init {
        refreshInsights()
    }

    override fun addTransaction(transaction: Transaction) {
        val updated = _transactions.value + transaction
        _transactions.value = updated.sortedByDescending { it.date }
        refreshInsights()
    }

    override fun deleteTransaction(transactionId: String) {
        _transactions.value = _transactions.value.filterNot { it.id == transactionId }
        refreshInsights()
    }

    override fun updateTransaction(transaction: Transaction) {
        _transactions.value = _transactions.value.map {
            if (it.id == transaction.id) transaction else it
        }.sortedByDescending { it.date }
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
        val updated = _currentBudget.value.toMutableMap()
        updated[category] = newBudget
        _currentBudget.value = updated
        refreshInsights()
    }

    override fun updateMonthlyIncome(newIncome: Double) {
        _monthlyIncome.value = newIncome
        refreshInsights()
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

    companion object {
        fun seedMarkSantosData(): List<Transaction> {
            val now = LocalDate.now()
            val year = now.year
            val month = now.monthValue

            return listOf(
                // 1. Income ($3,200)
                Transaction(
                    id = "tx_salary_1",
                    title = "Apex Systems SWE Paycheck",
                    amount = 3200.0,
                    category = TransactionCategory.SALARY,
                    date = LocalDate.of(year, month, 1),
                    isExpense = false,
                    notes = "Monthly net direct deposit - Junior SWE"
                ),

                // 2. Rent ($950)
                Transaction(
                    id = "tx_rent_1",
                    title = "Apex Apartments Monthly Rent",
                    amount = 950.0,
                    category = TransactionCategory.HOUSING_RENT,
                    date = LocalDate.of(year, month, 1),
                    isExpense = true,
                    notes = "2B2B split with roommate"
                ),

                // 3. Cafe Visits ($280 across 14 transactions)
                Transaction(id = "tx_cafe_1", title = "Blue Bottle Pour Over & Pastry", amount = 18.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 2)),
                Transaction(id = "tx_cafe_2", title = "Philz Mint Mojito Iced Coffee", amount = 7.25, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 3)),
                Transaction(id = "tx_cafe_3", title = "Sightglass Espresso & Croissant", amount = 14.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 5)),
                Transaction(id = "tx_cafe_4", title = "Local Artisan Roasters Oat Latte", amount = 6.75, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 7)),
                Transaction(id = "tx_cafe_5", title = "Matcha Cafe Maiko Soft Serve & Drink", amount = 15.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 8)),
                Transaction(id = "tx_cafe_6", title = "Equator Coffees Cold Brew", amount = 6.25, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 10)),
                Transaction(id = "tx_cafe_7", title = "Ritual Coffee Roasters Drip", amount = 5.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 12)),
                Transaction(id = "tx_cafe_8", title = "Verve Coffee Roasters Flat White", amount = 7.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 14)),
                Transaction(id = "tx_cafe_9", title = "Weekend Coffee Tasting & Beans", amount = 38.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 16)),
                Transaction(id = "tx_cafe_10", title = "Andytown Snowy Plover & Muffin", amount = 12.75, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 18)),
                Transaction(id = "tx_cafe_11", title = "Cow Hollow Cafe Brunch Coffee", amount = 16.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 20)),
                Transaction(id = "tx_cafe_12", title = "Craft Coffee Lab Pour Over", amount = 8.50, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 22)),
                Transaction(id = "tx_cafe_13", title = "Four Barrel Espresso Bar", amount = 9.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 24)),
                Transaction(id = "tx_cafe_14", title = "Sunday Roastery Batch Brew & Whole Beans", amount = 114.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 26)),

                // 4. Dining Out ($340 across 6 outings)
                Transaction(id = "tx_dining_1", title = "Ramen Nagi Rich Tonkotsu Dinner", amount = 45.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 4)),
                Transaction(id = "tx_dining_2", title = "Sushi Omakase Lunch Special", amount = 85.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 11)),
                Transaction(id = "tx_dining_3", title = "Engineering Team Hotpot Night", amount = 60.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 15)),
                Transaction(id = "tx_dining_4", title = "Friday Craft Burger & Fries", amount = 35.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 19)),
                Transaction(id = "tx_dining_5", title = "Thai Basil Bistro Weekend Dinner", amount = 40.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 23)),
                Transaction(id = "tx_dining_6", title = "Artisan Sourdough Pizza with Colleagues", amount = 75.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 27)),

                // 5. Tech Gadgets ($420 across 3 items)
                Transaction(id = "tx_tech_1", title = "Keychron Q1 Pro Mechanical Keyboard", amount = 210.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 6), notes = "Banana switches upgrade"),
                Transaction(id = "tx_tech_2", title = "CalDigit TS4 Thunderbolt Dock", amount = 160.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 13), notes = "Dual monitor productivity"),
                Transaction(id = "tx_tech_3", title = "Anker 737 GaN 24K Power Bank", amount = 50.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 21), notes = "Remote cafe charging backup"),

                // 6. Subscriptions ($120 across 7 recurring services)
                Transaction(id = "tx_sub_1", title = "GitHub Copilot Pro", amount = 20.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 2)),
                Transaction(id = "tx_sub_2", title = "ChatGPT Plus", amount = 20.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 5)),
                Transaction(id = "tx_sub_3", title = "Spotify Family Plan", amount = 17.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 9)),
                Transaction(id = "tx_sub_4", title = "Netflix Premium 4K", amount = 23.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 12)),
                Transaction(id = "tx_sub_5", title = "iCloud+ 2TB Storage", amount = 10.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 15)),
                Transaction(id = "tx_sub_6", title = "YouTube Premium", amount = 14.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 20)),
                Transaction(id = "tx_sub_7", title = "NordVPN Monthly Equivalent", amount = 16.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 25)),

                // 7. Groceries ($300 across 4 trips)
                Transaction(id = "tx_groc_1", title = "Trader Joe's Weekly Haul", amount = 115.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 3)),
                Transaction(id = "tx_groc_2", title = "Whole Foods Organic Market", amount = 95.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 10)),
                Transaction(id = "tx_groc_3", title = "Safeway Essentials & Snacks", amount = 60.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 17)),
                Transaction(id = "tx_groc_4", title = "Farmer's Market Fresh Produce", amount = 30.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 24)),

                // 8. Transport ($150)
                Transaction(id = "tx_trans_1", title = "Clipper Card Transit Auto-Reload", amount = 95.00, category = TransactionCategory.TRANSPORT, date = LocalDate.of(year, month, 4)),
                Transaction(id = "tx_trans_2", title = "Uber Weekend Transit", amount = 55.00, category = TransactionCategory.TRANSPORT, date = LocalDate.of(year, month, 18))
            ).sortedByDescending { it.date }
        }
    }
}
