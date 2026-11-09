package com.example.finsightai.llm.hf

import com.example.finsightai.llm.ModelSpec
import com.example.finsightai.security.SecretStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Typed failure reasons surfaced to the UI. */
sealed interface HfError {
    data object Network : HfError

    /** 401/403: the repo is gated or the token is missing/invalid. */
    data class Unauthorized(val hadToken: Boolean) : HfError

    data object NotFound : HfError
    data object RateLimited : HfError
    data class Server(val code: Int) : HfError
}

class HfApiException(val error: HfError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Read-only view of the Hub API the search feature needs. */
interface HfApi {
    suspend fun searchRepos(query: String, limit: Int): List<HubRepo>
    suspend fun listFiles(repoId: String): List<HubFile>
}

/** Stores the optional Hugging Face access token in the app's [SecretStore]. Never logs or exposes it elsewhere. */
class HfTokenStore(private val secrets: SecretStore) {
    fun get(): String? = secrets.get(KEY)?.takeIf { it.isNotBlank() }
    fun set(token: String) {
        val t = token.trim()
        if (t.isEmpty()) secrets.remove(KEY) else secrets.put(KEY, t)
    }
    fun clear() = secrets.remove(KEY)
    fun hasToken(): Boolean = get() != null

    companion object {
        const val KEY = "hf_token"
    }
}

/** [HfApi] over java.net.HttpURLConnection. [baseUrl] is injectable so tests can point at a local server. */
class HttpHfApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val tokenProvider: () -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : HfApi {

    override suspend fun searchRepos(query: String, limit: Int): List<HubRepo> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$baseUrl/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=$limit" +
            "&expand[]=gguf&expand[]=gated&expand[]=downloads&expand[]=likes" +
            "&expand[]=pipeline_tag&expand[]=tags&expand[]=library_name"
        val body = getText(url)
        return parseSearch(body)
    }

    override suspend fun listFiles(repoId: String): List<HubFile> {
        val body = getText("$baseUrl/api/models/$repoId/tree/main?limit=1000")
        return parseTree(body)
    }

    private suspend fun getText(url: String): String = withContext(ioDispatcher) {
        val token = tokenProvider()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            when {
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                code == 401 || code == 403 ->
                    throw HfApiException(HfError.Unauthorized(!token.isNullOrBlank()), "Hugging Face returned $code")
                code == 404 -> throw HfApiException(HfError.NotFound, "Not found")
                code == 429 -> throw HfApiException(HfError.RateLimited, "Rate limited by Hugging Face")
                else -> throw HfApiException(HfError.Server(code), "Hugging Face returned $code")
            }
        } catch (e: HfApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HfApiException(HfError.Network, "Timed out talking to Hugging Face", e)
        } catch (e: IOException) {
            throw HfApiException(HfError.Network, "Could not reach Hugging Face", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"
        const val USER_AGENT = "FinSightAI-Android/1.0"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun parseSearch(body: String): List<HubRepo> {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: o.str("modelId") ?: return@mapNotNull null
                val gguf = o["gguf"] as? JsonObject
                HubRepo(
                    id = id,
                    tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    libraryName = o.str("library_name"),
                    pipelineTag = o.str("pipeline_tag"),
                    ggufArchitecture = gguf?.str("architecture"),
                    gated = isGated(o["gated"]),
                    downloads = o["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                    likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }
        }

        internal fun parseTree(body: String): List<HubFile> {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if (o.str("type") != "file") return@mapNotNull null
                val path = o.str("path") ?: return@mapNotNull null
                val lfs = o["lfs"] as? JsonObject
                val size = lfs?.get("size")?.jsonPrimitive?.longOrNull ?: o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                HubFile(path = path, sizeBytes = size, sha256 = lfs?.str("oid"))
            }
        }

        /** `gated` is `false` or the strings "auto"/"manual". */
        private fun isGated(el: JsonElement?): Boolean {
            val p = el as? JsonPrimitive ?: return false
            return p.booleanOrNull ?: (p.contentOrNull?.let { it != "false" && it.isNotBlank() } ?: false)
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/** How the user can get to a result. */
enum class RepoAccess {
    OPEN,

    /** Gated repo and the user has not set a token: downloading will fail until they do. */
    GATED_NEEDS_TOKEN,

    /** Gated repo and a token is set (the Hub may still refuse if the licence has not been accepted). */
    GATED_TOKEN_SET,
}

data class SearchResult(
    val repoId: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val access: RepoAccess,
    val architecture: String?,
    val license: String,
    /** Usable files, best default first. Never empty. */
    val models: List<ModelSpec>,
) {
    val best: ModelSpec get() = models.first()
}

/**
 * Searches the Hub and returns only repos that contain at least one file the on-device runtime can use.
 * Everything else is hidden, not greyed out.
 */
class HfSearchService(
    private val api: HfApi,
    private val compatibility: GgufCompatibility = GgufCompatibility(),
    private val hasToken: () -> Boolean = { false },
    /** How many candidate repos get their file tree fetched (each is one extra request). */
    private val maxTreeFetches: Int = 12,
    private val treeConcurrency: Int = 4,
    private val maxFilesPerRepo: Int = 6,
) {
    suspend fun search(query: String, limit: Int = 30): List<SearchResult> {
        val repos = api.searchRepos(query, limit.coerceIn(1, 100))
        val candidates = repos.filter { compatibility.rejectRepo(it) == null }.take(maxTreeFetches)
        val gate = Semaphore(treeConcurrency)
        val token = hasToken()
        return coroutineScope {
            candidates.map { repo ->
                async {
                    val files = try {
                        gate.withPermit { api.listFiles(repo.id) }
                    } catch (e: HfApiException) {
                        // One unreachable/blocked repo must not fail the whole search.
                        if (e.error is HfError.Network || e.error is HfError.RateLimited) throw e
                        return@async null
                    }
                    val report = compatibility.evaluate(repo, files)
                    if (!report.isUsable) {
                        null
                    } else {
                        SearchResult(
                            repoId = repo.id,
                            downloads = repo.downloads,
                            likes = repo.likes,
                            gated = repo.gated,
                            access = when {
                                !repo.gated -> RepoAccess.OPEN
                                token -> RepoAccess.GATED_TOKEN_SET
                                else -> RepoAccess.GATED_NEEDS_TOKEN
                            },
                            architecture = repo.ggufArchitecture,
                            license = repo.license,
                            models = report.usable.take(maxFilesPerRepo).map { compatibility.toSpec(repo, it) },
                        )
                    }
                }
            }.mapNotNull { it.await() }
        }
    }
}
