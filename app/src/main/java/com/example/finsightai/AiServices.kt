package com.example.finsightai

import android.content.Context
import com.example.finsightai.ai.backend.AiBackendRouter
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.ai.backend.BackendPreferences
import com.example.finsightai.codex.CodexAuthClient
import com.example.finsightai.codex.CodexAuthManager
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.codex.CodexBackend
import com.example.finsightai.codex.CodexModelPreference
import com.example.finsightai.codex.CodexTextGenerator
import com.example.finsightai.codex.modelSwitches
import com.example.finsightai.llm.ActiveModel
import com.example.finsightai.llm.HttpModelDownloader
import com.example.finsightai.llm.LlamaCppRuntime
import com.example.finsightai.llm.ModelDownloader
import com.example.finsightai.llm.ModelStore
import com.example.finsightai.llm.OnDeviceRuntime
import com.example.finsightai.llm.OnDeviceTextGenerator
import com.example.finsightai.llm.SharedPreferencesKeyValueStore
import com.example.finsightai.llm.hf.HfSearchService
import com.example.finsightai.llm.hf.HfTokenStore
import com.example.finsightai.llm.hf.HttpHfApi
import com.example.finsightai.security.KeystoreSecretStore
import com.example.finsightai.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything the AI layer needs, built from interfaces so that tests can assemble the same object
 * graph from fakes (see [create]). Production code gets the process-wide instance via [get].
 */
class AiServices(
    val secrets: SecretStore,
    val modelStore: ModelStore,
    val downloader: ModelDownloader,
    val hfSearch: HfSearchService,
    val hfTokens: HfTokenStore,
    val codexAuth: CodexAuthManager,
    val backendPrefs: BackendPreferences,
    val runtime: OnDeviceRuntime,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Codex text generation plus the account model picker shown in AI Setup. */
    val codex: CodexBackend = CodexTextGenerator(codexAuth),
) {
    val onDeviceGenerator = OnDeviceTextGenerator(runtime) {
        modelStore.activeModel.value?.let { ActiveModel(it.id, it.spec.displayName, it.file) }
    }

    val router = AiBackendRouter(
        backendPrefs.choice,
        mapOf(BackendChoice.ON_DEVICE to onDeviceGenerator, BackendChoice.CODEX to codex),
    )

    /** Emits whenever the effective AI backend may have changed (selection, active model, Codex sign-in or model). */
    val signal: Flow<Any?> = combine(
        backendPrefs.choice,
        modelStore.activeModel.map { it?.id },
        codexAuth.state.map { it is CodexAuthState.SignedIn },
        codex.selectedModel.modelSwitches(),
    ) { choice, model, signedIn, codexSwitches ->
        listOf(choice, model, signedIn, codexSwitches.takeIf { choice == BackendChoice.CODEX })
    }.distinctUntilChanged()

    init {
        // Free the model's RAM as soon as it is deactivated or deleted.
        scope.launch {
            modelStore.activeModel.map { it?.id }.distinctUntilChanged().collect { id ->
                val loaded = runtime.loadedModelId
                if (loaded != null && loaded != id) runtime.unload()
            }
        }
        codexAuth.restore()
    }

    companion object {
        @Volatile private var instance: AiServices? = null

        fun get(context: Context): AiServices = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        /** Test hook: replace the process-wide instance (null restores lazy production wiring). */
        fun overrideForTest(services: AiServices?) {
            instance = services
        }

        private fun build(context: Context): AiServices {
            val secrets = KeystoreSecretStore(context)
            val hfTokens = HfTokenStore(secrets)
            val prefs = context.getSharedPreferences("finsight_models", Context.MODE_PRIVATE)
            val store = ModelStore(File(context.filesDir, "models"), SharedPreferencesKeyValueStore(prefs))
            val codexAuth = CodexAuthManager(CodexAuthClient(), secrets)
            val codexPrefs = SharedPreferencesKeyValueStore(context.getSharedPreferences("finsight_codex", Context.MODE_PRIVATE))
            return AiServices(
                secrets = secrets,
                modelStore = store,
                downloader = HttpModelDownloader(store, tokenProvider = { hfTokens.get() }),
                hfSearch = HfSearchService(
                    HttpHfApi(tokenProvider = { hfTokens.get() }),
                    hasToken = { hfTokens.hasToken() },
                ),
                hfTokens = hfTokens,
                codexAuth = codexAuth,
                backendPrefs = BackendPreferences(context),
                runtime = LlamaCppRuntime(context),
                codex = CodexTextGenerator(codexAuth, preference = CodexModelPreference(codexPrefs)),
            )
        }
    }
}
