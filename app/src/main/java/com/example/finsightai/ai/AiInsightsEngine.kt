package com.example.finsightai.ai

import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.InsightType
import com.example.finsightai.model.Severity
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import java.util.Locale

class AiInsightsEngine {

    fun generateInsights(
        transactions: List<Transaction>,
        monthlyIncome: Double = 3200.0,
        customBudgets: Map<TransactionCategory, Double> = emptyMap()
    ): List<AiInsight> {
        val insights = mutableListOf<AiInsight>()

        val expenseTransactions = transactions.filter { it.isExpense && it.category != TransactionCategory.SALARY }
        val categoryTotals = expenseTransactions.groupBy { it.category }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }

        // 1. Detect Cafe Hopping & Dining Lifestyle Inflation
        val cafeTransactions = expenseTransactions.filter { it.category == TransactionCategory.CAFES_DINING }
        val cafeCount = cafeTransactions.size
        val cafeSpend = categoryTotals[TransactionCategory.CAFES_DINING] ?: 0.0
        val cafeIncomePercent = if (monthlyIncome > 0) (cafeSpend / monthlyIncome) * 100.0 else 0.0
        val cafeBudget = customBudgets[TransactionCategory.CAFES_DINING]
            ?: TransactionCategory.CAFES_DINING.defaultMonthlyBudget

        if (cafeCount > 8 || cafeIncomePercent > 15.0 || cafeSpend > cafeBudget) {
            val severity = when {
                cafeIncomePercent > 20.0 || cafeSpend > cafeBudget * 1.5 -> Severity.HIGH
                cafeIncomePercent > 15.0 || cafeCount > 10 -> Severity.MEDIUM
                else -> Severity.LOW
            }

            val desc = buildString {
                append("You've recorded $cafeCount cafe & dining outings totaling $${formatAmount(cafeSpend)}")
                if (monthlyIncome > 0) {
                    append(" (~${formatPercent(cafeIncomePercent)}% of your monthly income)")
                }
                append(". Small frequent swipes easily slip under the radar for junior engineers.")
            }

            val savingsEstimate = (cafeSpend * 0.25).coerceAtLeast(30.0)
            insights.add(
                AiInsight(
                    id = "insight_cafe_lifestyle_inflation",
                    title = "High Cafe & Dining Velocity",
                    description = desc,
                    type = InsightType.RISK_NUDGE,
                    impactAmount = cafeSpend,
                    actionSuggestion = "Try a 2-day homebrew routine during weekdays to reclaim ~$${formatAmount(savingsEstimate)}/mo while keeping social weekend brunches intact.",
                    severity = severity
                )
            )
        }

        // 2. Detect Tech Gadget Spikes
        val techSpend = categoryTotals[TransactionCategory.TECH_GADGETS] ?: 0.0
        val techBudget = customBudgets[TransactionCategory.TECH_GADGETS]
            ?: TransactionCategory.TECH_GADGETS.defaultMonthlyBudget
        val techIncomePercent = if (monthlyIncome > 0) (techSpend / monthlyIncome) * 100.0 else 0.0

        if (techSpend > techBudget || techIncomePercent > 10.0) {
            val severity = if (techSpend > techBudget * 1.5) Severity.HIGH else Severity.MEDIUM
            val overage = techSpend - techBudget

            insights.add(
                AiInsight(
                    id = "insight_tech_gadget_spike",
                    title = "Tech & Gadget Spend Spike",
                    description = "Tech purchases reached $${formatAmount(techSpend)} this cycle" +
                        if (overage > 0) " ($${formatAmount(overage)} over your $${formatAmount(techBudget)} target)." else ".",
                    type = InsightType.RISK_NUDGE,
                    impactAmount = techSpend,
                    actionSuggestion = "Adopt a 14-day cooling-off rule on new peripherals and mechanical keyboards to avoid dopamine impulse buys.",
                    severity = severity
                )
            )
        }

        // 3. Subscription Drift Detection
        val subSpend = categoryTotals[TransactionCategory.SUBSCRIPTIONS] ?: 0.0
        val subBudget = customBudgets[TransactionCategory.SUBSCRIPTIONS]
            ?: TransactionCategory.SUBSCRIPTIONS.defaultMonthlyBudget
        if (subSpend > subBudget || subSpend > 100.0) {
            insights.add(
                AiInsight(
                    id = "insight_subscription_drift",
                    title = "Recurring Subscription Drift",
                    description = "Monthly recurring subscriptions currently sit at $${formatAmount(subSpend)}. Overlapping media or AI coding copilot tools add up fast.",
                    type = InsightType.RISK_NUDGE,
                    impactAmount = subSpend,
                    actionSuggestion = "Audit active tools and downgrade duplicate streaming or SaaS subscriptions to free up ~$30-$40/mo.",
                    severity = Severity.LOW
                )
            )
        }

        // 4. Compute Emergency Buffer & Growth Focus
        val totalExpense = expenseTransactions.sumOf { it.amount }
        val netSavings = (monthlyIncome - totalExpense).coerceAtLeast(0.0)

        val essentialSpend = (categoryTotals[TransactionCategory.HOUSING_RENT] ?: 0.0) +
            (categoryTotals[TransactionCategory.GROCERIES] ?: 0.0) +
            (categoryTotals[TransactionCategory.TRANSPORT] ?: 0.0)

        val baselineEssentials = if (essentialSpend > 0) essentialSpend else 1400.0
        // Calculate estimated emergency buffer in months assuming realistic starter savings reserve
        val starterReserve = (monthlyIncome * 0.9) + netSavings
        val emergencyMonths = starterReserve / baselineEssentials

        insights.add(
            AiInsight(
                id = "insight_emergency_fund_status",
                title = "Emergency Fund on Track: ${formatMonths(emergencyMonths)} Months",
                description = "Your essential overhead (rent, groceries, commute) is ~$${formatAmount(baselineEssentials)}/mo. You currently retain $${formatAmount(netSavings)} in monthly free cashflow.",
                type = InsightType.GROWTH_FOCUS,
                impactAmount = netSavings,
                actionSuggestion = "Maintain 3-6 months of liquid reserves in a 4.5%+ APY High-Yield Savings Account before scaling aggressive investments.",
                severity = Severity.LOW
            )
        )

        // Discretionary spending pacing
        val discretionarySpend = (categoryTotals[TransactionCategory.CAFES_DINING] ?: 0.0) +
            (categoryTotals[TransactionCategory.TECH_GADGETS] ?: 0.0) +
            (categoryTotals[TransactionCategory.LIFESTYLE] ?: 0.0)

        val savingsRate = if (monthlyIncome > 0) (netSavings / monthlyIncome) * 100.0 else 0.0
        insights.add(
            AiInsight(
                id = "insight_discretionary_growth",
                title = "Discretionary Spending Pacing",
                description = "Discretionary spend is $${formatAmount(discretionarySpend)}. You are successfully routing ${formatPercent(savingsRate)}% of your earnings to wealth accumulation.",
                type = InsightType.GROWTH_FOCUS,
                impactAmount = discretionarySpend,
                actionSuggestion = "Automate an auto-transfer of $${formatAmount(netSavings * 0.7)} into your savings on every direct deposit date.",
                severity = Severity.LOW
            )
        )

        // 5. Contextual Tips for Early-Career Earners
        insights.add(
            AiInsight(
                id = "insight_tip_401k_match",
                title = "Capture Full 401(k) Employer Match",
                description = "As a software engineer, your company likely offers a 3-5% 401(k) match. This represents an immediate 100% risk-free return on your money.",
                type = InsightType.TIP,
                impactAmount = monthlyIncome * 0.04,
                actionSuggestion = "Verify your HR payroll portal to ensure your elective deferral at least meets the full employer matching ceiling.",
                severity = Severity.LOW
            )
        )

        insights.add(
            AiInsight(
                id = "insight_tip_employer_stipend",
                title = "Leverage WFH & Learning Stipends",
                description = "Before paying out-of-pocket for keyboards, monitors, or tech books, check your company's fringe benefits for equipment and education budgets.",
                type = InsightType.TIP,
                impactAmount = 250.0,
                actionSuggestion = "Submit monitor and desk setup receipts through your corporate expense management system.",
                severity = Severity.LOW
            )
        )

        return insights
    }

    private fun formatAmount(amount: Double): String = String.format(Locale.US, "%.2f", amount)
    private fun formatPercent(percent: Double): String = String.format(Locale.US, "%.1f", percent)
    private fun formatMonths(months: Double): String = String.format(Locale.US, "%.1f", months)
}
