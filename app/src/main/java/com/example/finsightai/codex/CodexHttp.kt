package com.example.finsightai.codex

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of a fully-read HTTP exchange. */
internal class HttpResult(val status: Int, val body: String)

/** Minimal HttpURLConnection helpers that are cancellable (cancelling disconnects the socket). */
internal object CodexHttp {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000

    fun open(url: String, method: String, headers: Map<String, String>, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    /** Runs blocking [block] on IO, disconnecting [conn] if the coroutine is cancelled meanwhile. */
    suspend fun <T> cancellable(conn: HttpURLConnection, block: () -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { conn.disconnect() } // unblocks the read in `block`
                }
            }
            try {
                block()
            } catch (e: IOException) {
                ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    suspend fun send(url: String, method: String, headers: Map<String, String>, body: String?): HttpResult {
        val conn = open(url, method, headers)
        return try {
            cancellable(conn) {
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
                HttpResult(status, stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty())
            }
        } finally {
            conn.disconnect()
        }
    }
}
