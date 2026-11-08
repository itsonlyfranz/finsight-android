package com.example.finsightai.e2e

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.AiServices
import com.example.finsightai.ai.GeneratedText
import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.ai.backend.BackendPreferences
import com.example.finsightai.ai.backend.TextGenerator
import com.example.finsightai.codex.CodexAuthClient
import com.example.finsightai.codex.CodexAuthManager
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.codex.CodexTokens
import com.example.finsightai.codex.DeviceApproval
import com.example.finsightai.codex.DeviceCode
import com.example.finsightai.codex.DevicePoll
import com.example.finsightai.llm.DownloadError
import com.example.finsightai.llm.DownloadState
import com.example.finsightai.llm.HttpModelDownloader
import com.example.finsightai.llm.InMemoryKeyValueStore
import com.example.finsightai.llm.ModelDownloader
import com.example.finsightai.llm.ModelSpec
import com.example.finsightai.llm.ModelStore
import com.example.finsightai.llm.OnDeviceRuntime
import com.example.finsightai.llm.hf.HfApi
import com.example.finsightai.llm.hf.HfSearchService
import com.example.finsightai.llm.hf.HfTokenStore
import com.example.finsightai.llm.hf.HubFile
import com.example.finsightai.llm.hf.HubRepo
import com.example.finsightai.security.InMemorySecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Fake network + runtime: no real HTTP, no real model. Everything else (store, router, UI) is real. */
class FakeDownloader(private val store: ModelStore) : ModelDownloader {
    @Volatile var gate = CompletableDeferred<Unit>()
    @Volatile var failNext: DownloadError? = null
    val started = mutableListOf<String>()

    fun release() = gate.complete(Unit)

    override fun download(spec: ModelSpec): Flow<DownloadState> = flow {
        started += spec.id
        emit(DownloadState.Queued)
        val part = store.partFile(spec)
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(2048) { 7 })
        emit(DownloadState.Progress(1024, 2048))
        try {
            gate.await()
        } catch (e: Throwable) {
            part.delete() // the real downloader deletes its partial on cancel too
            throw e
        }
        val error = failNext
        if (error != null) {
            failNext = null
            part.delete()
            gate = CompletableDeferred()
            emit(DownloadState.Failed(error))
            return@flow
        }
        emit(DownloadState.Progress(2048, 2048))
        emit(DownloadState.Verifying)
        val model = store.commit(spec, part)
        gate = CompletableDeferred()
        emit(DownloadState.Completed(model))
    }
}

class FakeHfApi(var repos: List<HubRepo>, var files: Map<String, List<HubFile>>) : HfApi {
    var queries = mutableListOf<String>()
    override suspend fun searchRepos(query: String, limit: Int): List<HubRepo> { queries += query; return repos }
    override suspend fun listFiles(repoId: String): List<HubFile> = files[repoId].orEmpty()
}

class FakeRuntime : OnDeviceRuntime {
    override val isSupported = true
    @Volatile override var loadedModelId: String? = null
    var unloads = 0
    override suspend fun load(modelId: String, file: File) { loadedModelId = modelId }
    override suspend fun generate(request: AiRequest, onPiece: (String) -> Unit): String {
        val text = "INSIGHT: Rewritten on-device by the fake model.\nACTION: Follow the fake plan."
        onPiece(text)
        return text
    }
    override suspend fun unload() { loadedModelId = null; unloads++ }
    override fun systemInfo() = "fake"
}

class FakeCodexGenerator(private val auth: CodexAuthManager) : TextGenerator {
    override val id = "codex"
    override suspend fun describe(): String? = if (auth.isSignedIn) "Codex (fake)" else null
    override suspend fun isReady() = auth.isSignedIn
    override suspend fun generate(request: AiRequest) =
        GeneratedText("INSIGHT: Rewritten by Codex.\nACTION: Follow the Codex plan.", "Codex (fake)")
}

class FakeCodexClient : CodexAuthClient() {
    @Volatile var approved = false
    @Volatile var failRequest: Boolean = false
    override suspend fun requestDeviceCode(): DeviceCode {
        if (failRequest) throw com.example.finsightai.codex.CodexAuthException(
            "Could not start sign-in (HTTP 503).", com.example.finsightai.codex.CodexAuthException.Kind.UNEXPECTED
        )
        return DeviceCode("dev-1", "ABCD-1234", 1, System.currentTimeMillis() + 15 * 60_000, "https://auth.openai.com/codex/device")
    }
    override suspend fun pollDeviceCode(code: DeviceCode): DevicePoll =
        if (approved) DevicePoll.Approved(DeviceApproval("auth-code", "verifier")) else DevicePoll.Pending
    override suspend fun exchangeCode(approval: DeviceApproval) = CodexTokens(
        accessToken = "access", refreshToken = "refresh", email = "mark@example.com", planType = "plus", accountId = "acct",
        accessExpiresAtMillis = System.currentTimeMillis() + 3_600_000
    )
}

/**
 * The real object graph with fakes at the edges (network, native runtime). With [downloadBaseUrl] the
 * real [HttpModelDownloader] is used against that server instead of [FakeDownloader].
 */
class TestHarness(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    downloadBaseUrl: String? = null,
) {
    val root: File = File(context.cacheDir, "e2e-models-${System.nanoTime()}").also { it.mkdirs() }
    val secrets = InMemorySecretStore()
    val store = ModelStore(root, InMemoryKeyValueStore())
    val downloader = FakeDownloader(store)
    val hfApi = FakeHfApi(emptyList(), emptyMap())
    val hfTokens = HfTokenStore(secrets)
    val codexClient = FakeCodexClient()
    val codexAuth = CodexAuthManager(codexClient, secrets, pause = { delay(50) })
    val runtime = FakeRuntime()
    val prefs = BackendPreferences(null)
    val services = AiServices(
        secrets = secrets,
        modelStore = store,
        downloader = downloadBaseUrl?.let {
            HttpModelDownloader(store, baseUrl = it, freeSpace = { Long.MAX_VALUE }, maxRetries = 0, readTimeoutMs = 5_000)
        } ?: downloader,
        hfSearch = HfSearchService(hfApi, hasToken = { hfTokens.hasToken() }),
        hfTokens = hfTokens,
        codexAuth = codexAuth,
        backendPrefs = prefs,
        runtime = runtime,
        codexGenerator = FakeCodexGenerator(codexAuth),
    )

    fun close() { root.deleteRecursively() }
}

fun CodexAuthState.isSignedInState() = this is CodexAuthState.SignedIn
