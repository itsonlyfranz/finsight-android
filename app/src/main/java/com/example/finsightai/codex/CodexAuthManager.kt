package com.example.finsightai.codex

import com.example.finsightai.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * What the Codex settings UI shows. [Error] means "not signed in, and here is why" (tokens are
 * already wiped) unless [CodexAuthManager.isSignedIn] says otherwise.
 */
sealed interface CodexAuthState {
    data object SignedOut : CodexAuthState
    data object RequestingCode : CodexAuthState
    data class AwaitingApproval(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
    ) : CodexAuthState
    data class SignedIn(val email: String?, val planType: String?, val accountId: String?) : CodexAuthState
    data class Error(val message: String, val retryable: Boolean) : CodexAuthState
}

/**
 * Owns the Codex login lifecycle: device-code sign-in, secure persistence (only through
 * [SecretStore]), proactive/forced token refresh (single-flight) and sign-out.
 */
class CodexAuthManager(
    private val client: CodexAuthClient,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val refreshSkewMillis: Long = 2 * 60 * 1000L,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<CodexAuthState>(CodexAuthState.SignedOut)
    val state: StateFlow<CodexAuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = _state.value is CodexAuthState.SignedIn

    private val tokenLock = Mutex()
    private var cached: CodexTokens? = null
    private var signInJob: Job? = null

    /** Loads persisted tokens (call once on app start). Safe to call repeatedly. */
    fun restore() {
        val tokens = loadTokens()
        if (tokens != null && (_state.value is CodexAuthState.SignedOut || _state.value is CodexAuthState.Error)) {
            cached = tokens
            _state.value = tokens.toSignedIn()
        }
    }

    /** Fire-and-forget variant for UI buttons; use [cancelSignIn] to abort. */
    fun startSignIn() {
        synchronized(this) {
            if (signInJob?.isActive == true) return
            signInJob = scope.launch { signInWithDeviceCode() }
        }
    }

    fun cancelSignIn() {
        val job = synchronized(this) { signInJob.also { signInJob = null } }
        job?.cancel()
        if (_state.value.isInProgress()) _state.value = CodexAuthState.SignedOut
    }

    /**
     * Runs the whole device-code flow: request code -> show it -> poll until approved/expired ->
     * exchange -> persist. Never throws (except cancellation): failures land in [state].
     */
    suspend fun signInWithDeviceCode() {
        if (_state.value.isInProgress()) return
        _state.value = CodexAuthState.RequestingCode
        try {
            val code = client.requestDeviceCode()
            _state.value = CodexAuthState.AwaitingApproval(code.userCode, code.verificationUrl, code.expiresAtMillis)
            val approval = awaitApproval(code)
            val tokens = client.exchangeCode(approval)
            tokenLock.withLock {
                persist(tokens) // durable before we report success
                cached = tokens
            }
            _state.value = tokens.toSignedIn()
        } catch (e: CancellationException) {
            if (_state.value.isInProgress()) _state.value = CodexAuthState.SignedOut
            throw e
        } catch (e: CodexAuthException) {
            CodexLog.w("sign-in failed: ${e.kind}")
            _state.value = CodexAuthState.Error(e.message ?: "Sign-in failed.", e.retryable)
        } catch (e: Exception) {
            CodexLog.w("sign-in failed unexpectedly: ${e.javaClass.simpleName}")
            _state.value = CodexAuthState.Error("Sign-in failed. Please try again.", true)
        }
    }

    private suspend fun awaitApproval(code: DeviceCode): DeviceApproval {
        val interval = code.intervalSeconds.coerceAtLeast(1) * 1000
        while (true) {
            when (val poll = client.pollDeviceCode(code)) {
                is DevicePoll.Approved -> return poll.approval
                DevicePoll.Pending -> Unit
            }
            if (clock() + interval > code.expiresAtMillis) {
                throw CodexAuthException("The sign-in code expired. Start again.", CodexAuthException.Kind.EXPIRED)
            }
            pause(interval)
        }
    }

    /** Wipes local tokens. (OpenAI offers no revoke call the CLI uses; sign-out is local.) */
    suspend fun signOut() {
        cancelSignIn()
        tokenLock.withLock {
            withContext(NonCancellable) {
                secrets.remove(TOKENS_KEY)
                cached = null
            }
        }
        _state.value = CodexAuthState.SignedOut
    }

    /**
     * Returns usable credentials, refreshing first when the access token expires within the skew.
     * Pass [rejectedAccessToken] after a 401 to force exactly one refresh (concurrent callers that
     * saw the same rejected token share it). Throws [CodexAuthException].
     */
    suspend fun validAccessToken(rejectedAccessToken: String? = null): AccessCreds = tokenLock.withLock {
        val current = cached ?: loadTokens()?.also { cached = it }
            ?: throw CodexAuthException("Not signed in to Codex.", CodexAuthException.Kind.NOT_SIGNED_IN)
        val rejected = rejectedAccessToken != null && rejectedAccessToken == current.accessToken
        val expiring = current.accessExpiresAtMillis?.let { clock() >= it - refreshSkewMillis } ?: false
        if (!rejected && !expiring) return@withLock current.toCreds()
        try {
            val fresh = client.refresh(current)
            persist(fresh) // refresh tokens rotate: store the new one BEFORE handing out the access token
            cached = fresh
            _state.value = fresh.toSignedIn()
            fresh.toCreds()
        } catch (e: CodexAuthException) {
            if (e.kind == CodexAuthException.Kind.REFRESH_INVALID) {
                withContext(NonCancellable) { secrets.remove(TOKENS_KEY) }
                cached = null
                _state.value = CodexAuthState.Error("Your Codex session expired. Please sign in again.", true)
            }
            throw e
        }
    }

    private fun persist(tokens: CodexTokens) {
        try {
            secrets.put(TOKENS_KEY, json.encodeToString(CodexTokens.serializer(), tokens))
        } catch (e: Exception) {
            CodexLog.w("could not persist tokens: ${e.javaClass.simpleName}")
            throw CodexAuthException("Could not save the Codex sign-in securely on this device.", CodexAuthException.Kind.UNEXPECTED, e)
        }
    }

    private fun loadTokens(): CodexTokens? {
        val raw = secrets.get(TOKENS_KEY) ?: return null
        return try {
            json.decodeFromString(CodexTokens.serializer(), raw)
        } catch (_: Exception) {
            secrets.remove(TOKENS_KEY)
            null
        }
    }

    private fun CodexTokens.toSignedIn() = CodexAuthState.SignedIn(email, planType, accountId)
    private fun CodexTokens.toCreds() = AccessCreds(accessToken, accountId)
    private fun CodexAuthState.isInProgress() =
        this is CodexAuthState.RequestingCode || this is CodexAuthState.AwaitingApproval

    companion object {
        const val TOKENS_KEY = "codex_tokens"
    }
}
