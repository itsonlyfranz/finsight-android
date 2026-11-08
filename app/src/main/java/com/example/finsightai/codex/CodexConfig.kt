package com.example.finsightai.codex

import android.util.Log

/** Endpoints and client identity used for ChatGPT / Codex sign-in. All overridable for tests. */
data class CodexEndpoints(
    /** OAuth issuer. Device-code and token endpoints hang off this. */
    val issuer: String = "https://auth.openai.com",
    /** Base of the Codex backend; the Responses stream is POSTed to `{backendBaseUrl}/responses`. */
    val backendBaseUrl: String = "https://chatgpt.com/backend-api/codex",
    /** The public OAuth client id the open-source Codex CLI uses for ChatGPT sign-in. */
    val clientId: String = "app_EMoamEEZ73f0CkXaXp7hrann",
    /** Sent as the `originator` header, as the Codex CLI does. */
    val originator: String = "codex_cli_rs",
    val userAgent: String = "FinSight-Android/1.0 (codex-login)",
) {
    val deviceUserCodeUrl: String get() = "${issuer.trimEnd('/')}/api/accounts/deviceauth/usercode"
    val deviceTokenUrl: String get() = "${issuer.trimEnd('/')}/api/accounts/deviceauth/token"
    val tokenUrl: String get() = "${issuer.trimEnd('/')}/oauth/token"
    val deviceRedirectUri: String get() = "${issuer.trimEnd('/')}/deviceauth/callback"
    val verificationUrl: String get() = "${issuer.trimEnd('/')}/codex/device"
    val responsesUrl: String get() = "${backendBaseUrl.trimEnd('/')}/responses"
}

/** Logging that never carries token or code material. Safe on the JVM (unit tests). */
internal object CodexLog {
    const val TAG = "FinSightCodex"

    fun d(message: String) = emit { Log.d(TAG, message) }
    fun w(message: String) = emit { Log.w(TAG, message) }

    private inline fun emit(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // android.util.Log is not available in plain JVM tests.
        }
    }
}
