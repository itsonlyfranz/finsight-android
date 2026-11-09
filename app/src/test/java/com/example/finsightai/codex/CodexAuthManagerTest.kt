package com.example.finsightai.codex

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CodexAuthManagerTest {
    private lateinit var server: FakeCodexServer
    private lateinit var store: RecordingSecretStore
    private var now = 1_000_000_000_000L // virtual clock, ms

    private fun manager(
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) = CodexAuthManager(
        client = CodexAuthClient(server.endpoints) { now },
        secrets = store,
        clock = { now },
        pause = { now += it }, // virtual time: sleeping just advances the clock
        scope = scope,
    )

    @Before fun setUp() { server = FakeCodexServer(); store = RecordingSecretStore() }
    @After fun tearDown() { server.stop() }

    private fun userCodeReply(expiresInMs: Long = 15 * 60_000L, base: Long = now) = Reply(
        200,
        """{"device_auth_id":"dev-1","user_code":"ABCD-1234","interval":"5","expires_at":"${java.time.Instant.ofEpochMilli(base + expiresInMs)}"}""",
    )

    private fun approvalReply() = Reply(200, """{"authorization_code":"auth-code","code_challenge":"cc","code_verifier":"verifier-xyz"}""")

    @Test fun happyDeviceFlowPollsThroughPendingThenExchanges() = runBlocking {
        server.on(FakeCodexServer.USERCODE) { userCodeReply() }
        val polls = AtomicInteger()
        server.on(FakeCodexServer.DEVICE_TOKEN) { if (polls.incrementAndGet() <= 2) Reply(403, """{"error":"authorization_pending"}""") else approvalReply() }
        server.on(FakeCodexServer.OAUTH_TOKEN) { tokenResponse(accessTokenFor("a1", 4_000_000_000L), "r1").let { Reply(200, it) } }

        val m = manager()
        val seen = mutableListOf<CodexAuthState>()
        val collector = launch(Dispatchers.Unconfined) { m.state.collect { seen.add(it) } }
        m.signInWithDeviceCode()
        collector.cancelAndJoin()

        assertEquals(CodexAuthState.SignedIn("mark@example.com", "plus", "acct-1"), m.state.value)
        assertTrue(seen.any { it is CodexAuthState.RequestingCode })
        val awaiting = seen.filterIsInstance<CodexAuthState.AwaitingApproval>().single()
        assertEquals("ABCD-1234", awaiting.userCode)
        assertEquals("${server.baseUrl}/codex/device", awaiting.verificationUrl)
        assertEquals(3, server.count(FakeCodexServer.DEVICE_TOKEN))

        // the poll body carries the device id + user code; the exchange carries the server's verifier
        val poll = server.requests.first { it.path == FakeCodexServer.DEVICE_TOKEN }
        assertTrue(poll.body.contains("dev-1") && poll.body.contains("ABCD-1234"))
        val exchange = server.requests.first { it.path == FakeCodexServer.OAUTH_TOKEN }
        assertTrue(exchange.body.contains("grant_type=authorization_code"))
        assertTrue(exchange.body.contains("code_verifier=verifier-xyz"))
        assertTrue(exchange.body.contains("client_id=app_EMoamEEZ73f0CkXaXp7hrann"))
        assertTrue(exchange.body.contains("redirect_uri=" + java.net.URLEncoder.encode("${server.baseUrl}/deviceauth/callback", "UTF-8")))
        assertTrue(exchange.headers["content-type"]!!.startsWith("application/x-www-form-urlencoded"))

        // virtual time advanced by 2 pending polls x 5s, no real sleeping
        assertEquals(10_000L, now - 1_000_000_000_000L)
        assertNotNull(store.get(CodexAuthManager.TOKENS_KEY))
    }

    @Test fun expiredDeviceCodeEndsInRetryableError() = runBlocking {
        server.on(FakeCodexServer.USERCODE) { userCodeReply(expiresInMs = 12_000) }
        server.on(FakeCodexServer.DEVICE_TOKEN) { Reply(404, "{}") }
        val m = manager()
        m.signInWithDeviceCode()
        val s = m.state.value as CodexAuthState.Error
        assertTrue(s.retryable)
        assertTrue(s.message.contains("expired"))
        assertNull(store.get(CodexAuthManager.TOKENS_KEY))
        assertTrue(server.count(FakeCodexServer.DEVICE_TOKEN) in 2..4) // bounded, no endless polling
    }

    @Test fun deniedIsNotRetryable() = runBlocking {
        server.on(FakeCodexServer.USERCODE) { userCodeReply() }
        server.on(FakeCodexServer.DEVICE_TOKEN) { Reply(400, """{"error":"access_denied"}""") }
        val m = manager()
        m.signInWithDeviceCode()
        val s = m.state.value as CodexAuthState.Error
        assertFalse(s.retryable)
        assertTrue(s.message.contains("denied"))
    }

    @Test fun networkFailureIsRetryableError() = runBlocking {
        server.stop()
        val m = manager()
        m.signInWithDeviceCode()
        val s = m.state.value as CodexAuthState.Error
        assertTrue(s.retryable)
        assertTrue(s.message.contains("Network"))
    }

    @Test fun deviceCodeNotEnabledIsReported() = runBlocking {
        server.on(FakeCodexServer.USERCODE) { Reply(404, "{}") }
        val m = manager()
        m.signInWithDeviceCode()
        assertTrue((m.state.value as CodexAuthState.Error).message.contains("not available"))
    }

    @Test fun cancelSignInStopsPollingAndReturnsToSignedOut() = runBlocking {
        server.on(FakeCodexServer.USERCODE) { userCodeReply(base = System.currentTimeMillis()) }
        server.on(FakeCodexServer.DEVICE_TOKEN) { Reply(403, "{}") }
        // real (short) sleeps so the job is genuinely suspended when cancelled
        val m = CodexAuthManager(CodexAuthClient(server.endpoints), store, pause = { delay(20) },
            scope = CoroutineScope(Dispatchers.Default))
        m.startSignIn()
        withTimeout(5_000) { while (m.state.value !is CodexAuthState.AwaitingApproval) delay(5) }
        m.cancelSignIn()
        assertEquals(CodexAuthState.SignedOut, m.state.value)
        delay(100)
        val after = server.count(FakeCodexServer.DEVICE_TOKEN)
        delay(150)
        assertEquals(after, server.count(FakeCodexServer.DEVICE_TOKEN))
    }

    // ---- token lifecycle ----

    private fun seedTokens(access: String, refresh: String, expiresAt: Long?) {
        store.put(CodexAuthManager.TOKENS_KEY,
            kotlinx.serialization.json.Json.encodeToString(CodexTokens.serializer(),
                CodexTokens("id", access, refresh, "acct-1", "mark@example.com", "plus", expiresAt)))
        store.log.clear()
    }

    @Test fun restoreLoadsPersistedSession() {
        seedTokens("a", "r", now + 3_600_000)
        val m = manager()
        assertEquals(CodexAuthState.SignedOut, m.state.value)
        m.restore()
        assertEquals(CodexAuthState.SignedIn("mark@example.com", "plus", "acct-1"), m.state.value)
        assertTrue(m.isSignedIn)
    }

    @Test fun validTokenIsReturnedWithoutRefresh() = runBlocking {
        seedTokens("a", "r", now + 3_600_000)
        val creds = manager().validAccessToken()
        assertEquals("a", creds.accessToken); assertEquals("acct-1", creds.accountId)
        assertEquals(0, server.requests.size)
    }

    @Test fun expiringTokenRefreshesAndPersistsRotatedTokenBeforeReturning() = runBlocking {
        seedTokens("old", "r-old", now + 60_000) // inside the 2-minute skew
        server.on(FakeCodexServer.OAUTH_TOKEN) {
            // when the server sees the request the new token cannot be stored yet; the old one still is
            assertTrue(store.get(CodexAuthManager.TOKENS_KEY)!!.contains("r-old"))
            Reply(200, """{"access_token":"new","refresh_token":"r-new"}""")
        }
        val m = manager(); m.restore()
        val creds = m.validAccessToken()
        assertEquals("new", creds.accessToken)
        assertEquals("acct-1", creds.accountId) // carried over: refresh response had no id_token
        val persisted = store.get(CodexAuthManager.TOKENS_KEY)!!
        assertTrue(persisted.contains("r-new") && !persisted.contains("r-old"))
        val body = server.requests.single().body
        assertTrue(body.contains("grant_type=refresh_token") && body.contains("refresh_token=r-old"))
    }

    @Test fun tokensAreOnlyEverWrittenThroughTheSecretStore() = runBlocking {
        seedTokens("old", "r-old", now - 1)
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(200, """{"access_token":"new","refresh_token":"r-new"}""") }
        manager().validAccessToken()
        assertEquals(listOf("put:${CodexAuthManager.TOKENS_KEY}"), store.log.toList())
    }

    @Test fun failedPersistenceNeverHandsOutTheRotatedToken() = runBlocking {
        seedTokens("old", "r-old", now - 1)
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(200, """{"access_token":"new","refresh_token":"r-new"}""") }
        store.failWrites = true
        try { manager().validAccessToken(); fail("expected failure") } catch (e: CodexAuthException) {
            assertTrue(e.message!!.contains("securely"))
        }
    }

    @Test fun concurrentCallersShareOneRefresh() = runBlocking {
        seedTokens("old", "r-old", now - 1)
        val hits = AtomicInteger()
        server.on(FakeCodexServer.OAUTH_TOKEN) { Thread.sleep(50); hits.incrementAndGet(); Reply(200, """{"access_token":"new","refresh_token":"r-new","expires_in":3600}""") }
        val m = manager(); m.restore()
        val results = (1..8).map { async(Dispatchers.Default) { m.validAccessToken().accessToken } }.awaitAll()
        assertEquals(1, hits.get())
        assertEquals(setOf("new"), results.toSet())
    }

    @Test fun rejectedTokenForcesExactlyOneRefresh() = runBlocking {
        seedTokens("t1", "r1", now + 3_600_000)
        val hits = AtomicInteger()
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(200, """{"access_token":"t2","refresh_token":"r2"}""".also { hits.incrementAndGet() }) }
        val m = manager(); m.restore()
        assertEquals("t2", m.validAccessToken(rejectedAccessToken = "t1").accessToken)
        // a second caller that also saw t1 rejected reuses the refreshed token
        assertEquals("t2", m.validAccessToken(rejectedAccessToken = "t1").accessToken)
        assertEquals(1, hits.get())
    }

    @Test fun permanentRefreshFailureSignsOutAndDoesNotLoop() = runBlocking {
        listOf("refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated").forEach { code ->
            server.requests.clear()
            seedTokens("old", "r-old", now - 1)
            server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(400, """{"error":{"code":"$code"}}""") }
            val m = manager(); m.restore()
            try { m.validAccessToken(); fail() } catch (e: CodexAuthException) { assertEquals(CodexAuthException.Kind.REFRESH_INVALID, e.kind) }
            val s = m.state.value as CodexAuthState.Error
            assertTrue(s.message.contains("sign in again"))
            assertNull(store.get(CodexAuthManager.TOKENS_KEY))
            // next call does not hit the network again
            try { m.validAccessToken(); fail() } catch (e: CodexAuthException) { assertEquals(CodexAuthException.Kind.NOT_SIGNED_IN, e.kind) }
            assertEquals(1, server.count(FakeCodexServer.OAUTH_TOKEN))
        }
    }

    @Test fun transientRefreshFailureKeepsTheSession() = runBlocking {
        seedTokens("old", "r-old", now - 1)
        server.on(FakeCodexServer.OAUTH_TOKEN) { Reply(503, "{}") }
        val m = manager(); m.restore()
        try { m.validAccessToken(); fail() } catch (e: CodexAuthException) { assertTrue(e.retryable) }
        assertTrue(m.state.value is CodexAuthState.SignedIn)
        assertNotNull(store.get(CodexAuthManager.TOKENS_KEY))
    }

    @Test fun signOutWipesTokens() = runBlocking {
        seedTokens("a", "r", now + 3_600_000)
        val m = manager(); m.restore()
        m.signOut()
        assertEquals(CodexAuthState.SignedOut, m.state.value)
        assertNull(store.get(CodexAuthManager.TOKENS_KEY))
        try { m.validAccessToken(); fail() } catch (e: CodexAuthException) { assertEquals(CodexAuthException.Kind.NOT_SIGNED_IN, e.kind) }
    }
}
