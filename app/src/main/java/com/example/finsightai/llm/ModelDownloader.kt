package com.example.finsightai.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface DownloadError {
    data class Network(val detail: String) : DownloadError

    /** 401/403. [gated] says the repo is gated; [hadToken] whether a token was sent. */
    data class Unauthorized(val gated: Boolean, val hadToken: Boolean) : DownloadError

    data object NotFound : DownloadError
    data class DiskFull(val neededBytes: Long, val availableBytes: Long) : DownloadError
    data class SizeMismatch(val expected: Long, val actual: Long) : DownloadError
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadError

    /** Reading back or saving the finished file failed (e.g. storage filled up while committing). */
    data class Storage(val detail: String) : DownloadError
}

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Progress(val bytesDone: Long, val totalBytes: Long) : DownloadState {
        val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** Download finished; checking size and SHA-256 before the file is registered. */
    data object Verifying : DownloadState
    data class Completed(val model: InstalledModel) : DownloadState {
        val file: File get() = model.file
    }

    data class Failed(val error: DownloadError) : DownloadState
}

interface ModelDownloader {
    /**
     * Downloads [spec] into the model store, resuming a previous partial download when possible.
     * Collect to run. Cancelling the collector, or a terminal failure, deletes the partial file;
     * call [ModelStore.discardPartial] to clean up a partial left by a process death.
     */
    fun download(spec: ModelSpec): Flow<DownloadState>
}

/**
 * HTTP implementation: `GET <baseUrl>/<repo>/resolve/main/<file>` with `Range` resume, manual redirect
 * handling (Hugging Face redirects to a CDN on another host; the bearer token is NOT forwarded
 * there), bounded automatic retries that resume from the bytes already on disk, and a final
 * size + SHA-256 check before the file is committed to the [ModelStore].
 */
class HttpModelDownloader(
    private val store: ModelStore,
    private val baseUrl: String = "https://huggingface.co",
    private val tokenProvider: () -> String? = { null },
    private val freeSpace: (File) -> Long = { f -> generateSequence(f) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressIntervalMs: Long = 200,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1_000,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : ModelDownloader {

    override fun download(spec: ModelSpec): Flow<DownloadState> = flow {
        emit(DownloadState.Queued)
        store.find(spec.id)?.let {
            emit(DownloadState.Completed(it))
            return@flow
        }
        val part = store.partFile(spec)
        part.parentFile?.mkdirs()
        // A partial file only survives a transient retry inside this call (or a process death, which
        // lets a later download() resume). Terminal failure or cancelling the collector discards it.
        var committed = false
        try {

            val needed = spec.sizeBytes - (if (part.isFile) part.length() else 0L) + DISK_MARGIN_BYTES
            val available = freeSpace(part)
            if (available < needed) {
                emit(DownloadState.Failed(DownloadError.DiskFull(needed, available)))
                return@flow
            }

            var attempt = 0
            var lastEmitNs = 0L
            while (true) {
                try {
                    transfer(spec, part) { done ->
                        val now = System.nanoTime()
                        if (done >= spec.sizeBytes || (now - lastEmitNs) / 1_000_000 >= progressIntervalMs) {
                            lastEmitNs = now
                            emit(DownloadState.Progress(done, spec.sizeBytes))
                        }
                    }
                    break
                } catch (e: DownloadFailure) {
                    emit(DownloadState.Failed(e.error))
                    return@flow
                } catch (e: IOException) {
                    attempt++
                    if (attempt > maxRetries) {
                        emit(DownloadState.Failed(DownloadError.Network(e.message ?: "connection lost")))
                        return@flow
                    }
                    delay(retryDelayMs * attempt)
                }
            }

            emit(DownloadState.Verifying)
            val outcome = try {
                verifyAndCommit(spec, part)
            } catch (e: IOException) {
                DownloadState.Failed(DownloadError.Storage(e.message ?: "could not save the model"))
            }
            committed = outcome is DownloadState.Completed
            emit(outcome)
        } finally {
            if (!committed) withContext(NonCancellable) { part.delete() }
        }
    }.flowOn(ioDispatcher)

    private class DownloadFailure(val error: DownloadError) : Exception()

    private fun verifyAndCommit(spec: ModelSpec, part: File): DownloadState {
        val actual = part.length()
        if (actual != spec.sizeBytes) return DownloadState.Failed(DownloadError.SizeMismatch(spec.sizeBytes, actual))
        spec.sha256?.let { expected ->
            val digest = sha256Of(part)
            if (!digest.equals(expected, ignoreCase = true)) return DownloadState.Failed(DownloadError.ChecksumMismatch(expected, digest))
        }
        return DownloadState.Completed(store.commit(spec, part))
    }

    /** Streams the remote file into [part], appending from its current length. */
    private suspend fun transfer(spec: ModelSpec, part: File, onProgress: suspend (Long) -> Unit) {
        var resumeFrom = if (part.isFile) part.length() else 0L
        if (resumeFrom > spec.sizeBytes) {
            part.delete()
            resumeFrom = 0
        }
        if (resumeFrom == spec.sizeBytes) return // already complete, go verify

        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        val conn = open("$baseUrl/${spec.resolvePath}", resumeFrom, token)
        try {
            val code = conn.responseCode
            when (code) {
                200, 206 -> Unit
                401, 403 -> throw DownloadFailure(DownloadError.Unauthorized(spec.gated, token != null))
                404 -> throw DownloadFailure(DownloadError.NotFound)
                416 -> { // our offset is beyond the file: start over
                    part.delete()
                    throw IOException("range not satisfiable")
                }
                else -> throw IOException("HTTP $code")
            }
            val append = code == 206 && resumeFrom > 0 && contentRangeStart(conn) == resumeFrom
            if (!append) part.delete() // server ignored Range (200) or returned another range: restart
            val start = if (append) resumeFrom else 0L

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(start)
                var done = start
                val buf = ByteArray(BUFFER_SIZE)
                val input: InputStream = conn.inputStream
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                } finally {
                    input.close()
                }
                if (done < spec.sizeBytes) throw IOException("connection closed after $done of ${spec.sizeBytes} bytes")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Opens [url], following up to [MAX_REDIRECTS] redirects by hand so credentials never leave the original host. */
    private fun open(url: String, resumeFrom: Long, token: String?): HttpURLConnection {
        var current = URL(url)
        val originHost = current.host
        for (hop in 0..MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "FinSightAI-Android/1.0")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            if (token != null && current.host.equals(originHost, ignoreCase = true)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without Location")
                current = URL(current, location)
                continue
            }
            return conn
        }
        throw IOException("too many redirects")
    }

    private fun contentRangeStart(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Content-Range")
            ?.let { Regex("bytes\\s+(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val DISK_MARGIN_BYTES = 64L * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
