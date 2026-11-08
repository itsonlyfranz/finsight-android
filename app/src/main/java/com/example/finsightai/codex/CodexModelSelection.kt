package com.example.finsightai.codex

import com.example.finsightai.ai.backend.TextGenerator
import com.example.finsightai.llm.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lets the user choose which of their ChatGPT account's Codex models writes the insights. */
interface CodexModelSelection {
    /** The model tried first for the signed-in account; null until the account's list was resolved. */
    val selectedModel: StateFlow<String?>

    /** The account's models in server order; empty when signed out or the list cannot be loaded. */
    suspend fun availableModels(): List<String>

    /** Makes [model] (one of [availableModels]) the first choice for the next request. */
    fun select(model: String)

    /** Drops the saved choice, e.g. on sign-out. */
    fun forget()
}

/** The Codex backend as the app wires it: a text generator whose model the user can pick. */
interface CodexBackend : TextGenerator, CodexModelSelection

/**
 * The saved model choice, remembered per ChatGPT account in a plain [KeyValueStore] (not a secret).
 * A choice saved for another account, or one the account no longer offers, is replaced by the
 * account's first model.
 */
class CodexModelPreference(private val settings: KeyValueStore) {
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Resolves the choice for [accountId] against the account's [models]; empty means the list is unavailable. */
    fun resolve(accountId: String?, models: List<String>): String? {
        val account = accountId.orEmpty()
        val savedAccount = settings.getString(KEY_ACCOUNT)
        val savedModel = settings.getString(KEY_MODEL)
        val saved = savedModel?.takeIf { savedAccount == account }
        val choice = when {
            models.isEmpty() -> saved
            saved != null && saved in models -> saved
            else -> models.first()
        }
        if (choice != savedModel || (choice != null && savedAccount != account)) persist(account, choice)
        _selected.value = choice
        return choice
    }

    fun select(accountId: String?, model: String) {
        persist(accountId.orEmpty(), model)
        _selected.value = model
    }

    fun forget() {
        persist(null, null)
        _selected.value = null
    }

    private fun persist(account: String?, model: String?) {
        settings.putString(KEY_ACCOUNT, if (model == null) null else account)
        settings.putString(KEY_MODEL, model)
    }

    private companion object {
        const val KEY_MODEL = "codex_model"
        const val KEY_ACCOUNT = "codex_model_account"
    }
}
