package com.example.finsightai.ai.backend

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists which generative backend the user picked. Not a secret, so plain SharedPreferences. */
class BackendPreferences(private val prefs: SharedPreferences?) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<BackendChoice> = _choice.asStateFlow()

    fun select(choice: BackendChoice) {
        _choice.value = choice
        prefs?.edit()?.putString(KEY, choice.name)?.apply()
    }

    private fun read(): BackendChoice =
        prefs?.getString(KEY, null)
            ?.let { name -> BackendChoice.entries.firstOrNull { it.name == name } }
            ?: BackendChoice.ON_DEVICE

    private companion object {
        const val FILE = "finsight_ai_backend"
        const val KEY = "backend_choice"
    }
}
