package com.example.finsightai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.data.FinSightRepository
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class FinSightViewModel(
    private val repository: FinSightRepository = DefaultFinSightRepository()
) : ViewModel() {

    val transactions: StateFlow<List<Transaction>> = repository.transactions
    val categories: StateFlow<List<TransactionCategory>> = repository.categories
    val insights: StateFlow<List<AiInsight>> = repository.insights
    val monthlyIncome: StateFlow<Double> = repository.monthlyIncome
    val appTheme: StateFlow<AppTheme> = repository.currentTheme

    val totalSpent: StateFlow<Double> = transactions
        .map { list ->
            list.filter { it.isExpense && it.category != TransactionCategory.SALARY }
                .sumOf { it.amount }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = transactions.value
                .filter { it.isExpense && it.category != TransactionCategory.SALARY }
                .sumOf { it.amount }
        )

    val categoryBreakdown: StateFlow<Map<TransactionCategory, Double>> = transactions
        .map { list ->
            list.filter { it.isExpense && it.category != TransactionCategory.SALARY }
                .groupBy { it.category }
                .mapValues { (_, txs) -> txs.sumOf { it.amount } }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = repository.getCategoryBreakdown()
        )

    val sliderReductions = MutableStateFlow<Map<TransactionCategory, Float>>(
        mapOf(
            TransactionCategory.CAFES_DINING to 0.20f,
            TransactionCategory.TECH_GADGETS to 0.20f,
            TransactionCategory.SUBSCRIPTIONS to 0.15f
        )
    )

    private val _selectedCategoryFilter = MutableStateFlow<TransactionCategory?>(null)
    val selectedCategoryFilter: StateFlow<TransactionCategory?> = _selectedCategoryFilter.asStateFlow()

    val filteredTransactions: StateFlow<List<Transaction>> = combine(
        transactions,
        _selectedCategoryFilter
    ) { txList, filter ->
        if (filter == null) {
            txList
        } else {
            txList.filter { it.category == filter }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = transactions.value
    )

    val simulationResult: StateFlow<SimulationResult> = combine(
        categoryBreakdown,
        sliderReductions
    ) { _, reductions ->
        repository.simulateSavings(reductions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = repository.simulateSavings(sliderReductions.value)
    )

    fun addTransaction(
        title: String,
        amount: Double,
        category: TransactionCategory,
        date: LocalDate = LocalDate.now(),
        notes: String = ""
    ) {
        val newTx = Transaction(
            title = title.trim(),
            amount = amount,
            category = category,
            date = date,
            isExpense = (category != TransactionCategory.SALARY),
            notes = notes.trim()
        )
        repository.addTransaction(newTx)
    }

    fun deleteTransaction(id: String) {
        repository.deleteTransaction(id)
    }

    fun updateSliderReduction(category: TransactionCategory, percentage: Float) {
        val updated = sliderReductions.value.toMutableMap()
        updated[category] = percentage.coerceIn(0f, 1f)
        sliderReductions.value = updated
    }

    fun filterCategory(category: TransactionCategory?) {
        _selectedCategoryFilter.value = category
    }

    fun setAppTheme(theme: AppTheme) {
        repository.updateTheme(theme)
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = DefaultFinSightRepository(context.applicationContext)
                return FinSightViewModel(repository) as T
            }
        }
    }
}
