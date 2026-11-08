package com.example.finsightai.llm.hf

import com.example.finsightai.llm.ModelFamily
import com.example.finsightai.llm.ModelSource
import com.example.finsightai.llm.TestHttpServer
import com.example.finsightai.llm.resource
import com.example.finsightai.llm.respond
import com.example.finsightai.security.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

/** Serves JSON captured from the live Hub API (src/test/resources/hf) from a local server. */
class HfSearchServiceTest {
    private val seenAuth = mutableListOf<String?>()
    private val seenQueries = mutableListOf<String>()

    private fun server() = TestHttpServer { ex ->
        seenAuth += ex.requestHeaders.getFirst("Authorization")
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty()
        when {
            path == "/api/models" -> {
                seenQueries += query
                val q = URLDecoder.decode(query.substringAfter("search=").substringBefore('&'), "UTF-8")
                val file = when {
                    q.contains("bonsai", true) -> "search_bonsai.json"
                    q.contains("lfm", true) -> "search_lfm3.json"
                    q.contains("gemma", true) -> "search_gemma.json"
                    q == "gated" -> null
                    else -> "[]".let { ex.respond(200, it); return@TestHttpServer }
                }
                if (file == null) ex.respond(401, """{"error":"Invalid credentials"}""") else ex.respond(200, resource(file))
            }
            path == "/api/models/prism-ml/Ternary-Bonsai-2-2B-gguf/tree/main" -> ex.respond(200, resource("tree_ternary_bonsai_2b.json"))
            path == "/api/models/LiquidAI/LFM3-1.7B-GGUF/tree/main" -> ex.respond(200, resource("tree_lfm3.json"))
            path == "/api/models/unsloth/gemma-4-E2B-it-GGUF/tree/main" -> ex.respond(200, resource("tree_unsloth_gemma_e2b.json"))
            // Everything else: repos whose files we never captured. Give them only an mmproj so they are hidden.
            path.endsWith("/tree/main") -> ex.respond(200, """[{"type":"file","path":"mmproj-x-F16.gguf","size":100,"lfs":{"oid":"00","size":100}}]""")
            else -> ex.respond(404, "{}")
        }
    }

    private fun service(s: TestHttpServer, token: String? = null, hasToken: Boolean = token != null) =
        HfSearchService(HttpHfApi(baseUrl = s.localhostUrl, tokenProvider = { token }), hasToken = { hasToken })

    @Test fun bonsaiSearchShowsOnlyAndroidUsableRepos() = runBlocking {
        server().use { s ->
            val r = service(s).search("bonsai")
            // 27B repos: arch ok but tree has only an mmproj -> hidden; 9B 'dflash' arch -> hidden.
            assertEquals(listOf("prism-ml/Ternary-Bonsai-2-2B-gguf"), r.map { it.repoId })
            val hit = r.single()
            // PQ2_0 / PTQ1_0 are refused, mmproj refused: only Q2_0 remains.
            assertEquals(listOf("Ternary-Bonsai-2-2B-Q2_0.gguf"), hit.models.map { it.fileName })
            val spec = hit.best
            assertEquals(ModelFamily.BONSAI, spec.family)
            assertEquals(ModelSource.SEARCH, spec.source)
            assertEquals("Q2_0", spec.quantization)
            assertEquals(546_689_504L, spec.sizeBytes)
            assertEquals("c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650", spec.sha256)
            assertEquals(RepoAccess.OPEN, hit.access)
            assertEquals("apache-2.0", hit.license)
            assertTrue(hit.downloads > 0)
        }
    }

    @Test fun requestAsksForGgufAndExpandedFields() = runBlocking {
        server().use { s ->
            service(s).search("lfm 3")
            val q = seenQueries.single()
            assertTrue(q, "filter=gguf" in q && "sort=downloads" in q && "expand[]=gguf" in q && "search=lfm+3" in q)
        }
    }

    @Test fun lfmSearchHidesAudioRepoAndKeepsTextModels() = runBlocking {
        server().use { s ->
            val ids = service(s).search("lfm3").map { it.repoId }
            assertTrue("LiquidAI/LFM3-1.7B-GGUF" in ids)
            assertFalse(ids.any { "Audio" in it })
            val lfm = service(s).search("lfm3").first { it.repoId == "LiquidAI/LFM3-1.7B-GGUF" }
            assertEquals("LFM3-1.7B-Q4_K_M.gguf", lfm.best.fileName) // preferred quant first
            assertTrue(lfm.models.none { it.fileName.contains("BF16") || it.fileName.contains("F16") || it.fileName.contains("NVFP4") })
            assertEquals(ModelFamily.LFM, lfm.best.family)
        }
    }

    @Test fun gemmaSearchHidesUnsupportedFilesAndKeepsHubOrder() = runBlocking {
        server().use { s ->
            val r = service(s).search("gemma-4-E2B")
            val unsloth = r.first { it.repoId == "unsloth/gemma-4-E2B-it-GGUF" }
            val names = unsloth.models.map { it.fileName }
            assertEquals("gemma-4-E2B-it-Q4_K_M.gguf", names.first())
            assertTrue(names.none { "mmproj" in it || "BF16" in it || it.startsWith("mtp") })
            assertFalse("gemma-4-E2B-it-Q8_0.gguf" in names) // 5.05 GB exceeds the 4 GiB phone limit
        }
    }

    @Test fun emptyAndOffTopicResultsAreEmpty() = runBlocking {
        server().use { s -> assertTrue(service(s).search("zzzz").isEmpty()) }
    }

    @Test fun gatedReposAreFlaggedByTokenPresence() {
        val gatedRepo = HubRepo("g/gated-GGUF", tags = listOf("gguf"), ggufArchitecture = "gemma4", gated = true)
        val api = object : HfApi {
            override suspend fun searchRepos(query: String, limit: Int) = listOf(gatedRepo)
            override suspend fun listFiles(repoId: String) = listOf(HubFile("g-Q4_0.gguf", 1_000_000, "aa"))
        }
        runBlocking {
            assertEquals(RepoAccess.GATED_NEEDS_TOKEN, HfSearchService(api, hasToken = { false }).search("g").single().access)
            assertEquals(RepoAccess.GATED_TOKEN_SET, HfSearchService(api, hasToken = { true }).search("g").single().access)
            assertTrue(HfSearchService(api).search("g").single().best.gated)
        }
    }

    @Test fun tokenIsSentAsBearerWhenSet() = runBlocking {
        server().use { s ->
            service(s, token = "hf_secret").search("bonsai")
            assertTrue(seenAuth.isNotEmpty() && seenAuth.all { it == "Bearer hf_secret" })
        }
    }

    @Test fun noAuthorizationHeaderWithoutToken() = runBlocking {
        server().use { s ->
            service(s).search("bonsai")
            assertTrue(seenAuth.all { it == null })
        }
    }

    @Test fun unauthorizedMapsToTypedError() = runBlocking {
        server().use { s ->
            try {
                service(s, token = "bad").search("gated")
                fail("expected HfApiException")
            } catch (e: HfApiException) {
                assertEquals(HfError.Unauthorized(hadToken = true), e.error)
                assertFalse("hf" in e.message.orEmpty().lowercase().replace("hugging face", ""))
            }
        }
    }

    @Test fun networkFailureMapsToTypedError() = runBlocking {
        val api = HttpHfApi(baseUrl = "http://127.0.0.1:1")
        try {
            api.searchRepos("x", 5)
            fail()
        } catch (e: HfApiException) {
            assertEquals(HfError.Network, e.error)
        }
    }

    @Test fun parsesGatedStringAndMissingFields() {
        val repos = HttpHfApi.parseSearch("""[{"id":"a/b","gated":"manual","tags":["gguf","license:mit"]},{"id":"c/d","gated":false}]""")
        assertTrue(repos[0].gated); assertFalse(repos[1].gated)
        assertEquals("mit", repos[0].license)
        assertNull(repos[0].ggufArchitecture)
    }

    @Test fun tokenStoreRoundTripsThroughSecretStore() {
        val secrets = InMemorySecretStore()
        val store = HfTokenStore(secrets)
        assertFalse(store.hasToken())
        store.set("  hf_abc  ")
        assertEquals("hf_abc", store.get())
        assertEquals("hf_abc", secrets.get("hf_token"))
        store.set("")
        assertFalse(store.hasToken())
    }
}
