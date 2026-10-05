package com.example.finsightai.ai

import com.example.finsightai.model.GoalProjection
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.TransactionCategory
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class ForwardSimulatorEngine(
    private val defaultAnnualYield: Double = 0.045,
    private val defaultBaseSavings: Double = 300.0
) {

    fun simulate(
        baselineSpending: Map<TransactionCategory, Double>,
        targetReductions: Map<TransactionCategory, Float>,
        annualYield: Double = defaultAnnualYield,
        goals: List<SavingsGoal> = SavingsGoal.PREPOPULATED_GOALS,
        baseSavings: Double = defaultBaseSavings
    ): SimulationResult {
        var totalMonthlySavings = 0.0
        val categorySavings = mutableMapOf<TransactionCategory, Double>()

        targetReductions.forEach { (category, reductionFraction) ->
            val baseline = baselineSpending[category] ?: 0.0
            val clampedFraction = reductionFraction.coerceIn(0.0f, 1.0f).toDouble()
            val saved = baseline * clampedFraction
            categorySavings[category] = saved
            totalMonthlySavings += saved
        }

        val monthlySavingsRounded = roundToTwoDecimals(totalMonthlySavings)
        val proj3M = roundToTwoDecimals(calculateCompoundSavings(monthlySavingsRounded, 3, annualYield))
        val proj6M = roundToTwoDecimals(calculateCompoundSavings(monthlySavingsRounded, 6, annualYield))
        val proj12M = roundToTwoDecimals(calculateCompoundSavings(monthlySavingsRounded, 12, annualYield))

        val explanation = generateExplanation(
            targetReductions = targetReductions,
            categorySavings = categorySavings,
            monthlySavings = monthlySavingsRounded,
            projected12M = proj12M
        )

        val goalProjections = projectGoals(
            goals = goals,
            freedMonthlyCashflow = monthlySavingsRounded,
            baseSavings = baseSavings
        )

        return SimulationResult(
            targetReductions = targetReductions,
            monthlySavings = monthlySavingsRounded,
            projectedSavings3M = proj3M,
            projectedSavings6M = proj6M,
            projectedSavings12M = proj12M,
            aiExplanation = explanation,
            goalProjections = goalProjections
        )
    }

    fun calculateMonthsToGoal(
        targetAmount: Double,
        currentSaved: Double,
        freedMonthlyCashflow: Double,
        baseSavings: Double = defaultBaseSavings
    ): Int {
        val remaining = (targetAmount - currentSaved).coerceAtLeast(0.0)
        val monthlyContribution = baseSavings + freedMonthlyCashflow
        if (remaining <= 0.0) return 0
        if (monthlyContribution <= 0.0) return Int.MAX_VALUE
        return ceil(remaining / monthlyContribution).toInt()
    }

    fun projectGoals(
        goals: List<SavingsGoal> = SavingsGoal.PREPOPULATED_GOALS,
        freedMonthlyCashflow: Double,
        baseSavings: Double = defaultBaseSavings,
        referenceDate: LocalDate = LocalDate.now()
    ): List<GoalProjection> {
        return goals.map { goal ->
            val remaining = (goal.targetAmount - goal.currentSaved).coerceAtLeast(0.0)
            val progress = if (goal.targetAmount > 0.0) {
                (goal.currentSaved / goal.targetAmount).toFloat().coerceIn(0f, 1f)
            } else {
                1f
            }
            val months = calculateMonthsToGoal(
                targetAmount = goal.targetAmount,
                currentSaved = goal.currentSaved,
                freedMonthlyCashflow = freedMonthlyCashflow,
                baseSavings = baseSavings
            )

            val targetDate = referenceDate.plusMonths(months.toLong())
            val monthName = targetDate.month.name.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }
            val formattedTargetDate = "$monthName ${targetDate.year}"
            val formattedFreed = String.format(Locale.US, "%.0f", freedMonthlyCashflow)

            val badgeText = when {
                months == 0 -> "Goal Fully Funded! 🎉"
                freedMonthlyCashflow > 0.0 -> "Reached in $months months at +$$formattedFreed/mo • Funded by $formattedTargetDate!"
                else -> "Reached in $months months at baseline • Funded by $formattedTargetDate"
            }

            GoalProjection(
                goal = goal,
                remainingAmount = remaining,
                progressPercentage = progress,
                monthsToReach = months,
                formattedTargetDate = formattedTargetDate,
                badgeText = badgeText
            )
        }
    }

    fun calculateCompoundSavings(
        monthlyDeposit: Double,
        months: Int,
        annualYield: Double = defaultAnnualYield
    ): Double {
        if (monthlyDeposit <= 0.0 || months <= 0) return 0.0
        val monthlyRate = annualYield / 12.0
        var balance = 0.0
        for (i in 1..months) {
            balance = (balance + monthlyDeposit) * (1.0 + monthlyRate)
        }
        return balance
    }

    private fun generateExplanation(
        targetReductions: Map<TransactionCategory, Float>,
        categorySavings: Map<TransactionCategory, Double>,
        monthlySavings: Double,
        projected12M: Double
    ): String {
        if (monthlySavings <= 0.5) {
            return "Adjust the reduction sliders to simulate how minor habit tweaks in discretionary categories can compound into a resilient financial runway."
        }

        val cafeSaved = categorySavings[TransactionCategory.CAFES_DINING] ?: 0.0
        val techSaved = categorySavings[TransactionCategory.TECH_GADGETS] ?: 0.0
        val subSaved = categorySavings[TransactionCategory.SUBSCRIPTIONS] ?: 0.0

        val formattedMonthly = String.format(Locale.US, "%.0f", monthlySavings)
        val formatted12M = String.format(Locale.US, "%.0f", projected12M)

        return when {
            cafeSaved > 0 && techSaved > 0 -> {
                val cafeVisits = maxOf(1, (cafeSaved / 28.0).roundToInt() * 2)
                "By cutting back $cafeVisits cafe visits per week and moderating tech impulse upgrades ($$formattedMonthly/mo), you'll accumulate $$formatted12M in 12 months — building a formidable emergency tech reserve without sacrificing weekend leisure."
            }
            cafeSaved > 0 && techSaved <= 0.1 -> {
                val cafeVisits = maxOf(1, (cafeSaved / 28.0).roundToInt() * 2)
                "By cutting back $cafeVisits cafe visits per week ($$formattedMonthly/mo), you'll accumulate $$formatted12M in 12 months — enough for your emergency tech reserve without sacrificing weekend leisure."
            }
            techSaved > 0 && cafeSaved <= 0.1 -> {
                val techReductionPct = ((targetReductions[TransactionCategory.TECH_GADGETS] ?: 0.15f) * 100).toInt()
                "By pacing tech gadget upgrades by $techReductionPct% ($$formattedMonthly/mo), you'll accumulate $$formatted12M in 12 months — keeping your hardware fresh while funding a high-yield safety net."
            }
            subSaved > 0 -> {
                "By pruning redundant cloud and media subscriptions ($$formattedMonthly/mo), you'll accumulate $$formatted12M in 12 months with effortless compound returns."
            }
            else -> {
                "By optimizing your monthly discretionary expenses ($$formattedMonthly/mo), you'll accumulate $$formatted12M in 12 months — strengthening your financial independence as an early-career engineer."
            }
        }
    }

    private fun roundToTwoDecimals(value: Double): Double {
        return (value * 100.0).roundToInt() / 100.0
    }
}
