package com.example.finsightai.codex

import com.example.finsightai.ai.backend.AiBackendException
import com.example.finsightai.ai.backend.AiRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CodexTextGeneratorTest {
    private lateinit var server: FakeCodexServer
    private lateinit var store: RecordingSecretStore
    private val now = 1_000_000_000_000L

    @Before fun setUp() {
        server = FakeCodexServer(); store = RecordingSecretStore()
        seed("tok-1", "ref-1", now + 3_600_000)
    }
    @After fun tearDown() { server.stop() }

    private fun seed(access: String, refresh: String, exp: Long) {
        store.put(CodexAuthManager.TOKENS_KEY, kotlinx.serialization.json.Json.encodeToString(
            CodexTokens.serializer(), CodexTokens("id", access, refresh, "acct-1", "mark@example.com", "plus", exp)))
    }

    private fun signedInGenerator(models: List<String> = listOf("m-one", "m-two")): CodexTextGenerator {
        val auth = CodexAuthManager(CodexAuthClient(server.endpoints) { now }, store, clock = { now })
        auth.restore()
        return CodexTextGenerator(auth, server.endpoints, models)
    }

    private fun sse(vararg deltas: String) =
        deltas.joinToString("") { """data: {"type":"response.output_text.delta","delta":"$it"}""" + "\n\n" } +
            """data: {"type":"response.completed","response":{}}""" + "\n\n"

    private val request = AiRequest(system = "You are a budgeting coach.", user = "Summarise: cafes ₱500")

    @Test fun streamsAndConcatenatesText_andSendsCodexShapedRequest() = runBlocking {
        server.on(FakeCodexServer.RESPONSES) { Reply(200, "", "text/event-stream", chunks = listOf("data: {\"type\":\"response.created\"}\n\n") + sse("Cut ", "cafes ", "by 20%").chunked(23)) }
        val gen = signedInGenerator()
        assertTrue(gen.isReady())
        assertEquals("Cut cafes by 20%", gen.generate(request))

        val req = server.requests.single()
        assertEquals("Bearer tok-1", req.headers["authorization"])
        assertEquals("acct-1", req.headers["chatgpt-account-id"])
        assertEquals("codex_cli_rs", req.headers["originator"])
        assertEquals("text/event-stream", req.headers["accept"])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(req.body).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("\"m-one\"", body["model"].toString())
        assertEquals("true", body["stream"].toString()); assertEquals("false", body["store"].toString())
        val input = body["input"].toString()
        assertTrue(input.contains("\"developer\"") && input.contains("budgeting coach") && input.contains("\"user\"") && input.contains("input_text"))
        assertFalse(body.containsKey("instructions"))
        assertEquals("Codex (m-one) · mark@example.com", gen.describe())
    }

    @Test fun unauthenticatedGeneratorIsNotReadyAndFailsClearly() = runBlocking {
        val auth = CodexAuthManager(CodexAuthClient(server.endpoints), RecordingSecretStore())
        val gen = CodexTextGenerator(auth, server.endpoints)
        assertFalse(gen.isReady())
        assertEquals(null, gen.describe())
        try { gen.generate(request); fail() } catch (e: AiBackendException) { assertTrue(e.message!!.contains("Sign in")) }
        assertEquals(0, server.requests.size)
    }

    @Test fun on401RefreshesOnceAndRetriesWithNewToken() = runBlocking {
        server.on(FakeCodexServer.RESPONSES) { r ->
            if (r.headers["authorization"] == "Bearer tok-1") Reply(401, """{"detail":"expired"}""")
            else Reply(200, sse("ok"), "text/event-stream")
        }
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(200, """{"access_token":"tok-2","refresh_token":"ref-2"}""") }
        assertEquals("ok", signedInGenerator().generate(request))
        assertEquals(1, server.count(FakeCodexServer.OAUTH_TOKEN))
        assertEquals(2, server.count(FakeCodexServer.RESPONSES))
        assertTrue(store.get(CodexAuthManager.TOKENS_KEY)!!.contains("ref-2"))
    }

    @Test fun repeated401GivesUpAfterOneRetry() = runBlocking {
        server.on(FakeCodexServer.RESPONSES) { Reply(401, "{}") }
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(200, """{"access_token":"tok-2","refresh_token":"ref-2"}""") }
        try { signedInGenerator().generate(request); fail() } catch (e: AiBackendException) { assertTrue(e.message!!.contains("Sign in again")) }
        assertEquals(2, server.count(FakeCodexServer.RESPONSES))
        assertEquals(1, server.count(FakeCodexServer.OAUTH_TOKEN))
    }

    @Test fun httpFailuresMapToUserPresentableMessages() = runBlocking {
        val expectations = mapOf(403 to "plan or region", 429 to "usage limit", 500 to "having problems", 503 to "having problems")
        for ((status, fragment) in expectations) {
            server.on(FakeCodexServer.RESPONSES) { Reply(status, """{"detail":"x"}""") }
            try { signedInGenerator().generate(request); fail("$status") } catch (e: AiBackendException) {
                assertTrue("$status -> ${e.message}", e.message!!.contains(fragment))
            }
        }
    }

    @Test fun badRequestAboutInstructionsRetriesWithInstructionsField() = runBlocking {
        val seenBodies = mutableListOf<String>()
        server.on(FakeCodexServer.RESPONSES) { r ->
            seenBodies.add(r.body)
            if (!r.body.contains("\"instructions\"")) Reply(400, """{"detail":"Instructions are required"}""")
            else Reply(200, sse("fine"), "text/event-stream")
        }
        assertEquals("fine", signedInGenerator().generate(request))
        assertEquals(2, seenBodies.size)
        assertTrue(seenBodies[1].contains("\"instructions\":\"You are a budgeting coach.\""))
        assertFalse(seenBodies[1].contains("developer"))
    }

    @Test fun unsupportedModelFallsThroughToNextModelAndIsRemembered() = runBlocking {
        val models = mutableListOf<String>()
        server.on(FakeCodexServer.RESPONSES) { r ->
            val m = Regex("\"model\":\"([^\"]+)\"").find(r.body)!!.groupValues[1]
            models.add(m)
            if (m == "m-one") Reply(400, """{"detail":"The 'm-one' model is not supported when using Codex with a ChatGPT account."}""")
            else Reply(200, sse("hi"), "text/event-stream")
        }
        val gen = signedInGenerator()
        assertEquals("hi", gen.generate(request))
        assertEquals("hi", gen.generate(request))
        assertEquals(listOf("m-one", "m-two", "m-two"), models)
        assertTrue(gen.describe()!!.contains("m-two"))
    }

    @Test fun streamedErrorEventBecomesBackendException() = runBlocking {
        server.on(FakeCodexServer.RESPONSES) { Reply(200, "data: {\"type\":\"error\",\"error\":{\"message\":\"quota exceeded\"}}\n\n", "text/event-stream") }
        try { signedInGenerator().generate(request); fail() } catch (e: AiBackendException) { assertTrue(e.message!!.contains("quota exceeded")) }
    }

    @Test fun emptyStreamIsAnError() = runBlocking {
        server.on(FakeCodexServer.RESPONSES) { Reply(200, "data: {\"type\":\"response.completed\"}\n\n", "text/event-stream") }
        try { signedInGenerator().generate(request); fail() } catch (e: AiBackendException) { assertTrue(e.message!!.contains("empty")) }
    }

    @Test fun networkFailureBecomesBackendException() = runBlocking {
        val gen = signedInGenerator()
        server.stop()
        try { gen.generate(request); fail() } catch (e: AiBackendException) { assertTrue(e.message!!.contains("Network")) }
    }

    @Test fun cancellationAbortsAHangingRequest() = runBlocking {
        val entered = AtomicInteger()
        server.on(FakeCodexServer.RESPONSES) { entered.incrementAndGet(); Thread.sleep(3_000); Reply(200, sse("late"), "text/event-stream") }
        val gen = signedInGenerator()
        val job = kotlinx.coroutines.GlobalScope.launch { gen.generate(request) }
        while (entered.get() == 0) kotlinx.coroutines.delay(10)
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        assertTrue("cancel took too long", (System.nanoTime() - t0) / 1_000_000 < 1_500)
        assertTrue(job.isCancelled)
    }
}
