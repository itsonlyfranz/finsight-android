package com.example.finsightai.codex

import com.example.finsightai.security.SecretStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One recorded request. */
class Recorded(val path: String, val headers: Map<String, String>, val body: String)

class Reply(val status: Int, val body: String, val contentType: String = "application/json", val chunks: List<String>? = null)

/** Local HTTP server standing in for auth.openai.com and chatgpt.com/backend-api/codex. */
class FakeCodexServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Recorded>()
    private val handlers = HashMap<String, (Recorded) -> Reply>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val endpoints: CodexEndpoints
        get() = CodexEndpoints(issuer = baseUrl, backendBaseUrl = "$baseUrl/backend", userAgent = "test-agent")

    fun on(path: String, handler: (Recorded) -> Reply) { handlers[path] = handler }

    fun count(path: String) = requests.count { it.path == path }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        val rec = Recorded(ex.requestURI.path, headers, body)
        requests.add(rec)
        val reply = handlers[rec.path]?.invoke(rec) ?: Reply(404, "{}")
        ex.responseHeaders.add("Content-Type", reply.contentType)
        val bytes = reply.body.toByteArray()
        if (reply.chunks != null) {
            ex.sendResponseHeaders(reply.status, 0)
            ex.responseBody.use { out ->
                reply.chunks.forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(5) }
            }
        } else {
            ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
    }

    fun stop() = server.stop(0)
    override fun close() = stop()

    companion object {
        const val USERCODE = "/api/accounts/deviceauth/usercode"
        const val DEVICE_TOKEN = "/api/accounts/deviceauth/token"
        const val OAUTH_TOKEN = "/oauth/token"
        const val RESPONSES = "/backend/responses"
        const val MODELS = "/backend/models"
    }
}

/** Builds an unsigned JWT with the given payload JSON (signature part is dummy). */
fun fakeJwt(payloadJson: String): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
        enc.encodeToString(payloadJson.toByteArray()) + ".sig"
}

fun idTokenFor(email: String = "mark@example.com", account: String = "acct-1", plan: String = "plus", exp: Long = 4_000_000_000L) =
    fakeJwt("""{"email":"$email","exp":$exp,"https://api.openai.com/auth":{"chatgpt_account_id":"$account","chatgpt_plan_type":"$plan"}}""")

fun accessTokenFor(tag: String, exp: Long) = fakeJwt("""{"jti":"$tag","exp":$exp}""")

fun tokenResponse(access: String, refresh: String, id: String = idTokenFor()) =
    """{"id_token":"$id","access_token":"$access","refresh_token":"$refresh"}"""

/** SecretStore that records every write so tests can assert what was persisted and when. */
class RecordingSecretStore : SecretStore {
    val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    val log = CopyOnWriteArrayList<String>()
    @Volatile var failWrites = false
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        if (failWrites) throw IllegalStateException("keystore down")
        values[key] = value
        log.add("put:$key")
    }
    override fun remove(key: String) { values.remove(key); log.add("remove:$key") }
}
