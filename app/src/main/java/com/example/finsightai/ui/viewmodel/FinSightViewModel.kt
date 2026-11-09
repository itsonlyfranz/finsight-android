package com.example.finsightai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.finsightai.ai.AiEnhancer
import com.example.finsightai.ai.ForwardSimulatorEngine
import com.example.finsightai.ai.GeneratedText
import com.example.finsightai.ai.InsightRewrite
import com.example.finsightai.ai.NoGenerativeSource
import com.example.finsightai.ai.backend.AiBackendRouter
import com.example.finsightai.ai.backend.EffectiveBackend
import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.data.FinSightRepository
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.util.UUID
import java.time.YearMonth

/** Progress of the optional generative layer on top of the deterministic engines, for the UI. */
data class AiFeatureStatus(
    /** True while a model is (re)writing insight / simulator text. */
    val working: Boolean = false,
    /** Label of the backend writing text now, or null when the deterministic engines are in use. */
    val backendLabel: String? = null,
    /** Why the deterministic engines are being used (no model installed, not signed in). */
    val deterministicReason: String? = null,
    /** Last backend failure, if generation fell back to deterministic text. */
    val lastError: String? = null,
)

class FinSightViewModel(
    private val repository: FinSightRepository = DefaultFinSightRepository(),
    /** Routes to the user's chosen backend; null = deterministic engines only. */
    private val aiRouter: AiBackendRouter? = null,
    /** Emits whenever the backend selection or its readiness changes (model installed/selected, sign-in). */
    private val aiSignal: Flow<Any?> = emptyFlow()
) : ViewModel() {

    private val enhancer = AiEnhancer(aiRouter ?: NoGenerativeSource)
    private val quickCutEngine = ForwardSimulatorEngine()

    private data class CachedRewrite(val signature: String, val rewrite: InsightRewrite)
    private data class CachedNarration(
        val signature: String,
        val explanation: GeneratedText?,
        val goalContext: Map<String, String>,
    )

    private val insightRewrites = MutableStateFlow<Map<String, CachedRewrite>>(emptyMap())
    private val simulationNarration = MutableStateFlow<CachedNarration?>(null)
    private val _aiStatus = MutableStateFlow(AiFeatureStatus())
    val aiStatus: StateFlow<AiFeatureStatus> = _aiStatus.asStateFlow()

    val transactions: StateFlow<List<Transaction>> = repository.transactions
    val categories: StateFlow<List<TransactionCategory>> = repository.categories

    /** Deterministic insights, with the model's wording layered on top when a backend produced it. */
    val insights: StateFlow<List<AiInsight>> = combine(repository.insights, insightRewrites) { base, rewrites ->
        base.map { insight ->
            val cached = rewrites[insight.id]
            if (cached != null && cached.signature == signatureOf(insight)) {
                insight.copy(
                    description = cached.rewrite.description,
                    actionSuggestion = cached.rewrite.actionSuggestion ?: insight.actionSuggestion,
                    generatedBy = cached.rewrite.by
                )
            } else {
                insight
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = repository.insights.value
    )
    val monthlyIncome: StateFlow<Double> = repository.monthlyIncome
    val goals: StateFlow<List<SavingsGoal>> = repository.goals
    val userName: StateFlow<String> = repository.userName
    val onboardingCompleted: StateFlow<Boolean> = repository.onboardingCompleted
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

    /**
     * What a 20% cut in cafe/dining and tech spending would compound to in 12 months, worked out from the
     * user's own average monthly spending (the Dashboard simulator card). 0.0 when there is nothing to cut.
     */
    val quickCutProjection12M: StateFlow<Double> = transactions
        .map { quickCutEngine.quickCutProjection(averageMonthlySpending(it)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = quickCutEngine.quickCutProjection(averageMonthlySpending(transactions.value))
        )

    private fun averageMonthlySpending(list: List<Transaction>): Map<TransactionCategory, Double> {
        val months = list.map { YearMonth.from(it.date) }.distinct().size
        if (months == 0) return emptyMap()
        return list.filter { it.isExpense && it.category != TransactionCategory.SALARY }
            .groupBy { it.category }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } / months }
    }

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

    private val deterministicSimulation: StateFlow<SimulationResult> = combine(
        categoryBreakdown,
        sliderReductions,
        repository.goals
    ) { _, reductions, _ ->
        repository.simulateSavings(reductions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = repository.simulateSavings(sliderReductions.value)
    )

    /** The simulator's numbers always come from the deterministic engine; only the wording can be model-written. */
    val simulationResult: StateFlow<SimulationResult> = combine(
        deterministicSimulation,
        simulationNarration
    ) { base, narration ->
        if (narration == null || narration.signature != signatureOf(base)) {
            base
        } else {
            base.copy(
                aiExplanation = narration.explanation?.text ?: base.aiExplanation,
                generatedBy = narration.explanation?.by,
                goalProjections = base.goalProjections.map { projection ->
                    projection.copy(aiContext = narration.goalContext[projection.goal.id])
                }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = deterministicSimulation.value
    )

    init {
        if (aiRouter != null) startAiEnhancement(aiRouter)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun startAiEnhancement(router: AiBackendRouter) {
        viewModelScope.launch {
            // Restart everything whenever the backend choice or its readiness changes.
            aiSignal.onStart { emit(Unit) }.distinctUntilChanged().collectLatest {
                insightRewrites.value = emptyMap()
                simulationNarration.value = null
                refreshStatus(router, working = false)
                val effective = router.effective()
                if (effective !is EffectiveBackend.Generative) return@collectLatest

                launch {
                    repository.insights.collectLatest { base -> rewriteInsights(router, base) }
                }
                launch {
                    deterministicSimulation.debounce(SIMULATION_DEBOUNCE_MS).collectLatest { base ->
                        narrateSimulation(router, base)
                    }
                }
            }
        }
    }

    private suspend fun rewriteInsights(router: AiBackendRouter, base: List<AiInsight>) {
        refreshStatus(router, working = true)
        try {
            val income = repository.monthlyIncome.value
            for (insight in base) {
                val signature = signatureOf(insight)
                if (insightRewrites.value[insight.id]?.signature == signature) continue
                val rewrite = enhancer.rewriteInsight(insight, income) ?: continue
                insightRewrites.value = insightRewrites.value + (insight.id to CachedRewrite(signature, rewrite))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            refreshStatus(router, working = false)
        }
    }

    private suspend fun narrateSimulation(router: AiBackendRouter, base: SimulationResult) {
        val signature = signatureOf(base)
        if (simulationNarration.value?.signature == signature) return
        refreshStatus(router, working = true)
        try {
            val explanation = enhancer.explainSimulation(base)
            val goalContext = mutableMapOf<String, String>()
            if (explanation != null) {
                simulationNarration.value = CachedNarration(signature, explanation, emptyMap())
            }
            for (projection in base.goalProjections) {
                val context = enhancer.goalMilestoneContext(projection, base.monthlySavings) ?: continue
                goalContext[projection.goal.id] = context.text
                simulationNarration.value = CachedNarration(signature, explanation, goalContext.toMap())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            refreshStatus(router, working = false)
        }
    }

    private suspend fun refreshStatus(router: AiBackendRouter, working: Boolean) {
        val effective = router.effective()
        _aiStatus.value = AiFeatureStatus(
            working = working && effective is EffectiveBackend.Generative,
            backendLabel = (effective as? EffectiveBackend.Generative)?.label,
            deterministicReason = (effective as? EffectiveBackend.Deterministic)?.reason,
            lastError = router.lastError.value
        )
    }

    private fun signatureOf(insight: AiInsight): String =
        "${insight.title}|${insight.description}|${insight.actionSuggestion}|${insight.impactAmount}"

    private fun signatureOf(result: SimulationResult): String =
        "${result.targetReductions.toSortedMap(compareBy { it.name })}|${result.monthlySavings}|" +
            result.goalProjections.joinToString(",") {
                "${it.goal.id}:${it.goal.title}:${it.goal.targetAmount}:${it.goal.currentSaved}:${it.monthsToReach}"
            }

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

    /**
     * Saves an edited transaction. Whether it counts as spending follows the category, exactly as when it
     * was added (Salary is income, everything else an expense). Returns false for a blank title or a
     * non-positive / non-finite amount, in which case nothing is written.
     */
    fun updateTransaction(
        original: Transaction,
        title: String,
        amount: Double,
        category: TransactionCategory,
        date: LocalDate,
        notes: String
    ): Boolean {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty() || !amount.isFinite() || amount <= 0.0) return false
        repository.updateTransaction(
            original.copy(
                title = trimmedTitle,
                amount = amount,
                category = category,
                date = date,
                isExpense = (category != TransactionCategory.SALARY),
                notes = notes.trim()
            )
        )
        return true
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

    /**
     * Sets the monthly income the dashboard, insights and simulator work from. Returns false (and
     * changes nothing) unless [income] is a finite amount above zero.
     */
    fun setMonthlyIncome(income: Double): Boolean {
        if (!income.isFinite() || income <= 0.0) return false
        repository.updateMonthlyIncome(income)
        return true
    }

    /** Saves the name the dashboard greets; a blank name clears it (the greeting falls back to "Welcome back"). */
    fun setUserName(name: String) {
        repository.updateUserName(name)
    }

    /**
     * Adds a savings goal. Returns false (nothing written) for a blank title, a target that is not above
     * zero, or a saved-so-far amount that is negative or not finite.
     */
    fun addGoal(title: String, targetAmount: Double, currentSaved: Double): Boolean {
        val goal = buildGoal(UUID.randomUUID().toString(), title, targetAmount, currentSaved) ?: return false
        repository.addGoal(goal)
        return true
    }

    /** Saves an edited goal, with the same validation as [addGoal]; its icon and description are kept. */
    fun updateGoal(original: SavingsGoal, title: String, targetAmount: Double, currentSaved: Double): Boolean {
        val goal = buildGoal(original.id, title, targetAmount, currentSaved)
            ?.copy(icon = original.icon, description = original.description)
            ?: return false
        repository.updateGoal(goal)
        return true
    }

    fun deleteGoal(id: String) {
        repository.deleteGoal(id)
    }

    private fun buildGoal(id: String, title: String, targetAmount: Double, currentSaved: Double): SavingsGoal? {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return null
        if (!targetAmount.isFinite() || targetAmount <= 0.0) return null
        if (!currentSaved.isFinite() || currentSaved < 0.0) return null
        return SavingsGoal(
            id = id,
            title = trimmedTitle,
            targetAmount = targetAmount,
            currentSaved = currentSaved,
            icon = DEFAULT_GOAL_ICON,
            description = ""
        )
    }

    /**
     * Ends the first-launch intro and never shows it again. With [monthlyIncome] / [name] / the goal
     * fields it first saves what was entered; call it with no arguments to skip and keep the defaults.
     * Returns false, leaving the intro open, if the income or the goal is invalid.
     */
    fun finishOnboarding(
        monthlyIncome: Double? = null,
        name: String = "",
        goalTitle: String = "",
        goalTarget: Double? = null
    ): Boolean {
        if (monthlyIncome != null && (!monthlyIncome.isFinite() || monthlyIncome <= 0.0)) return false
        val wantsGoal = goalTitle.isNotBlank() || goalTarget != null
        val goal = if (wantsGoal) {
            buildGoal(UUID.randomUUID().toString(), goalTitle, goalTarget ?: Double.NaN, 0.0)
                ?: return false
        } else {
            null
        }

        if (monthlyIncome != null) repository.updateMonthlyIncome(monthlyIncome)
        if (name.isNotBlank()) repository.updateUserName(name)
        if (goal != null) repository.addGoal(goal)
        repository.completeOnboarding()
        return true
    }

    /** Leaves the intro without changing anything: income stays at its default and no name or goal is saved. */
    fun skipOnboarding() {
        repository.completeOnboarding()
    }

    companion object {
        private const val SIMULATION_DEBOUNCE_MS = 700L
        const val DEFAULT_GOAL_ICON = "🎯"

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = DefaultFinSightRepository(context.applicationContext)
                val ai = com.example.finsightai.AiServices.get(context)
                return FinSightViewModel(repository, ai.router, ai.signal) as T
            }
        }
    }
}
