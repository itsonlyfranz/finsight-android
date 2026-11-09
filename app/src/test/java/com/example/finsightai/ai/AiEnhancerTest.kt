package com.example.finsightai.ai

import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.InsightType
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.testdata.SampleData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEnhancerTest {
    private val insight = AiInsight(
        id = "i1", title = "High Cafe Velocity",
        description = "You spent ₱412.50 on cafes.", type = InsightType.RISK_NUDGE,
        impactAmount = 412.5, actionSuggestion = "Brew at home to save ~₱100.00/mo."
    )

    private fun source(text: String?) = GenerativeSource { text?.let { GeneratedText(it, "Test Model") } }

    @Test fun rewriteParsesTwoLineFormat() = runTest {
        val e = AiEnhancer(source("INSIGHT: Your cafe habit hit ₱412.50 this month.\nACTION: Brew at home to save ₱100.00."))
        val r = e.rewriteInsight(insight, 45000.0)
        assertNotNull(r)
        assertEquals("Your cafe habit hit ₱412.50 this month.", r!!.description)
        assertEquals("Brew at home to save ₱100.00.", r.actionSuggestion)
        assertEquals("Test Model", r.by)
    }

    @Test fun rewriteRejectsInventedDollarFigures() = runTest {
        val e = AiEnhancer(source("INSIGHT: You wasted ₱999.00 on cafes.\nACTION: Stop."))
        assertNull(e.rewriteInsight(insight, 45000.0))
    }

    @Test fun rewriteReturnsNullWhenNoBackend() = runTest {
        assertNull(AiEnhancer(NoGenerativeSource).rewriteInsight(insight, 45000.0))
        assertNull(AiEnhancer(source(null)).rewriteInsight(insight, 45000.0))
    }

    @Test fun unformattedReplyIsAcceptedAsInsight() = runTest {
        val r = AiEnhancer(source("Cafes cost you ₱412.50.")).rewriteInsight(insight, 45000.0)
        assertEquals("Cafes cost you ₱412.50.", r!!.description)
        assertEquals(insight.actionSuggestion, r.actionSuggestion)
    }

    @Test fun goalContextIsGrounded() = runTest {
        val projection = com.example.finsightai.ai.ForwardSimulatorEngine()
            .projectGoals(SampleData.goals(), 200.0).first()
        val ok = AiEnhancer(source("You still need ₱46,200.00 for the emergency fund.")).goalMilestoneContext(projection, 200.0)
        assertNotNull(ok)
        val bad = AiEnhancer(source("You need ₱1.00 more.")).goalMilestoneContext(projection, 200.0)
        assertNull(bad)
    }

    @Test fun simulationSkippedWhenNothingSaved() = runTest {
        val result = ForwardSimulatorEngine().simulate(emptyMap(), mapOf(TransactionCategory.CAFES_DINING to 0f))
        assertNull(AiEnhancer(source("anything")).explainSimulation(result))
    }

    @Test fun cleanerStripsThinkingAndMarkdown() {
        assertEquals("Save more.", OutputCleaner.clean("<think>hmm ₱5</think>**Save** more."))
        assertEquals("Answer.", OutputCleaner.clean("reasoning...</think>\"Answer.\""))
        assertTrue(OutputCleaner.clean("word. ".repeat(300)).length <= 601)
    }

    @Test fun numberGuardAllowsRoundedFigures() {
        assertTrue(NumberGuard.isGrounded("About ₱413 a month", "Spent ₱412.60"))
        assertFalse(NumberGuard.isGrounded("About ₱500", "Spent ₱412.60"))
        assertTrue(NumberGuard.isGrounded("No money talk", "Spent ₱1.00"))
    }

    @Test fun numberGuardChecksPhpSpelledFigures() {
        assertFalse(NumberGuard.isGrounded("You wasted PHP 999.00 on cafes.", "Spent ₱412.60"))
        assertFalse(NumberGuard.isGrounded("You wasted Php999 on cafes.", "Spent ₱412.60"))
        assertTrue(NumberGuard.isGrounded("You spent PHP 412.60 on cafes.", "Spent ₱412.60"))
        assertTrue(NumberGuard.isGrounded("About Php413 a month", "Spent ₱412.60"))
        assertTrue(NumberGuard.isGrounded("Under PHP 1,100 a month", "Budget PHP 1,100.00"))
    }

    @Test fun rewriteRejectsInventedPhpFigures() = runTest {
        val e = AiEnhancer(source("INSIGHT: You wasted PHP 999.00 on cafes.\nACTION: Stop."))
        assertNull(e.rewriteInsight(insight, 45000.0))
    }

    @Test fun numberGuardStillChecksDollarSignedFigures() {
        // A model that ignores the peso instruction and writes "$" is held to the same facts.
        assertTrue(NumberGuard.isGrounded("About $413 a month", "Spent ₱412.60"))
        assertFalse(NumberGuard.isGrounded("About $500", "Spent ₱412.60"))
    }

    @Test fun promptsTellTheModelAmountsArePesos() = runTest {
        var request: AiRequest? = null
        val e = AiEnhancer { r -> request = r; GeneratedText("INSIGHT: Fine.\nACTION: Go.", "t") }
        e.rewriteInsight(insight, 45000.0)
        val sent = request!!
        assertTrue(sent.system.contains("Philippine pesos"))
        assertTrue(sent.user.contains("Monthly take-home income: ₱45,000.00"))
        assertFalse(sent.user.contains("$"))
    }
}
