package com.example.finsightai.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** The real HttpModelDownloader against a tiny local HTTP server on the device (no internet): download + resume. */
@RunWith(AndroidJUnit4::class)
class HttpModelDownloaderOnDeviceTest {
    private val payload = ByteArray(300_000) { (it * 31).toByte() }
    private lateinit var server: ServerSocket
    private lateinit var root: File
    @Volatile private var dropFirstAfter = -1
    private val rangeHeaders = mutableListOf<String?>()

    @Before fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dl-${System.nanoTime()}").also { it.mkdirs() }
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    @After fun tearDown() { server.close(); root.deleteRecursively() }

    private fun serve(socket: java.net.Socket) = socket.use {
        val input = it.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        synchronized(rangeHeaders) { rangeHeaders += range }
        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val body = payload.copyOfRange(start, payload.size)
        val out = it.getOutputStream()
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val head = StringBuilder("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (range != null) head.append("Content-Range: bytes $start-${payload.size - 1}/${payload.size}\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        val drop = dropFirstAfter
        if (drop >= 0 && range == null) {
            dropFirstAfter = -1
            out.write(body, 0, drop); out.flush() // simulate a connection drop mid-body
        } else {
            out.write(body); out.flush()
        }
    }

    private fun spec(sha: String?) = ModelSpec(
        id = "local/test.gguf", displayName = "Local test", family = ModelFamily.COMMUNITY, repoId = "local/test",
        fileName = "test.gguf", sizeBytes = payload.size.toLong(), quantization = "Q4_K_M", minRamMb = 1, recommendedRamMb = 1,
        gated = false, license = "mit", description = "test", source = ModelSource.SEARCH, sha256 = sha
    )

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun downloadsVerifiesAndInstalls() = runBlocking {
        val store = ModelStore(root, InMemoryKeyValueStore())
        val downloader = HttpModelDownloader(store, baseUrl = "http://127.0.0.1:${server.localPort}", freeSpace = { Long.MAX_VALUE })
        val states = downloader.download(spec(sha256(payload))).toList()
        val done = states.last() as DownloadState.Completed
        assertArrayEquals(payload, done.file.readBytes())
        assertTrue(states.any { it is DownloadState.Progress })
        assertFalse(store.partFile(spec(null)).exists())
    }

    @Test fun resumesFromPartialBytesAfterADroppedConnection() = runBlocking {
        dropFirstAfter = 100_000
        val store = ModelStore(root, InMemoryKeyValueStore())
        val downloader = HttpModelDownloader(
            store, baseUrl = "http://127.0.0.1:${server.localPort}", freeSpace = { Long.MAX_VALUE }, retryDelayMs = 10
        )
        val states = downloader.download(spec(sha256(payload))).toList()
        assertTrue("completed after retry: ${states.last()}", states.last() is DownloadState.Completed)
        assertArrayEquals(payload, (states.last() as DownloadState.Completed).file.readBytes())
        val ranges = synchronized(rangeHeaders) { rangeHeaders.toList() }
        assertEquals("second request must resume with a Range header", 2, ranges.size)
        assertTrue(ranges[1]!!.startsWith("bytes="))
    }
}
