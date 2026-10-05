package com.example.finsightai.ai

import com.example.finsightai.model.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForwardSimulatorEngineTest {

    private lateinit var simulator: ForwardSimulatorEngine

    @Before
    fun setUp() {
        simulator = ForwardSimulatorEngine(defaultAnnualYield = 0.045)
    }

    @Test
    fun `simulate produces zero savings when no reductions specified`() {
        val baseline = mapOf(
            TransactionCategory.CAFES_DINING to 280.0,
            TransactionCategory.TECH_GADGETS to 420.0
        )

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = emptyMap()
        )

        assertEquals(0.0, result.monthlySavings, 0.01)
        assertEquals(0.0, result.projectedSavings3M, 0.01)
        assertEquals(0.0, result.projectedSavings6M, 0.01)
        assertEquals(0.0, result.projectedSavings12M, 0.01)
        assertTrue(result.aiExplanation.contains("Adjust the reduction sliders"))
    }

    @Test
    fun `simulate calculates compound savings accurately for cafe cutback`() {
        // Mark Santos: $280 baseline on cafes, 20% reduction = $56/mo
        val baseline = mapOf(TransactionCategory.CAFES_DINING to 280.0)
        val reductions = mapOf(TransactionCategory.CAFES_DINING to 0.20f)

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        assertEquals(56.0, result.monthlySavings, 0.01)

        // Deterministic compound checking at 4.5% APY
        // 3 months: > 3 * 56 = 168.0
        assertTrue("3M projection should include compound yield", result.projectedSavings3M > 168.0)
        // 6 months: > 6 * 56 = 336.0
        assertTrue("6M projection should include compound yield", result.projectedSavings6M > 336.0)
        // 12 months: > 12 * 56 = 672.0
        assertTrue("12M projection should include compound yield", result.projectedSavings12M > 672.0)

        // Explanation verification
        assertTrue("Explanation should mention cafe visits", result.aiExplanation.contains("cafe visits"))
        assertTrue("Explanation should mention monthly amount", result.aiExplanation.contains("56"))
        assertTrue("Explanation should mention 12 months", result.aiExplanation.contains("12 months"))
    }

    @Test
    fun `simulate generates tailored tech explanation for gadget reductions`() {
        val baseline = mapOf(TransactionCategory.TECH_GADGETS to 400.0)
        val reductions = mapOf(TransactionCategory.TECH_GADGETS to 0.15f) // $60/mo

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        assertEquals(60.0, result.monthlySavings, 0.01)
        assertTrue(result.aiExplanation.contains("tech gadget upgrades"))
        assertTrue(result.aiExplanation.contains("60"))
    }

    @Test
    fun `simulate handles combined cafe and tech reduction`() {
        val baseline = mapOf(
            TransactionCategory.CAFES_DINING to 280.0,
            TransactionCategory.TECH_GADGETS to 420.0
        )
        val reductions = mapOf(
            TransactionCategory.CAFES_DINING to 0.20f, // $56
            TransactionCategory.TECH_GADGETS to 0.15f  // $63
        )

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        // $56 + $63 = $119/mo
        assertEquals(119.0, result.monthlySavings, 0.01)
        assertTrue("12M projection should exceed 12 * 119 = 1428", result.projectedSavings12M > 1428.0)
        assertTrue(result.aiExplanation.contains("cafe visits"))
        assertTrue(result.aiExplanation.contains("tech impulse"))
    }

    @Test
    fun `calculateCompoundSavings returns zero for invalid inputs`() {
        assertEquals(0.0, simulator.calculateCompoundSavings(-10.0, 12), 0.001)
        assertEquals(0.0, simulator.calculateCompoundSavings(100.0, 0), 0.001)
    }

    @Test
    fun `calculateMonthsToGoal computes accurate ceiling division with freed cashflow`() {
        // Target $5,400, saved $2,100 -> remaining $3,300
        // Base savings = 300, freed = 0 -> 3300 / 300 = 11 months
        val monthsBaseline = simulator.calculateMonthsToGoal(
            targetAmount = 5400.0,
            currentSaved = 2100.0,
            freedMonthlyCashflow = 0.0,
            baseSavings = 300.0
        )
        assertEquals(11, monthsBaseline)

        // Freed cashflow = $178/mo -> pool = $478 -> ceil(3300 / 478) = 7 months
        val monthsAccelerated = simulator.calculateMonthsToGoal(
            targetAmount = 5400.0,
            currentSaved = 2100.0,
            freedMonthlyCashflow = 178.0,
            baseSavings = 300.0
        )
        assertEquals(7, monthsAccelerated)
    }

    @Test
    fun `projectGoals produces dynamic badges and timeline projections`() {
        val projections = simulator.projectGoals(
            freedMonthlyCashflow = 178.0,
            baseSavings = 300.0
        )

        assertEquals(3, projections.size)

        val emergencyGoal = projections.find { it.goal.id == "goal_emergency" }
        assertNotNull(emergencyGoal)
        assertEquals(7, emergencyGoal!!.monthsToReach)
        assertTrue(emergencyGoal.badgeText.contains("Reached in 7 months at +$178/mo"))
        assertTrue(emergencyGoal.badgeText.contains("Funded by"))

        val devRigGoal = projections.find { it.goal.id == "goal_dev_rig" }
        assertNotNull(devRigGoal)
        // Target $3200, saved $800 -> remaining $2400 / 478 = ceil(5.02) = 6 months
        assertEquals(6, devRigGoal!!.monthsToReach)
    }
}
