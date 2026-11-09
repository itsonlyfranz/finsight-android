package com.example.finsightai.e2e

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One repo served by [FakeHubServer]: Hub search metadata plus the files in its tree. */
class FakeRepo(
    val id: String,
    val files: List<FakeFile>,
    val tags: List<String> = listOf("gguf", "license:apache-2.0"),
    val pipelineTag: String? = "text-generation",
    val architecture: String? = "llama",
    val gated: Boolean = false,
    val downloads: Long = 1_000,
)

/**
 * A file in a [FakeRepo]. [reportedSize] / [reportedSha256] are what the tree API advertises; the
 * default is the truth about [content], tests override them to model a corrupt or truncated file.
 */
class FakeFile(
    val path: String,
    val content: ByteArray,
    val reportedSize: Long = content.size.toLong(),
    val reportedSha256: String? = sha256(content),
) {
    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** A tiny but GGUF-shaped payload (magic, version, filler); the fake runtime never parses it. */
fun tinyGguf(size: Int = 96 * 1024, seed: Int = 1): ByteArray =
    ByteArray(size) { i ->
        when (i) {
            0 -> 'G'.code.toByte()
            1 -> 'G'.code.toByte()
            2 -> 'U'.code.toByte()
            3 -> 'F'.code.toByte()
            else -> ((i * 31 + seed) and 0xFF).toByte()
        }
    }

/** How the next file download is served. */
sealed interface Delivery {
    data object Normal : Delivery

    /** Sends [afterBytes] and then stalls until [FakeHubServer.release] (a cancel closes the socket). */
    data class HoldAfter(val afterBytes: Int) : Delivery

    /** Sends [afterBytes] of the first full-body response, then drops the connection once. */
    data class DropAfter(val afterBytes: Int) : Delivery

    /** Answers with this HTTP status and no body. */
    data class Status(val code: Int) : Delivery
}

/** One request the server saw, for assertions on Range / Authorization handling. */
data class HubRequest(val path: String, val range: String?, val authorization: String?)

/**
 * A fake Hugging Face on a local socket: the Hub search + tree JSON that [com.example.finsightai.llm.hf.HttpHfApi]
 * parses, and `resolve/main` file downloads (with `Range`) that [com.example.finsightai.llm.HttpModelDownloader]
 * resumes. No real network is involved, so the whole search -> download -> install chain runs for real.
 */
class FakeHubServer(private val repos: List<FakeRepo>) {
    private val server = ServerSocket(0)
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<HubRequest>()

    /** What happens to file downloads; reset to [Delivery.Normal] by [reset]. */
    @Volatile var delivery: Delivery = Delivery.Normal

    /** Status for `/api/models` (search) requests; 200 serves results. */
    @Volatile var searchStatus: Int = 200

    @Volatile private var hold = CountDownLatch(1)
    @Volatile private var dropped = false

    init {
        thread(isDaemon = true, name = "fake-hub-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true, name = "fake-hub-conn") { runCatching { serve(socket) } }
            }
        }
    }

    fun close() { server.close(); hold.countDown() }

    fun release() = hold.countDown()

    fun reset() { delivery = Delivery.Normal; searchStatus = 200; hold = CountDownLatch(1); dropped = false; requests.clear() }

    fun fileRequests(): List<HubRequest> = requests.filter { "/resolve/main/" in it.path }

    private fun serve(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        var range: String? = null
        var auth: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            when {
                line.startsWith("Range:", ignoreCase = true) -> range = line.substringAfter(':').trim()
                line.startsWith("Authorization:", ignoreCase = true) -> auth = line.substringAfter(':').trim()
            }
        }
        val path = request.split(' ').getOrNull(1) ?: return
        requests += HubRequest(path, range, auth)
        val out = it.getOutputStream()
        when {
            path.startsWith("/api/models?") -> search(out, path)
            "/tree/main" in path && path.startsWith("/api/models/") -> tree(out, path)
            "/resolve/main/" in path -> download(out, path, range)
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun search(out: java.io.OutputStream, path: String) {
        if (searchStatus != 200) return respond(out, searchStatus, "text/plain", "error".toByteArray())
        val query = java.net.URLDecoder.decode(path.substringAfter("search=").substringBefore('&'), "UTF-8").lowercase()
        val hits = repos.filter { query.isBlank() || it.id.lowercase().contains(query) }.sortedByDescending { it.downloads }
        val json = hits.joinToString(prefix = "[", postfix = "]") { r ->
            val gguf = r.architecture?.let { a -> ""","gguf":{"architecture":"$a","total":1,"context_length":4096}""" }.orEmpty()
            val pipeline = r.pipelineTag?.let { p -> ""","pipeline_tag":"$p"""" }.orEmpty()
            """{"id":"${r.id}","gated":${r.gated},"likes":1,"downloads":${r.downloads},"tags":[${r.tags.joinToString(",") { t -> "\"$t\"" }}],"library_name":"gguf"$gguf$pipeline}"""
        }
        respond(out, 200, "application/json", json.toByteArray())
    }

    private fun tree(out: java.io.OutputStream, path: String) {
        val repoId = path.removePrefix("/api/models/").substringBefore("/tree/main")
        val repo = repos.firstOrNull { it.id == repoId } ?: return respond(out, 404, "text/plain", "no repo".toByteArray())
        val json = repo.files.joinToString(prefix = "[", postfix = "]") { f ->
            val lfs = f.reportedSha256?.let { s -> ""","lfs":{"oid":"$s","size":${f.reportedSize}}""" }.orEmpty()
            """{"type":"file","oid":"x","size":${f.reportedSize},"path":"${f.path}"$lfs}"""
        }
        respond(out, 200, "application/json", json.toByteArray())
    }

    private fun download(out: java.io.OutputStream, path: String, range: String?) {
        val rest = path.substringBefore('?').removePrefix("/")
        val repoId = rest.substringBefore("/resolve/main/")
        val fileName = rest.substringAfter("/resolve/main/")
        val file = repos.firstOrNull { it.id == repoId }?.files?.firstOrNull { it.path == fileName }
            ?: return respond(out, 404, "text/plain", "no file".toByteArray())
        val mode = delivery
        if (mode is Delivery.Status) return respond(out, mode.code, "text/plain", ByteArray(0))

        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val body = file.content.copyOfRange(start.coerceAtMost(file.content.size), file.content.size)
        val head = StringBuilder(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Length: ${body.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (range != null) head.append("Content-Range: bytes $start-${file.content.size - 1}/${file.content.size}\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        when {
            mode is Delivery.HoldAfter -> {
                val first = mode.afterBytes.coerceAtMost(body.size)
                out.write(body, 0, first); out.flush()
                hold.await(60, TimeUnit.SECONDS) // a cancelled client closes the socket; the next write fails
                out.write(body, first, body.size - first)
            }
            mode is Delivery.DropAfter && range == null && !dropped -> {
                dropped = true
                out.write(body, 0, mode.afterBytes.coerceAtMost(body.size)); out.flush()
                return // closing the socket mid-body is the "connection dropped" the downloader must resume from
            }
            else -> out.write(body)
        }
        out.flush()
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }
}
