package com.example.finsightai.ui.aisetup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.finsightai.AiServices
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.ai.backend.EffectiveBackend
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.llm.DownloadError
import com.example.finsightai.llm.DownloadState
import com.example.finsightai.llm.InstalledModel
import com.example.finsightai.llm.ModelSpec
import com.example.finsightai.llm.hf.HfApiException
import com.example.finsightai.llm.hf.HfError
import com.example.finsightai.llm.hf.SearchResult
import com.example.finsightai.security.SecretStoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-model download progress shown on a model card. */
sealed interface DownloadUi {
    data object Queued : DownloadUi
    data class Progress(val bytesDone: Long, val totalBytes: Long) : DownloadUi {
        val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : DownloadUi
    data class Failed(val message: String, val needsToken: Boolean) : DownloadUi
}

/** A download the UI is tracking, with the spec so it can be shown outside the screen it started on. */
data class ActiveDownload(val spec: ModelSpec, val state: DownloadUi)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val query: String, val items: List<SearchResult>) : SearchUiState
    data class Error(val message: String, val needsToken: Boolean = false) : SearchUiState
}

/** State + actions for the "AI Setup" tab: backend choice, model picker, Hugging Face search, Codex sign-in. */
class AiSetupViewModel(private val services: AiServices) : ViewModel() {

    val backendChoice: StateFlow<BackendChoice> = services.backendPrefs.choice
    val installed: StateFlow<List<InstalledModel>> = services.modelStore.installed
    val activeModel: StateFlow<InstalledModel?> = services.modelStore.activeModel
    val codexState: StateFlow<CodexAuthState> = services.codexAuth.state

    private val _effective = MutableStateFlow<EffectiveBackend?>(null)
    val effective: StateFlow<EffectiveBackend?> = _effective.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ActiveDownload>> = _downloads.asStateFlow()

    private val _search = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _hasHfToken = MutableStateFlow(services.hfTokens.hasToken())
    val hasHfToken: StateFlow<Boolean> = _hasHfToken.asStateFlow()

    private val _hfTokenError = MutableStateFlow<String?>(null)
    val hfTokenError: StateFlow<String?> = _hfTokenError.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            services.signal.collect { _effective.value = services.router.effective() }
        }
    }

    // ---- backend ----
    fun selectBackend(choice: BackendChoice) = services.backendPrefs.select(choice)

    // ---- models ----
    /**
     * Starts downloading [spec]. A previous, cancelled job for the same spec is awaited first: it owns
     * the `.part` file until it has finished deleting it.
     */
    fun download(spec: ModelSpec) {
        val previous = jobs[spec.id]
        if (previous?.isActive == true) return
        show(spec, DownloadUi.Queued)
        jobs[spec.id] = viewModelScope.launch {
            withContext(NonCancellable) { previous?.join() }
            services.downloader.download(spec).collect { state ->
                when (state) {
                    DownloadState.Queued -> show(spec, DownloadUi.Queued)
                    is DownloadState.Progress -> show(spec, DownloadUi.Progress(state.bytesDone, state.totalBytes))
                    DownloadState.Verifying -> show(spec, DownloadUi.Verifying)
                    is DownloadState.Completed -> _downloads.update { it - spec.id }
                    is DownloadState.Failed -> show(spec, DownloadUi.Failed(describe(state.error), needsToken(state.error)))
                }
            }
        }
    }

    private fun show(spec: ModelSpec, state: DownloadUi) = _downloads.update { it + (spec.id to ActiveDownload(spec, state)) }

    /** Stops an in-flight download; the downloader deletes its partial file as it unwinds. */
    fun cancelDownload(spec: ModelSpec) {
        jobs[spec.id]?.cancel()
        _downloads.update { it - spec.id }
    }

    fun dismissFailure(spec: ModelSpec) {
        _downloads.update { it - spec.id }
    }

    fun setActive(modelId: String) {
        services.modelStore.setActive(modelId)
    }

    /** Deletes the model's files; if it was active the AI features fall back automatically. */
    fun delete(modelId: String) {
        services.modelStore.delete(modelId)
    }

    // ---- Hugging Face search ----
    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.length < 2) {
            _search.value = SearchUiState.Idle
            return
        }
        _search.value = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            _search.value = try {
                SearchUiState.Results(q, services.hfSearch.search(q))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: HfApiException) {
                SearchUiState.Error(describe(e.error), needsToken = e.error is HfError.Unauthorized)
            } catch (e: Exception) {
                SearchUiState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun saveHfToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        try {
            services.hfTokens.set(t)
        } catch (e: SecretStoreException) {
            _hfTokenError.value = "Could not save the token securely on this device."
            return
        }
        _hfTokenError.value = null
        _hasHfToken.value = true
    }

    fun clearHfToken() {
        services.hfTokens.clear()
        _hasHfToken.value = false
    }

    // ---- Codex ----
    fun startCodexSignIn() = services.codexAuth.startSignIn()
    fun cancelCodexSignIn() = services.codexAuth.cancelSignIn()
    fun signOutCodex() {
        viewModelScope.launch { services.codexAuth.signOut() }
    }

    private fun needsToken(error: DownloadError) = error is DownloadError.Unauthorized

    private fun describe(error: DownloadError): String = when (error) {
        is DownloadError.Network -> "Network problem: ${error.detail}. Tap Retry to download again."
        is DownloadError.Unauthorized ->
            if (error.hadToken) "Hugging Face rejected your token for this model." else "This model is gated: add a Hugging Face token below."
        DownloadError.NotFound -> "The file was not found on Hugging Face."
        is DownloadError.DiskFull -> "Not enough free storage."
        is DownloadError.SizeMismatch -> "The downloaded file was incomplete. Try again."
        is DownloadError.ChecksumMismatch -> "The downloaded file failed its integrity check."
        is DownloadError.Storage -> "Could not save the model on this device: ${error.detail}."
    }

    private fun describe(error: HfError): String = when (error) {
        HfError.Network -> "Could not reach Hugging Face. Check your connection."
        is HfError.Unauthorized ->
            if (error.hadToken) "Hugging Face rejected your token." else "Hugging Face asked for a token."
        HfError.NotFound -> "Nothing found."
        HfError.RateLimited -> "Hugging Face is rate limiting requests. Try again shortly."
        is HfError.Server -> "Hugging Face returned an error (${error.code})."
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AiSetupViewModel(AiServices.get(context)) as T
        }
    }
}
