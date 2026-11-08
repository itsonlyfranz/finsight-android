package com.example.finsightai.ai.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBackendRouterTest {
    private class Fake(
        override val id: String, var ready: Boolean = true, var fail: Boolean = false, val label: String = id
    ) : TextGenerator {
        var calls = 0
        override suspend fun describe() = if (ready) label else null
        override suspend fun isReady() = ready
        override suspend fun generate(request: AiRequest): String {
            calls++
            if (fail) throw AiBackendException("boom")
            return "$id says hi"
        }
    }

    private val req = AiRequest("s", "u")

    @Test fun routesToSelectedBackendOnly() = runTest {
        val onDevice = Fake("on-device"); val codex = Fake("codex")
        val choice = MutableStateFlow(BackendChoice.CODEX)
        val router = AiBackendRouter(choice, mapOf(BackendChoice.ON_DEVICE to onDevice, BackendChoice.CODEX to codex))
        assertEquals("codex says hi", router.generate(req)!!.text)
        assertEquals(0, onDevice.calls)
        choice.value = BackendChoice.ON_DEVICE
        assertEquals("on-device says hi", router.generate(req)!!.text)
    }

    @Test fun fallsBackToDeterministicWhenNotReady() = runTest {
        val onDevice = Fake("on-device", ready = false)
        val router = AiBackendRouter(MutableStateFlow(BackendChoice.ON_DEVICE), mapOf(BackendChoice.ON_DEVICE to onDevice))
        assertNull(router.generate(req))
        assertEquals(0, onDevice.calls)
        val eff = router.effective()
        assertTrue(eff is EffectiveBackend.Deterministic)
    }

    @Test fun failureReturnsNullAndRecordsError() = runTest {
        val codex = Fake("codex", fail = true)
        val router = AiBackendRouter(MutableStateFlow(BackendChoice.CODEX), mapOf(BackendChoice.CODEX to codex))
        assertNull(router.generate(req))
        assertEquals("boom", router.lastError.value)
        codex.fail = false
        assertNotNull(router.generate(req))
        assertNull(router.lastError.value)
    }

    @Test fun deletedActiveModelFallsBackThenCodexTakesOver() = runTest {
        val onDevice = Fake("on-device"); val codex = Fake("codex", label = "Codex")
        val choice = MutableStateFlow(BackendChoice.ON_DEVICE)
        val router = AiBackendRouter(choice, mapOf(BackendChoice.ON_DEVICE to onDevice, BackendChoice.CODEX to codex))
        onDevice.ready = false // active model deleted
        assertNull(router.generate(req))
        choice.value = BackendChoice.CODEX
        val eff = router.effective() as EffectiveBackend.Generative
        assertEquals("Codex", eff.label)
    }
}
