package com.example.finsightai.llm

import com.example.finsightai.ai.backend.AiRequest
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceTextGeneratorTest {
    @Test fun answerIsLabelledWithTheModelThatProducedItEvenIfTheActiveModelChangesMeanwhile() = runBlocking {
        val gemma = ActiveModel("g", "Gemma 4 E2B", File("g.gguf"))
        val bonsai = ActiveModel("b", "Bonsai 1.7B", File("b.gguf"))
        var active: ActiveModel? = gemma
        val runtime = object : OnDeviceRuntime {
            override val isSupported = true
            override var loadedModelId: String? = null
            override suspend fun load(modelId: String, file: File) { loadedModelId = modelId }
            override suspend fun generate(request: AiRequest, onPiece: (String) -> Unit): String {
                active = bonsai
                return "from $loadedModelId"
            }
            override suspend fun unload() { loadedModelId = null }
            override fun systemInfo() = "fake"
        }
        val result = OnDeviceTextGenerator(runtime) { active }.generate(AiRequest("s", "u"))
        assertEquals("from g", result.text)
        assertEquals("Gemma 4 E2B", result.by)
    }
}
