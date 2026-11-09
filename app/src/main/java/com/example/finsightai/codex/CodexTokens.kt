package com.example.finsightai.codex

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What is persisted (as JSON, via [com.example.finsightai.security.SecretStore]) after a sign-in. */
@Serializable
data class CodexTokens(
    val idToken: String? = null,
    val accessToken: String,
    val refreshToken: String,
    val accountId: String? = null,
    val email: String? = null,
    val planType: String? = null,
    /** Epoch millis when [accessToken] expires; null if the server gave no hint. */
    val accessExpiresAtMillis: Long? = null,
) {
    /** Never prints credentials. */
    override fun toString(): String = "CodexTokens(email=$email, plan=$planType, expiresAt=$accessExpiresAtMillis)"
}

/** Credentials needed to call the Codex backend. */
data class AccessCreds(val accessToken: String, val accountId: String?) {
    override fun toString(): String = "AccessCreds(accountId=$accountId)"
}

/** Claims we read from the (unverified) ChatGPT JWTs. Parsing is tolerant: everything is optional. */
data class JwtClaims(
    val email: String? = null,
    val accountId: String? = null,
    val planType: String? = null,
    val expiresAtSeconds: Long? = null,
) {
    companion object {
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Returns empty claims for anything that is not a decodable JWT. */
        fun parse(jwt: String?): JwtClaims {
            if (jwt.isNullOrBlank()) return JwtClaims()
            val parts = jwt.split('.')
            if (parts.size < 2) return JwtClaims()
            val payload = try {
                String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return JwtClaims()
            }
            val root = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (_: Exception) {
                return JwtClaims()
            }
            val auth = root[AUTH_CLAIM] as? JsonObject
            val profile = root[PROFILE_CLAIM] as? JsonObject
            return JwtClaims(
                email = root.str("email") ?: profile?.str("email"),
                accountId = auth?.str("chatgpt_account_id"),
                planType = auth?.str("chatgpt_plan_type"),
                expiresAtSeconds = (root["exp"] as? JsonPrimitive)?.longOrNull,
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
