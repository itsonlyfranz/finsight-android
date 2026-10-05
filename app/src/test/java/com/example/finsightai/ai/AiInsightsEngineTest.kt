package com.example.finsightai.ai

import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.model.InsightType
import com.example.finsightai.model.Severity
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class AiInsightsEngineTest {

    private lateinit var engine: AiInsightsEngine

    @Before
    fun setUp() {
        engine = AiInsightsEngine()
    }

    @Test
    fun `detects cafe hopping when visit count exceeds threshold`() {
        val cafeTransactions = (1..10).map { i ->
            Transaction(
                id = "cafe_$i",
                title = "Coffee Shop $i",
                amount = 6.50,
                category = TransactionCategory.CAFES_DINING,
                date = LocalDate.now(),
                isExpense = true
            )
        }

        val insights = engine.generateInsights(
            transactions = cafeTransactions,
            monthlyIncome = 3200.0
        )

        val cafeNudge = insights.find { it.id == "insight_cafe_lifestyle_inflation" }
        assertNotNull("Expected cafe lifestyle inflation insight", cafeNudge)
        assertEquals(InsightType.RISK_NUDGE, cafeNudge?.type)
        assertTrue(cafeNudge?.description?.contains("10 cafe & dining outings") == true)
    }

    @Test
    fun `detects cafe spend when percentage of income exceeds 15 percent`() {
        val highSpendCafes = listOf(
            Transaction(
                id = "cafe_high_1",
                title = "Omakase Weekend",
                amount = 260.0,
                category = TransactionCategory.CAFES_DINING,
                date = LocalDate.now(),
                isExpense = true
            ),
            Transaction(
                id = "cafe_high_2",
                title = "Wine & Bistro",
                amount = 260.0,
                category = TransactionCategory.CAFES_DINING,
                date = LocalDate.now(),
                isExpense = true
            )
        )

        val insights = engine.generateInsights(
            transactions = highSpendCafes,
            monthlyIncome = 3200.0 // 520 / 3200 = 16.25%
        )

        val cafeNudge = insights.find { it.id == "insight_cafe_lifestyle_inflation" }
        assertNotNull(cafeNudge)
        assertEquals(InsightType.RISK_NUDGE, cafeNudge?.type)
        assertEquals(Severity.MEDIUM, cafeNudge?.severity)
    }

    @Test
    fun `detects tech gadget spend spike over budget`() {
        val techTransactions = listOf(
            Transaction(
                id = "tech_1",
                title = "Mechanical Keyboard",
                amount = 220.0,
                category = TransactionCategory.TECH_GADGETS,
                date = LocalDate.now(),
                isExpense = true
            ),
            Transaction(
                id = "tech_2",
                title = "Studio Monitor Headphones",
                amount = 180.0,
                category = TransactionCategory.TECH_GADGETS,
                date = LocalDate.now(),
                isExpense = true
            )
        )

        val insights = engine.generateInsights(
            transactions = techTransactions,
            monthlyIncome = 3200.0,
            customBudgets = mapOf(TransactionCategory.TECH_GADGETS to 250.0)
        )

        val techSpike = insights.find { it.id == "insight_tech_gadget_spike" }
        assertNotNull("Expected tech gadget spike insight", techSpike)
        assertEquals(InsightType.RISK_NUDGE, techSpike?.type)
        assertEquals(Severity.HIGH, techSpike?.severity)
        assertTrue(techSpike?.actionSuggestion?.contains("cooling-off") == true)
    }

    @Test
    fun `computes emergency buffer and discretionary growth focus`() {
        val transactions = listOf(
            Transaction(
                id = "rent",
                title = "Apartment Rent",
                amount = 950.0,
                category = TransactionCategory.HOUSING_RENT,
                date = LocalDate.now()
            ),
            Transaction(
                id = "groceries",
                title = "Groceries",
                amount = 300.0,
                category = TransactionCategory.GROCERIES,
                date = LocalDate.now()
            ),
            Transaction(
                id = "transit",
                title = "Transit Pass",
                amount = 100.0,
                category = TransactionCategory.TRANSPORT,
                date = LocalDate.now()
            )
        )

        val insights = engine.generateInsights(
            transactions = transactions,
            monthlyIncome = 3200.0
        )

        val emergencyInsight = insights.find { it.id == "insight_emergency_fund_status" }
        assertNotNull("Expected emergency fund growth insight", emergencyInsight)
        assertEquals(InsightType.GROWTH_FOCUS, emergencyInsight?.type)
        assertTrue(emergencyInsight?.title?.contains("Emergency Fund on Track") == true)

        val growthPacing = insights.find { it.id == "insight_discretionary_growth" }
        assertNotNull(growthPacing)
        assertEquals(InsightType.GROWTH_FOCUS, growthPacing?.type)
    }

    @Test
    fun `emits contextual tips for junior software engineers`() {
        val insights = engine.generateInsights(
            transactions = emptyList(),
            monthlyIncome = 3200.0
        )

        val tips = insights.filter { it.type == InsightType.TIP }
        assertTrue("Should emit at least 2 tips", tips.size >= 2)
        assertTrue(tips.any { it.title.contains("401(k)") })
        assertTrue(tips.any { it.title.contains("Stipend") })
    }

    @Test
    fun `analyzes Mark Santos seed data comprehensively`() {
        val seed = DefaultFinSightRepository.seedMarkSantosData()
        val insights = engine.generateInsights(
            transactions = seed,
            monthlyIncome = 3200.0
        )

        val riskNudges = insights.filter { it.type == InsightType.RISK_NUDGE }
        val growthFocus = insights.filter { it.type == InsightType.GROWTH_FOCUS }
        val tips = insights.filter { it.type == InsightType.TIP }

        assertTrue("Should have risk nudges", riskNudges.isNotEmpty())
        assertTrue("Should have growth focus insights", growthFocus.isNotEmpty())
        assertTrue("Should have tips", tips.isNotEmpty())

        // Mark has 14 cafe visits + 6 dining outings ($620 total in CAFES_DINING)
        val cafeInsight = insights.find { it.id == "insight_cafe_lifestyle_inflation" }
        assertNotNull(cafeInsight)

        // Mark spent $420 on Tech Gadgets (budget 250)
        val techInsight = insights.find { it.id == "insight_tech_gadget_spike" }
        assertNotNull(techInsight)

        // Mark spent $120 on Subscriptions
        val subInsight = insights.find { it.id == "insight_subscription_drift" }
        assertNotNull(subInsight)
    }
}
