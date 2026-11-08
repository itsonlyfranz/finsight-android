package com.example.finsightai.llm

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.ai.backend.AiRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real native inference on a real GGUF. Skipped unless a model has been pushed:
 *   adb push Bonsai-1.7B-Q1_0.gguf /data/local/tmp/finsight-smoke.gguf
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppRuntimeSmokeTest {
    private val model = File("/data/local/tmp/finsight-smoke.gguf")

    @Test
    fun nativeLibraryLoadsOnThisDevice() {
        val runtime = LlamaCppRuntime(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("libfinsight_llm.so must load on arm64", runtime.isSupported)
        assertTrue(runtime.systemInfo().isNotBlank())
    }

    @Test
    fun generatesTextFromRealModel() = runBlocking {
        assumeTrue("push a GGUF to ${model.path} to run this test", model.isFile)
        val runtime = LlamaCppRuntime(InstrumentationRegistry.getInstrumentation().targetContext)
        runtime.load("smoke", model)
        val pieces = StringBuilder()
        val text = runtime.generate(
            AiRequest(system = "You are a concise assistant.", user = "Say hello to Mark in one short sentence.", maxTokens = 24, temperature = 0.2f)
        ) { pieces.append(it) }
        runtime.unload()
        assertTrue("model produced text: '$text'", text.isNotBlank())
        assertTrue(pieces.toString() == text)
    }
}
