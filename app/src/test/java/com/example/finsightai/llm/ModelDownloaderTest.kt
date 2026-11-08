package com.example.finsightai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(400_000) { (it * 31 + 7).toByte() }
    private val sha = HttpModelDownloader.sha256Of(File.createTempFile("payload", ".bin").also { it.writeBytes(payload); it.deleteOnExit() })
    private val spec = CuratedModels.bonsai17b.copy(
        id = "acme/m/m-Q4_0.gguf", repoId = "acme/m", fileName = "m-Q4_0.gguf",
        sizeBytes = payload.size.toLong(), sha256 = sha, gated = false,
    )
    private val resolvePath = "/acme/m/resolve/main/m-Q4_0.gguf"

    private fun store() = ModelStore(File(tmp.root, "models"), InMemoryKeyValueStore())

    private fun downloader(
        store: ModelStore, baseUrl: String, token: String? = null, free: Long = Long.MAX_VALUE,
    ) = HttpModelDownloader(
        store = store, baseUrl = baseUrl, tokenProvider = { token }, freeSpace = { free },
        ioDispatcher = Dispatchers.IO, progressIntervalMs = 0, maxRetries = 3, retryDelayMs = 5,
    )

    private fun run(d: HttpModelDownloader, s: ModelSpec = spec): List<DownloadState> =
        runBlocking { withTimeout(30_000) { d.download(s).toList() } }

    /** Serves [payload] honouring Range; [hook] may intercept first. */
    private fun fileServer(
        honourRange: Boolean = true,
        dropFirstAt: Int? = null,
        requests: MutableList<Pair<String?, String?>> = mutableListOf(),
        body: ByteArray = payload,
    ): TestHttpServer {
        val served = AtomicInteger(0)
        return TestHttpServer { ex ->
            requests += ex.requestHeaders.getFirst("Range") to ex.requestHeaders.getFirst("Authorization")
            val range = ex.requestHeaders.getFirst("Range")
            var start = 0
            var code = 200
            if (honourRange && range != null) {
                start = range.removePrefix("bytes=").substringBefore('-').toInt()
                code = 206
                ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
            }
            val len = body.size - start
            ex.sendResponseHeaders(code, len.toLong())
            val out = ex.responseBody
            if (dropFirstAt != null && served.getAndIncrement() == 0) {
                out.write(body, start, dropFirstAt) // then close early -> premature EOF on the client
                out.flush()
            } else {
                out.write(body, start, len)
            }
        }
    }

    @Test fun happyPathReportsProgressVerifiesAndInstalls() {
        val store = store()
        fileServer().use { s ->
            val states = run(downloader(store, s.localhostUrl))
            assertEquals(DownloadState.Queued, states.first())
            val progress = states.filterIsInstance<DownloadState.Progress>()
            assertTrue(progress.isNotEmpty())
            assertEquals(spec.sizeBytes, progress.last().bytesDone)
            assertEquals(1f, progress.last().fraction, 0f)
            assertTrue(progress.map { it.bytesDone }.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(DownloadState.Verifying in states)
            val done = states.last() as DownloadState.Completed
            assertEquals(spec.id, done.model.id)
            assertTrue(done.file.readBytes().contentEquals(payload))
            assertFalse(store.partFile(spec).exists())
            assertEquals(spec.id, store.activeModel.value?.id)
        }
    }

    @Test fun alreadyInstalledCompletesWithoutNetwork() {
        val store = store()
        fileServer().use { s -> run(downloader(store, s.localhostUrl)) }
        val states = run(downloader(store, "http://127.0.0.1:1"))
        assertTrue(states.last() is DownloadState.Completed)
        assertEquals(2, states.size)
    }

    @Test fun resumesAfterDroppedConnectionWithinOneDownload() {
        val store = store()
        val requests = mutableListOf<Pair<String?, String?>>()
        fileServer(dropFirstAt = 150_000, requests = requests).use { s ->
            val states = run(downloader(store, s.localhostUrl))
            assertTrue(states.last() is DownloadState.Completed)
            assertTrue(store.targetFile(spec).readBytes().contentEquals(payload))
            // First request is a full GET, the retry resumes from the bytes already on disk.
            assertNull(requests[0].first)
            assertEquals("bytes=150000-", requests[1].first)
        }
    }

    @Test fun resumesFromPartialLeftByAnEarlierProcess() {
        val store = store()
        store.partFile(spec).also { it.parentFile!!.mkdirs() }.writeBytes(payload.copyOf(100_000))
        val requests = mutableListOf<Pair<String?, String?>>()
        fileServer(requests = requests).use { s ->
            val states = run(downloader(store, s.localhostUrl))
            assertTrue(states.last() is DownloadState.Completed)
            assertEquals("bytes=100000-", requests.single().first)
            assertTrue((states.filterIsInstance<DownloadState.Progress>().first().bytesDone) > 100_000)
        }
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        val store = store()
        store.partFile(spec).also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(50_000) { 1 })
        fileServer(honourRange = false).use { s ->
            val states = run(downloader(store, s.localhostUrl))
            assertTrue(states.last() is DownloadState.Completed)
            assertTrue(store.targetFile(spec).readBytes().contentEquals(payload))
        }
    }

    @Test fun crossHostRedirectDropsAuthorizationHeader() {
        val store = store()
        val cdnAuth = mutableListOf<String?>()
        val originAuth = mutableListOf<String?>()
        lateinit var server: TestHttpServer
        server = TestHttpServer { ex ->
            if (ex.requestURI.path.startsWith("/cdn/")) {
                cdnAuth += ex.requestHeaders.getFirst("Authorization")
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload)
            } else {
                originAuth += ex.requestHeaders.getFirst("Authorization")
                // Same server, different host name (localhost -> 127.0.0.1) == different origin.
                ex.responseHeaders.add("Location", "${server.ipUrl}/cdn/blob?sig=1")
                ex.sendResponseHeaders(302, -1)
            }
        }
        server.use { s ->
            val states = run(downloader(store, s.localhostUrl, token = "hf_secret"))
            assertTrue(states.last() is DownloadState.Completed)
        }
        assertEquals(listOf<String?>("Bearer hf_secret"), originAuth)
        assertEquals(listOf<String?>(null), cdnAuth)
    }

    @Test fun sameHostRedirectKeepsAuthorization() {
        val store = store()
        val seen = mutableListOf<String?>()
        lateinit var server: TestHttpServer
        server = TestHttpServer { ex ->
            seen += ex.requestHeaders.getFirst("Authorization")
            if (ex.requestURI.path.startsWith("/final")) {
                ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.write(payload)
            } else {
                ex.responseHeaders.add("Location", "/final"); ex.sendResponseHeaders(307, -1)
            }
        }
        server.use { s ->
            assertTrue(run(downloader(store, s.localhostUrl, token = "t")).last() is DownloadState.Completed)
        }
        assertEquals(listOf<String?>("Bearer t", "Bearer t"), seen)
    }

    @Test fun gatedRepoMapsToUnauthorizedAndLeavesNoPart() {
        val store = store()
        for (code in listOf(401, 403)) {
            TestHttpServer { ex -> ex.sendResponseHeaders(code, -1) }.use { s ->
                val gated = spec.copy(gated = true)
                val last = run(downloader(store, s.localhostUrl, token = null), gated).last()
                assertEquals(DownloadState.Failed(DownloadError.Unauthorized(gated = true, hadToken = false)), last)
                val withToken = run(downloader(store, s.localhostUrl, token = "bad"), gated).last()
                assertEquals(DownloadState.Failed(DownloadError.Unauthorized(gated = true, hadToken = true)), withToken)
                assertFalse(store.partFile(gated).exists())
            }
        }
    }

    @Test fun notFoundMapsToNotFound() {
        TestHttpServer { ex -> ex.sendResponseHeaders(404, -1) }.use { s ->
            assertEquals(DownloadState.Failed(DownloadError.NotFound), run(downloader(store(), s.localhostUrl)).last())
        }
    }

    @Test fun sizeMismatchFailsAndLeavesNothing() {
        val store = store()
        val wrong = spec.copy(sizeBytes = payload.size.toLong() + 10)
        // Server serves 'payload', spec claims 10 more bytes: the stream ends early -> retries -> Network failure.
        fileServer().use { s ->
            val last = run(downloader(store, s.localhostUrl), wrong).last()
            assertTrue(last is DownloadState.Failed)
            assertFalse(store.partFile(wrong).exists())
            assertFalse(store.isInstalled(wrong.id))
        }
    }

    @Test fun serverSendingTooManyBytesIsASizeMismatch() {
        val store = store()
        val small = spec.copy(sizeBytes = payload.size.toLong() - 100, sha256 = null)
        fileServer().use { s ->
            val last = run(downloader(store, s.localhostUrl), small).last()
            assertEquals(DownloadState.Failed(DownloadError.SizeMismatch(small.sizeBytes, payload.size.toLong())), last)
            assertFalse(store.partFile(small).exists())
            assertFalse(store.isInstalled(small.id))
        }
    }

    @Test fun checksumMismatchFailsAndLeavesNoPartOrModel() {
        val store = store()
        val bad = spec.copy(sha256 = "0".repeat(64))
        fileServer().use { s ->
            val last = run(downloader(store, s.localhostUrl), bad).last() as DownloadState.Failed
            assertTrue(last.error is DownloadError.ChecksumMismatch)
            assertFalse(store.partFile(bad).exists())
            assertFalse(store.targetFile(bad).exists())
            assertTrue(store.installed.value.isEmpty())
        }
    }

    @Test fun diskFullIsReportedBeforeDownloading() {
        val store = store()
        val requests = mutableListOf<Pair<String?, String?>>()
        fileServer(requests = requests).use { s ->
            val last = run(downloader(store, s.localhostUrl, free = 1_000)).last() as DownloadState.Failed
            val err = last.error as DownloadError.DiskFull
            assertEquals(1_000L, err.availableBytes)
            assertTrue(err.neededBytes >= spec.sizeBytes)
            assertTrue(requests.isEmpty())
            assertFalse(store.partFile(spec).exists())
        }
    }

    @Test fun persistentNetworkFailureEndsFailedWithNoPartial() {
        val store = store()
        // Always drops after 10 KB, never completes: retries are bounded and the partial is removed.
        TestHttpServer { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload, 0, 10_000); ex.responseBody.flush()
        }.use { s ->
            val last = run(downloader(store, s.localhostUrl)).last()
            assertTrue(last is DownloadState.Failed && last.error is DownloadError.Network)
            assertFalse(store.partFile(spec).exists())
            assertTrue(store.installed.value.isEmpty())
        }
    }

    @Test fun unreachableServerFailsWithNetworkErrorAndNoPartial() {
        val store = store()
        val last = run(downloader(store, "http://127.0.0.1:1")).last()
        assertTrue(last is DownloadState.Failed && last.error is DownloadError.Network)
        assertFalse(store.partFile(spec).exists())
    }

    @Test fun cancellingTheCollectorDeletesThePartialFile() {
        val store = store()
        val started = java.util.concurrent.CountDownLatch(1)
        TestHttpServer { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload, 0, 100_000); ex.responseBody.flush()
            started.countDown()
            Thread.sleep(2_000) // stall: the client is cancelled meanwhile
        }.use { s ->
            runBlocking {
                withTimeout(30_000) {
                    // take(): stop collecting after the first byte progress => cancels the flow like a UI cancel.
                    downloader(store, s.localhostUrl).download(spec).first { it is DownloadState.Progress }
                }
            }
            assertFalse("part must be deleted on cancel", store.partFile(spec).exists())
            assertTrue(store.installed.value.isEmpty())
        }
    }

    @Test fun explicitDiscardPartialCleansUpAfterProcessDeath() {
        val store = store()
        store.partFile(spec).also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(1234))
        assertEquals(1234L, store.discardPartial(spec))
        assertFalse(store.partFile(spec).exists())
    }

    @Test fun completePartialIsJustVerifiedAndCommitted() {
        val store = store()
        store.partFile(spec).also { it.parentFile!!.mkdirs() }.writeBytes(payload)
        val states = run(downloader(store, "http://127.0.0.1:1")) // no server needed
        assertTrue(states.last() is DownloadState.Completed)
    }
}
