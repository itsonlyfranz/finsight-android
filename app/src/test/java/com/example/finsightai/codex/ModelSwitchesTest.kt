package com.example.finsightai.codex

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only a user-visible change from one model to another should restart the AI work. */
class ModelSwitchesTest {
    @Test fun resolvingTheFirstModelIsNotASwitch() = runBlocking {
        assertEquals(listOf(0), flowOf(null, "codex-auto-review").modelSwitches().toList())
    }

    @Test fun pickingAnotherModelIsASwitch() = runBlocking {
        assertEquals(listOf(0, 1), flowOf(null, "codex-auto-review", "gpt-mini").modelSwitches().toList())
    }

    @Test fun signingOutAndBackInIsNotASwitch() = runBlocking {
        assertEquals(listOf(0), flowOf("codex-auto-review", null, "codex-auto-review", "codex-auto-review").modelSwitches().toList())
    }
}
