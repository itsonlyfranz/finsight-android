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
            TransactionCategory.CAFES_DINING to 4000.0,
            TransactionCategory.TECH_GADGETS to 5900.0
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
        // Mark Santos: ₱4,000 baseline on cafes, 20% reduction = ₱800/mo
        val baseline = mapOf(TransactionCategory.CAFES_DINING to 4000.0)
        val reductions = mapOf(TransactionCategory.CAFES_DINING to 0.20f)

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        assertEquals(800.0, result.monthlySavings, 0.01)

        // Deterministic compound checking at 4.5% APY
        // 3 months: > 3 * 800 = 2,400
        assertTrue("3M projection should include compound yield", result.projectedSavings3M > 2400.0)
        // 6 months: > 6 * 800 = 4,800
        assertTrue("6M projection should include compound yield", result.projectedSavings6M > 4800.0)
        // 12 months: > 12 * 800 = 9,600
        assertTrue("12M projection should include compound yield", result.projectedSavings12M > 9600.0)

        // Explanation verification
        assertTrue("Explanation should mention cafe visits", result.aiExplanation.contains("cafe visits"))
        assertTrue("Explanation should mention monthly amount", result.aiExplanation.contains("₱800/mo"))
        assertTrue("Explanation should mention 12 months", result.aiExplanation.contains("12 months"))
    }

    @Test
    fun `simulate generates tailored tech explanation for gadget reductions`() {
        val baseline = mapOf(TransactionCategory.TECH_GADGETS to 5600.0)
        val reductions = mapOf(TransactionCategory.TECH_GADGETS to 0.15f) // ₱840/mo

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        assertEquals(840.0, result.monthlySavings, 0.01)
        assertTrue(result.aiExplanation.contains("tech gadget upgrades"))
        assertTrue(result.aiExplanation.contains("₱840/mo"))
    }

    @Test
    fun `simulate handles combined cafe and tech reduction`() {
        val baseline = mapOf(
            TransactionCategory.CAFES_DINING to 4000.0,
            TransactionCategory.TECH_GADGETS to 5900.0
        )
        val reductions = mapOf(
            TransactionCategory.CAFES_DINING to 0.20f, // ₱800
            TransactionCategory.TECH_GADGETS to 0.15f  // ₱885
        )

        val result = simulator.simulate(
            baselineSpending = baseline,
            targetReductions = reductions
        )

        // ₱800 + ₱885 = ₱1,685/mo
        assertEquals(1685.0, result.monthlySavings, 0.01)
        assertTrue("12M projection should exceed 12 * 1,685 = 20,220", result.projectedSavings12M > 20220.0)
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
        // Target ₱75,600, saved ₱29,400 -> remaining ₱46,200
        // Base savings = 4,200, freed = 0 -> 46200 / 4200 = 11 months
        val monthsBaseline = simulator.calculateMonthsToGoal(
            targetAmount = 75600.0,
            currentSaved = 29400.0,
            freedMonthlyCashflow = 0.0,
            baseSavings = 4200.0
        )
        assertEquals(11, monthsBaseline)

        // Freed cashflow = ₱2,492/mo -> pool = ₱6,692 -> ceil(46200 / 6692) = 7 months
        val monthsAccelerated = simulator.calculateMonthsToGoal(
            targetAmount = 75600.0,
            currentSaved = 29400.0,
            freedMonthlyCashflow = 2492.0,
            baseSavings = 4200.0
        )
        assertEquals(7, monthsAccelerated)
    }

    @Test
    fun `projectGoals produces dynamic badges and timeline projections`() {
        val projections = simulator.projectGoals(
            freedMonthlyCashflow = 2492.0
        )

        assertEquals(3, projections.size)

        val emergencyGoal = projections.find { it.goal.id == "goal_emergency" }
        assertNotNull(emergencyGoal)
        assertEquals(7, emergencyGoal!!.monthsToReach)
        assertTrue(emergencyGoal.badgeText.contains("Reached in 7 months at +₱2,492/mo"))
        assertTrue(emergencyGoal.badgeText.contains("Funded by"))

        val devRigGoal = projections.find { it.goal.id == "goal_dev_rig" }
        assertNotNull(devRigGoal)
        // Target ₱44,800, saved ₱11,200 -> remaining ₱33,600 / 6,692 = ceil(5.02) = 6 months
        assertEquals(6, devRigGoal!!.monthsToReach)
    }
}
