package com.example.finsightai.codex

import com.example.finsightai.ai.GeneratedText
import com.example.finsightai.ai.backend.AiBackendException
import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.ai.backend.TextGenerator
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [TextGenerator] backed by the ChatGPT Codex backend (`/responses`, SSE) using the account the
 * user signed in with via [CodexAuthManager]. Stateless per request (`store:false`, no tools).
 *
 * The current Codex CLI sends the system prompt as a `developer` message inside `input`; older
 * backends took a top-level `instructions` field. We try the CLI's shape first and retry once with
 * the other if the server complains about it.
 *
 * Models are tried in this order: the last one that worked, the models the backend lists for the
 * signed-in account (`GET /models`, cached per account), then [models] as a fallback.
 */
class CodexTextGenerator(
    private val auth: CodexAuthManager,
    private val endpoints: CodexEndpoints = CodexEndpoints(),
    /** Tried after the account's listed models when the backend rejects a model; the first that works is remembered. */
    private val models: List<String> = DEFAULT_MODELS,
    private val requestTimeoutMillis: Long = 90_000,
) : TextGenerator {
    override val id: String = "codex"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sessionId = UUID.randomUUID().toString()
    @Volatile private var workingModel: String? = null
    @Volatile private var accountModels: AccountModels? = null

    private class AccountModels(val accountId: String?, val slugs: List<String>)

    @Serializable private data class ModelList(val models: List<ModelEntry> = emptyList())
    @Serializable private data class ModelEntry(val slug: String? = null)

    override suspend fun isReady(): Boolean = auth.isSignedIn

    override suspend fun describe(): String? {
        val signedIn = auth.state.value as? CodexAuthState.SignedIn ?: return null
        return label(signedIn, workingModel)
    }

    private fun label(signedIn: CodexAuthState.SignedIn?, model: String?): String {
        val who = signedIn?.let { it.email ?: it.planType } ?: "ChatGPT"
        return model?.let { "Codex ($it) · $who" } ?: "Codex · $who"
    }

    override suspend fun generate(request: AiRequest): GeneratedText {
        if (!auth.isSignedIn) throw AiBackendException("Sign in to Codex in AI Setup first.")
        var lastError: AiBackendException? = null
        val candidates = (listOfNotNull(workingModel) + listedModels() + models).distinct()
        for (model in candidates) {
            for (useInstructionsField in listOf(false, true)) {
                try {
                    val text = attempt(request, model, useInstructionsField)
                    workingModel = model
                    return GeneratedText(text, label(auth.state.value as? CodexAuthState.SignedIn, model))
                } catch (e: ShapeRejected) {
                    lastError = e.asBackend()
                    if (e.about == Rejected.INSTRUCTIONS && !useInstructionsField) continue
                    if (e.about == Rejected.MODEL) break
                    throw lastError
                }
            }
        }
        throw lastError ?: AiBackendException("Codex did not return an answer.")
    }

    /** The account's model slugs in server order; empty (and not cached) when the list is unavailable. */
    private suspend fun listedModels(): List<String> {
        var creds = try { auth.validAccessToken() } catch (e: CodexAuthException) { throw e.asBackend() }
        val cached = accountModels
        if (cached != null) {
            if (cached.accountId == creds.accountId) return cached.slugs
            accountModels = null
            workingModel = null
        }
        val slugs = try {
            var result = fetchModelList(creds)
            if (result.status == 401) {
                creds = try { auth.validAccessToken(rejectedAccessToken = creds.accessToken) } catch (e: CodexAuthException) { throw e.asBackend() }
                result = fetchModelList(creds)
            }
            if (result.status !in 200..299) {
                CodexLog.w("codex model list unavailable (HTTP ${result.status})")
                return emptyList()
            }
            json.decodeFromString(ModelList.serializer(), result.body).models.mapNotNull { it.slug?.takeIf(String::isNotBlank) }
        } catch (e: TimeoutCancellationException) {
            CodexLog.w("codex model list timed out")
            return emptyList()
        } catch (e: IOException) {
            CodexLog.w("codex model list failed: ${e.javaClass.simpleName}")
            return emptyList()
        } catch (e: IllegalArgumentException) {
            CodexLog.w("codex model list unreadable")
            return emptyList()
        }
        accountModels = AccountModels(creds.accountId, slugs)
        return slugs
    }

    private suspend fun fetchModelList(creds: AccessCreds): HttpResult {
        val headers = buildMap {
            put("Authorization", "Bearer ${creds.accessToken}")
            creds.accountId?.let { put("chatgpt-account-id", it) }
            put("originator", endpoints.originator)
            put("User-Agent", endpoints.userAgent)
            put("Accept", "application/json")
        }
        return withTimeout(requestTimeoutMillis) { CodexHttp.send(endpoints.modelsUrl, "GET", headers, null) }
    }

    private suspend fun attempt(request: AiRequest, model: String, useInstructionsField: Boolean): String {
        var creds = try { auth.validAccessToken() } catch (e: CodexAuthException) { throw e.asBackend() }
        var refreshedAfter401 = false
        while (true) {
            val result = try {
                withTimeout(requestTimeoutMillis) { post(creds, buildBody(request, model, useInstructionsField)) }
            } catch (e: TimeoutCancellationException) {
                throw AiBackendException("Codex took too long to answer. Try again.")
            }
            when (result) {
                is Posted.Text -> return result.text
                is Posted.Failed -> when {
                    result.status == 401 && !refreshedAfter401 -> {
                        refreshedAfter401 = true
                        creds = try { auth.validAccessToken(rejectedAccessToken = creds.accessToken) } catch (e: CodexAuthException) { throw e.asBackend() }
                    }
                    else -> throw mapFailure(result)
                }
            }
        }
    }

    private sealed interface Posted {
        data class Text(val text: String) : Posted
        data class Failed(val status: Int, val detail: String) : Posted
    }

    private suspend fun post(creds: AccessCreds, body: String): Posted {
        val headers = buildMap {
            put("Authorization", "Bearer ${creds.accessToken}")
            creds.accountId?.let { put("chatgpt-account-id", it) }
            put("originator", endpoints.originator)
            put("User-Agent", endpoints.userAgent)
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            put("session-id", sessionId)
            put("x-client-request-id", UUID.randomUUID().toString())
        }
        val conn = CodexHttp.open(endpoints.responsesUrl, "POST", headers)
        return try {
            CodexHttp.cancellable(conn) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                if (status !in 200..299) {
                    val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    return@cancellable Posted.Failed(status, err)
                }
                val sse = conn.inputStream.bufferedReader(Charsets.UTF_8).use { ResponsesSse.read(it) }
                when {
                    sse.error != null -> Posted.Failed(200, sse.error)
                    sse.text.isBlank() -> Posted.Failed(200, "empty")
                    else -> Posted.Text(sse.text.trim())
                }
            }
        } catch (e: SocketTimeoutException) {
            throw AiBackendException("Codex took too long to answer. Try again.", e)
        } catch (e: IOException) {
            CodexLog.w("codex request failed: ${e.javaClass.simpleName}")
            throw AiBackendException("Network problem: could not reach Codex.", e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun buildBody(request: AiRequest, model: String, useInstructionsField: Boolean): String =
        buildJsonObject {
            put("model", model)
            put("stream", true)
            put("store", false)
            if (useInstructionsField) put("instructions", request.system)
            putJsonArray("input") {
                if (!useInstructionsField && request.system.isNotBlank()) add(message("developer", request.system))
                add(message("user", request.user))
            }
            put("tool_choice", "auto")
            put("parallel_tool_calls", false)
            putJsonObject("reasoning") { put("effort", "low") }
            put("include", buildJsonArray { })
        }.toString()

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", role)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", text)
            })
        })
    }

    private enum class Rejected { INSTRUCTIONS, MODEL }
    private class ShapeRejected(val about: Rejected, val reason: String) : Exception(reason) {
        fun asBackend() = AiBackendException(reason)
    }

    private fun mapFailure(f: Posted.Failed): Exception {
        val detail = if (f.status == 200) f.detail else errorDetail(f.detail)
        val lower = detail.lowercase()
        return when {
            f.status == 401 -> AiBackendException("Codex rejected the saved sign-in. Sign in again in AI Setup.")
            f.status == 403 -> AiBackendException("Codex access was refused (HTTP 403). Your ChatGPT plan or region may not include Codex.")
            f.status == 429 -> AiBackendException("Codex usage limit reached. Try again later.")
            f.status in 500..599 -> AiBackendException("Codex is having problems (HTTP ${f.status}). Try again later.")
            f.status == 400 && lower.contains("instructions") -> ShapeRejected(Rejected.INSTRUCTIONS, "Codex rejected the request: $detail")
            f.status == 400 && lower.contains("model") -> ShapeRejected(Rejected.MODEL, "Codex does not offer this model for your account: $detail")
            f.status == 200 && detail == "empty" -> AiBackendException("Codex returned an empty answer.")
            f.status == 200 -> AiBackendException("Codex failed: $detail")
            else -> AiBackendException("Codex request failed (HTTP ${f.status})${if (detail.isNotBlank()) ": $detail" else ""}")
        }
    }

    /** Pulls a short human message out of `{"detail": ...}` / `{"error": {"message": ...}}` bodies. */
    private fun errorDetail(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val detail = obj["detail"]
            val err = obj["error"]
            when {
                detail is JsonPrimitive -> detail.contentOrNull
                detail is JsonArray -> detail.toString()
                err is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                err is JsonPrimitive -> err.contentOrNull
                else -> null
            } ?: ""
        } catch (_: Exception) {
            ""
        }.take(200)
    }

    private fun CodexAuthException.asBackend() = AiBackendException(message ?: "Codex sign-in problem.", this)

    companion object {
        /** Catalogue slugs from the Codex CLI's bundled model list, most conservative wire shape first. */
        val DEFAULT_MODELS = listOf("gpt-5.5", "gpt-6.1-terra")
    }
}
