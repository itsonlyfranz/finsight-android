package com.example.finsightai.e2e

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finsightai.AiServices
import com.example.finsightai.MainActivity
import com.example.finsightai.ai.backend.BackendPreferences
import com.example.finsightai.codex.CodexAuthManager
import com.example.finsightai.codex.CodexModelPreference
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.llm.HttpModelDownloader
import com.example.finsightai.llm.ModelStore
import com.example.finsightai.llm.SharedPreferencesKeyValueStore
import com.example.finsightai.llm.hf.HfSearchService
import com.example.finsightai.llm.hf.HfTokenStore
import com.example.finsightai.llm.hf.HttpHfApi
import com.example.finsightai.model.Transaction
import com.example.finsightai.testdata.SampleData
import com.example.finsightai.security.KeystoreSecretStore
import kotlinx.coroutines.delay
import java.io.File

/**
 * The real [MainActivity] over a real SQLite database and real on-disk preferences, with only the
 * edges faked: the Hugging Face / download server ([hub]), Codex sign-in and the llama.cpp runtime.
 *
 * [newServices] builds a *fresh* object graph over the same persisted storage, which is what a process
 * restart does: nothing survives in memory, everything has to come back from disk.
 */
class AppFixture(
    val hub: FakeHubServer? = null,
    /** Storage namespace; pass the same one from two test processes to share what is on disk. */
    namespace: String = "e2e${System.nanoTime()}",
    /** False keeps whatever an earlier process left on disk under [namespace]. */
    fresh: Boolean = true,
    /**
     * The rows written to the (otherwise brand-new) database before the app starts. The app seeds no demo
     * data of its own, so tests that need a month of spending bring it; pass `emptyList()` for a true
     * fresh install. Ignored when [fresh] is false (the earlier process's rows are kept).
     */
    val transactions: List<Transaction> = SampleData.markSantosTransactions(),
) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ns = namespace
    private val modelsDir = File(context.filesDir, "e2e-models-$ns")
    private val prefNames = listOf("models", "codex", "backend", "secrets").map { "e2e_${it}_$ns" }
    val secretsPrefsName: String get() = prefNames[3]
    private val keyAlias = "finsight_e2e_key_$ns"

    /** The current graph's fakes; replaced by [newServices]. */
    lateinit var codexClient: FakeCodexClient
    lateinit var codexAuth: CodexAuthManager
    lateinit var codex: FakeCodexGenerator
    lateinit var runtime: FakeRuntime
    lateinit var services: AiServices
    lateinit var store: ModelStore
    lateinit var hfTokens: HfTokenStore
    lateinit var prefs: BackendPreferences

    init {
        if (fresh) {
            wipeStorage()
            seedTransactions()
        }
        services = newServices()
    }

    private fun seedTransactions() {
        if (transactions.isEmpty()) return
        val db = database()
        try {
            transactions.forEach(db::insertTransaction)
        } finally {
            db.close()
        }
    }

    fun deleteDatabase() { context.deleteDatabase(FinSightDatabaseHelper.DATABASE_NAME) }

    fun database() = FinSightDatabaseHelper(context)

    /** A new object graph over the persisted storage (new process, same disk). */
    fun newServices(): AiServices {
        val modelPrefs = context.getSharedPreferences(prefNames[0], Context.MODE_PRIVATE)
        val codexPrefs = context.getSharedPreferences(prefNames[1], Context.MODE_PRIVATE)
        val backendPrefs = context.getSharedPreferences(prefNames[2], Context.MODE_PRIVATE)
        val secrets = KeystoreSecretStore(context, keyAlias = keyAlias, prefsName = prefNames[3])
        store = ModelStore(modelsDir, SharedPreferencesKeyValueStore(modelPrefs))
        hfTokens = HfTokenStore(secrets)
        prefs = BackendPreferences(backendPrefs)
        codexClient = FakeCodexClient()
        val auth = CodexAuthManager(codexClient, secrets, pause = { delay(50) })
        codexAuth = auth
        codex = FakeCodexGenerator(auth, CodexModelPreference(SharedPreferencesKeyValueStore(codexPrefs)))
        runtime = FakeRuntime()
        val baseUrl = hub?.baseUrl ?: "http://127.0.0.1:1" // nothing listens: any accidental real call fails fast
        services = AiServices(
            secrets = secrets,
            modelStore = store,
            downloader = HttpModelDownloader(
                store, baseUrl = baseUrl, tokenProvider = { hfTokens.get() },
                freeSpace = { Long.MAX_VALUE }, maxRetries = 3, retryDelayMs = 50, readTimeoutMs = 10_000,
            ),
            hfSearch = HfSearchService(HttpHfApi(baseUrl, tokenProvider = { hfTokens.get() }), hasToken = { hfTokens.hasToken() }),
            hfTokens = hfTokens,
            codexAuth = auth,
            backendPrefs = prefs,
            runtime = runtime,
            codex = codex,
        )
        return services
    }

    /** Starts the activity on the current object graph. */
    fun launch(): ActivityScenario<MainActivity> {
        AiServices.overrideForTest(services)
        return ActivityScenario.launch(MainActivity::class.java)
    }

    /** Closes [scenario] and starts the app again as a new process would: new graph, same disk. */
    fun restart(scenario: ActivityScenario<MainActivity>): ActivityScenario<MainActivity> {
        scenario.close()
        newServices()
        return launch()
    }

    fun cleanup() {
        AiServices.overrideForTest(null)
        wipeStorage()
    }

    private fun wipeStorage() {
        deleteDatabase()
        modelsDir.deleteRecursively()
        prefNames.forEach { context.deleteSharedPreferences(it) }
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
    }
}
