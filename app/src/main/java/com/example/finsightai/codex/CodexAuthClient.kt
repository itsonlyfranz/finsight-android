package com.example.finsightai.codex

import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class CodexAuthException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { NETWORK, REJECTED, EXPIRED, DENIED, REFRESH_INVALID, NOT_SIGNED_IN, UNEXPECTED }

    /** Worth offering a "try again" button. */
    val retryable: Boolean get() = kind == Kind.NETWORK || kind == Kind.EXPIRED || kind == Kind.UNEXPECTED
}

data class DeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
    val expiresAtMillis: Long,
    val verificationUrl: String,
) {
    override fun toString(): String = "DeviceCode(expiresAt=$expiresAtMillis)"
}

/** What the server hands back once the user approved the device code. */
data class DeviceApproval(val authorizationCode: String, val codeVerifier: String) {
    override fun toString(): String = "DeviceApproval()"
}

sealed interface DevicePoll {
    data object Pending : DevicePoll
    data class Approved(val approval: DeviceApproval) : DevicePoll
}

/** Raw HTTP steps of the Codex CLI's device-code login and token refresh. Stateless. */
open class CodexAuthClient(
    private val endpoints: CodexEndpoints = CodexEndpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open suspend fun requestDeviceCode(): DeviceCode {
        val body = buildJsonObject { put("client_id", endpoints.clientId) }.toString()
        val res = call(endpoints.deviceUserCodeUrl, jsonHeaders(), body)
        if (res.status == 404) {
            throw CodexAuthException("Device-code sign-in is not available for this account or server.", CodexAuthException.Kind.REJECTED)
        }
        if (res.status !in 200..299) {
            throw CodexAuthException("Could not start sign-in (HTTP ${res.status}).", CodexAuthException.Kind.UNEXPECTED)
        }
        val obj = parseObject(res.body)
        val id = obj.string("device_auth_id")
        val code = obj.string("user_code") ?: obj.string("usercode")
        if (id == null || code == null) {
            throw CodexAuthException("Unexpected response while starting sign-in.", CodexAuthException.Kind.UNEXPECTED)
        }
        val now = clock()
        val interval = obj.string("interval")?.trim()?.toLongOrNull()?.coerceAtLeast(1) ?: 5L
        val expires = obj.string("expires_at")?.let(::parseInstant)
            ?: (now + DEFAULT_CODE_LIFETIME_MS)
        return DeviceCode(id, code, interval, expires, endpoints.verificationUrl)
    }

    /** One poll of the device-token endpoint. 403/404 mean "user has not approved yet". */
    open suspend fun pollDeviceCode(code: DeviceCode): DevicePoll {
        val body = buildJsonObject {
            put("device_auth_id", code.deviceAuthId)
            put("user_code", code.userCode)
        }.toString()
        val res = call(endpoints.deviceTokenUrl, jsonHeaders(), body)
        return when {
            res.status in 200..299 -> {
                val obj = parseObject(res.body)
                val auth = obj.string("authorization_code")
                val verifier = obj.string("code_verifier")
                if (auth == null || verifier == null) {
                    throw CodexAuthException("Unexpected response while waiting for approval.", CodexAuthException.Kind.UNEXPECTED)
                }
                DevicePoll.Approved(DeviceApproval(auth, verifier))
            }
            res.status == 403 || res.status == 404 -> DevicePoll.Pending
            res.status == 410 || res.errorCode().contains("expired") ->
                throw CodexAuthException("The sign-in code expired. Start again.", CodexAuthException.Kind.EXPIRED)
            res.errorCode().contains("denied") || res.errorCode().contains("cancel") ->
                throw CodexAuthException("Sign-in was denied.", CodexAuthException.Kind.DENIED)
            else -> throw CodexAuthException("Sign-in failed (HTTP ${res.status}).", CodexAuthException.Kind.REJECTED)
        }
    }

    open suspend fun exchangeCode(approval: DeviceApproval): CodexTokens {
        val form = form(
            "grant_type" to "authorization_code",
            "client_id" to endpoints.clientId,
            "code" to approval.authorizationCode,
            "redirect_uri" to endpoints.deviceRedirectUri,
            "code_verifier" to approval.codeVerifier,
        )
        val res = call(endpoints.tokenUrl, formHeaders(), form)
        if (res.status !in 200..299) {
            throw CodexAuthException("Could not complete sign-in (HTTP ${res.status}).", CodexAuthException.Kind.REJECTED)
        }
        return tokensFrom(res.body, previous = null)
    }

    /**
     * Exchanges [previous]'s refresh token. Refresh tokens rotate: callers must persist the result
     * before using it. Permanent rejections throw [CodexAuthException.Kind.REFRESH_INVALID].
     */
    open suspend fun refresh(previous: CodexTokens): CodexTokens {
        val form = form(
            "grant_type" to "refresh_token",
            "client_id" to endpoints.clientId,
            "refresh_token" to previous.refreshToken,
        )
        val res = call(endpoints.tokenUrl, formHeaders(), form)
        if (res.status in 200..299) return tokensFrom(res.body, previous)
        val code = res.errorCode()
        val permanent = res.status == 401 || code.contains("refresh_token") || code.contains("invalid_grant")
        if (permanent) {
            throw CodexAuthException("Your Codex session expired. Please sign in again.", CodexAuthException.Kind.REFRESH_INVALID)
        }
        throw CodexAuthException("Could not refresh the Codex session (HTTP ${res.status}).", CodexAuthException.Kind.UNEXPECTED)
    }

    // ---- helpers ----

    private fun tokensFrom(body: String, previous: CodexTokens?): CodexTokens {
        val obj = parseObject(body)
        val access = obj.string("access_token")
            ?: throw CodexAuthException("The server returned no access token.", CodexAuthException.Kind.UNEXPECTED)
        val refresh = obj.string("refresh_token") ?: previous?.refreshToken
            ?: throw CodexAuthException("The server returned no refresh token.", CodexAuthException.Kind.UNEXPECTED)
        val idToken = obj.string("id_token") ?: previous?.idToken
        val idClaims = JwtClaims.parse(idToken)
        val accessClaims = JwtClaims.parse(access)
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.longOrNull
        val expiresAt = accessClaims.expiresAtSeconds?.times(1000)
            ?: expiresIn?.let { clock() + it * 1000 }
        return CodexTokens(
            idToken = idToken,
            accessToken = access,
            refreshToken = refresh,
            accountId = idClaims.accountId ?: accessClaims.accountId ?: previous?.accountId,
            email = idClaims.email ?: accessClaims.email ?: previous?.email,
            planType = idClaims.planType ?: accessClaims.planType ?: previous?.planType,
            accessExpiresAtMillis = expiresAt,
        )
    }

    private suspend fun call(url: String, headers: Map<String, String>, body: String): HttpResult = try {
        CodexHttp.send(url, "POST", headers, body)
    } catch (e: IOException) {
        CodexLog.w("auth request failed: ${e.javaClass.simpleName}")
        throw CodexAuthException("Network problem: could not reach OpenAI.", CodexAuthException.Kind.NETWORK, e)
    }

    private fun jsonHeaders() = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to endpoints.userAgent,
    )

    private fun formHeaders() = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
        "User-Agent" to endpoints.userAgent,
    )

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw CodexAuthException("Unexpected response from OpenAI.", CodexAuthException.Kind.UNEXPECTED, e)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseInstant(text: String): Long? =
        try { OffsetDateTime.parse(text).toInstant().toEpochMilli() } catch (_: Exception) { null }

    /** Lower-cased error code from `{"error": "x"}`, `{"error": {"code": "x"}}`, `{"code": "x"}`; empty if none. */
    private fun HttpResult.errorCode(): String = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val err = obj["error"]
        val code = when {
            err is JsonObject -> err.string("code") ?: err.string("type") ?: err.string("message")
            err is JsonPrimitive -> err.contentOrNull
            else -> null
        } ?: obj.string("code") ?: obj.string("error_code")
        code.orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }

    private companion object {
        const val DEFAULT_CODE_LIFETIME_MS = 15 * 60 * 1000L
    }
}
