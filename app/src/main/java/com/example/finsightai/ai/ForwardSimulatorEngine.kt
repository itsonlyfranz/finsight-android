package com.example.finsightai.ai

import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.TransactionCategory
import java.util.Locale
import kotlin.math.roundToInt

class ForwardSimulatorEngine(
    private val defaultAnnualYield: Double = 0.045
) {

    fun simulate(
        baselineSpending: Map<TransactionCategory, Double>,
        targetReductions: Map<TransactionCategory, Float>,
        annualYield: Double = defaultAnnualYield
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

        return SimulationResult(
            targetReductions = targetReductions,
            monthlySavings = monthlySavingsRounded,
            projectedSavings3M = proj3M,
            projectedSavings6M = proj6M,
            projectedSavings12M = proj12M,
            aiExplanation = explanation
        )
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
