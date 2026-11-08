package com.example.finsightai.llm

/** Minimal string key/value persistence so core classes unit-test without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
