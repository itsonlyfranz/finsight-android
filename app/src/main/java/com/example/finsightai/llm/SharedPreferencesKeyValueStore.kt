package com.example.finsightai.llm

import android.content.SharedPreferences

/** [KeyValueStore] backed by SharedPreferences (use `context.getSharedPreferences("finsight_ai", MODE_PRIVATE)`). */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
