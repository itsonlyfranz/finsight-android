package com.example.finsightai.ai.backend

import com.example.finsightai.ai.GeneratedText

/** Which generative backend the user chose for AI features. The deterministic engines are always the fallback. */
enum class BackendChoice(val label: String) {
    ON_DEVICE("On-device model"),
    CODEX("Codex (ChatGPT account)"),
}

/** A single-turn prompt: instructions + the data to talk about. */
data class AiRequest(
    val system: String,
    val user: String,
    val maxTokens: Int = 220,
    val temperature: Float = 0.4f,
)

class AiBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A source of generated text. Implementations: on-device llama.cpp, Codex (ChatGPT backend), and
 * test fakes. `generate` must be cancellable (coroutine cancellation) and throw
 * [AiBackendException] on failure so callers can fall back to the deterministic engines.
 */
interface TextGenerator {
    /** Stable id, e.g. "on-device" or "codex". */
    val id: String

    /** Human label of what is generating, e.g. "Gemma 4 E2B" or "Codex". Null when not ready. */
    suspend fun describe(): String?

    /** True when [generate] can be expected to work right now (model installed / signed in). */
    suspend fun isReady(): Boolean

    /** The text plus a label naming what actually produced it (e.g. the model that answered). */
    suspend fun generate(request: AiRequest): GeneratedText
}
