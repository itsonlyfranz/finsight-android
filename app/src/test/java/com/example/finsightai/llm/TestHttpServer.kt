package com.example.finsightai.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Tiny in-process HTTP server for network-free tests. */
class TestHttpServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            try {
                handler(ex)
            } catch (e: Throwable) {
                runCatching { ex.sendResponseHeaders(500, -1) }
            } finally {
                ex.close()
            }
        }
        start()
    }
    val port: Int get() = server.address.port
    val localhostUrl: String get() = "http://localhost:$port"
    val ipUrl: String get() = "http://127.0.0.1:$port"
    override fun close() = server.stop(0)
}

fun HttpExchange.respond(code: Int, body: ByteArray, contentType: String = "application/json") {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.use { it.write(body) }
}

fun HttpExchange.respond(code: Int, text: String) = respond(code, text.toByteArray())

fun resource(name: String): String =
    TestHttpServer::class.java.getResourceAsStream("/hf/$name")!!.bufferedReader().use { it.readText() }
