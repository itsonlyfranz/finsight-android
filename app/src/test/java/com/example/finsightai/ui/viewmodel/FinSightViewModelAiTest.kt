package com.example.finsightai.ui.viewmodel

import com.example.finsightai.ai.backend.AiBackendRouter
import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.ai.backend.TextGenerator
import com.example.finsightai.data.DefaultFinSightRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The generative layer is optional: numbers and default wording stay deterministic, a ready model only rewrites text. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinSightViewModelAiTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeGenerator(var ready: Boolean, var reply: String) : TextGenerator {
        override val id = "on-device"
        var calls = 0
        override suspend fun describe() = if (ready) "Fake 1B" else null
        override suspend fun isReady() = ready
        override suspend fun generate(request: AiRequest): String { calls++; return reply }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(generator: FakeGenerator, signal: MutableStateFlow<Int>): FinSightViewModel {
        val router = AiBackendRouter(MutableStateFlow(BackendChoice.ON_DEVICE), mapOf(BackendChoice.ON_DEVICE to generator))
        return FinSightViewModel(DefaultFinSightRepository(), router, signal)
    }

    @Test fun readyModelRewritesInsightsSimulationAndGoalsButNotTheNumbers() = runTest(dispatcher) {
        val baseline = FinSightViewModel(DefaultFinSightRepository())
        val generator = FakeGenerator(true, "INSIGHT: Rewritten by the model.\nACTION: Do the thing.")
        val viewModel = vm(generator, MutableStateFlow(0))
        advanceUntilIdle()

        val insights = viewModel.insights.value
        assertTrue(insights.isNotEmpty())
        assertTrue(insights.all { it.generatedBy == "Fake 1B" && it.description == "Rewritten by the model." })
        // Titles, severity and impact are the deterministic engine's.
        assertEquals(baseline.insights.value.map { it.title to it.impactAmount }, insights.map { it.title to it.impactAmount })

        val sim = viewModel.simulationResult.value
        assertEquals("Fake 1B", sim.generatedBy)
        assertEquals(baseline.simulationResult.value.monthlySavings, sim.monthlySavings, 0.0)
        assertEquals(baseline.simulationResult.value.projectedSavings12M, sim.projectedSavings12M, 0.0)
        assertTrue(sim.goalProjections.all { it.aiContext != null })
        assertEquals(baseline.simulationResult.value.goalProjections.map { it.monthsToReach }, sim.goalProjections.map { it.monthsToReach })
        assertEquals("Fake 1B", viewModel.aiStatus.value.backendLabel)
    }

    @Test fun notReadyKeepsDeterministicTextAndNeverCallsTheModel() = runTest(dispatcher) {
        val generator = FakeGenerator(false, "INSIGHT: nope")
        val viewModel = vm(generator, MutableStateFlow(0))
        advanceUntilIdle()
        assertTrue(viewModel.insights.value.all { it.generatedBy == null })
        assertNull(viewModel.simulationResult.value.generatedBy)
        assertEquals(0, generator.calls)
        assertNotNull(viewModel.aiStatus.value.deterministicReason)
    }

    @Test fun modelBecomingUnavailableRestoresDeterministicText() = runTest(dispatcher) {
        val generator = FakeGenerator(true, "INSIGHT: Rewritten by the model.")
        val signal = MutableStateFlow(0)
        val viewModel = vm(generator, signal)
        advanceUntilIdle()
        assertTrue(viewModel.insights.value.all { it.generatedBy != null })

        generator.ready = false // e.g. the active model was deleted
        signal.value = 1
        advanceUntilIdle()
        assertTrue(viewModel.insights.value.all { it.generatedBy == null })
        assertNull(viewModel.simulationResult.value.generatedBy)
    }

    @Test fun hallucinatedMoneyIsRejectedAndFallsBack() = runTest(dispatcher) {
        val generator = FakeGenerator(true, "INSIGHT: You should have $1,000,000.\nACTION: Win the lottery.")
        val viewModel = vm(generator, MutableStateFlow(0))
        advanceUntilIdle()
        assertTrue(viewModel.insights.value.all { it.generatedBy == null })
    }
}
