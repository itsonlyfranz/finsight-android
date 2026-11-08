package com.example.finsightai.ai.backend

import com.example.finsightai.ai.GeneratedText
import com.example.finsightai.ai.GenerativeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the AI features are effectively using right now, for the settings / insights UI. */
sealed interface EffectiveBackend {
    /** A generative backend will write the text. [label] e.g. "Gemma 4 E2B" or "Codex (gpt-5) · me@x.com". */
    data class Generative(val choice: BackendChoice, val label: String) : EffectiveBackend

    /** Deterministic on-device engines are used; [reason] explains why no model is active. */
    data class Deterministic(val choice: BackendChoice, val reason: String) : EffectiveBackend
}

/**
 * Routes AI feature requests to the backend the user selected and falls back to the deterministic
 * engines (by returning null) whenever that backend is not ready or fails. It never throws for
 * backend problems; cancellation is propagated.
 */
class AiBackendRouter(
    choice: StateFlow<BackendChoice>,
    private val generators: Map<BackendChoice, TextGenerator>,
) : GenerativeSource {

    private val choiceFlow = choice
    val choice: StateFlow<BackendChoice> get() = choiceFlow

    private val _lastError = MutableStateFlow<String?>(null)

    /** Message of the last generation failure since the backend became healthy again (null = healthy). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override suspend fun generate(request: AiRequest): GeneratedText? {
        val generator = generators[choiceFlow.value] ?: return null
        if (!generator.isReady()) return null
        val label = generator.describe() ?: generator.id
        return try {
            val text = generator.generate(request)
            _lastError.value = null
            GeneratedText(text, label)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: AiBackendException) {
            _lastError.value = e.message ?: "The ${generator.id} backend failed"
            null
        } catch (e: Exception) {
            _lastError.value = e.message ?: "The ${generator.id} backend failed unexpectedly"
            null
        }
    }

    suspend fun effective(): EffectiveBackend {
        val selected = choiceFlow.value
        val generator = generators[selected]
            ?: return EffectiveBackend.Deterministic(selected, "No backend is configured")
        if (!generator.isReady()) {
            val reason = when (selected) {
                BackendChoice.ON_DEVICE -> "No on-device model is installed and selected"
                BackendChoice.CODEX -> "Not signed in to Codex"
            }
            return EffectiveBackend.Deterministic(selected, reason)
        }
        return EffectiveBackend.Generative(selected, generator.describe() ?: generator.id)
    }
}
