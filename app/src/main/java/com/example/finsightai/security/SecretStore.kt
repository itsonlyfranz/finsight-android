package com.example.finsightai.security

/**
 * Small key/value store for secrets (Hugging Face token, Codex OAuth tokens).
 * Production: [KeystoreSecretStore] (Android Keystore AES-GCM). Tests: in-memory fakes.
 * Implementations must never log values.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** In-memory store, for tests and previews. */
class InMemorySecretStore : SecretStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
