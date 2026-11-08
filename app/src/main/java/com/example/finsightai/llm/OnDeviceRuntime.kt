package com.example.finsightai.llm

import android.content.Context
import com.example.finsightai.ai.GeneratedText
import com.example.finsightai.ai.backend.AiBackendException
import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.ai.backend.TextGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A model file that is installed and selected as the active on-device model. */
data class ActiveModel(val id: String, val displayName: String, val file: File)

/** Runs a GGUF model on this device. One model is loaded at a time; calls are serialised. */
interface OnDeviceRuntime {
    /** False on devices/ABIs the native library was not built for. */
    val isSupported: Boolean

    /** Id of the model currently held in memory, if any. */
    val loadedModelId: String?

    suspend fun load(modelId: String, file: File)

    /** Streams pieces to [onPiece] (may be empty) and returns the full text. */
    suspend fun generate(request: AiRequest, onPiece: (String) -> Unit = {}): String

    suspend fun unload()

    /** CPU feature / backend summary, for diagnostics. */
    fun systemInfo(): String
}

/** [OnDeviceRuntime] backed by llama.cpp through JNI. */
class LlamaCppRuntime(context: Context) : OnDeviceRuntime {
    private val nativeLibDir = context.applicationContext.applicationInfo.nativeLibraryDir
    private val lock = Mutex()
    private var handle: Long = 0L

    @Volatile override var loadedModelId: String? = null
        private set

    override val isSupported: Boolean get() = LlamaNative.isAvailable()

    override suspend fun load(modelId: String, file: File) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!LlamaNative.ensureLoaded()) throw AiBackendException("On-device AI is not supported on this device")
            if (loadedModelId == modelId && handle != 0L) return@withContext
            releaseLocked()
            if (!file.isFile) throw AiBackendException("Model file is missing: ${file.name}")
            LlamaNative.nativeInit(nativeLibDir)
            val out = LongArray(1)
            val rc = LlamaNative.nativeLoad(file.absolutePath, CONTEXT_TOKENS, out)
            when (rc) {
                LlamaNative.RC_OK -> {
                    handle = out[0]
                    loadedModelId = modelId
                }
                LlamaNative.RC_LOAD_FAILED ->
                    throw AiBackendException("This model could not be loaded (unsupported or corrupt GGUF)")
                else -> throw AiBackendException("Not enough memory to start this model (code $rc)")
            }
        }
    }

    override suspend fun generate(request: AiRequest, onPiece: (String) -> Unit): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = handle
            if (current == 0L) throw AiBackendException("No on-device model is loaded")
            val out = StringBuilder()
            val job = currentCoroutineContext()
            val rc = LlamaNative.nativeGenerate(
                current,
                request.system,
                request.user,
                request.maxTokens,
                request.temperature,
                TokenSink { bytes ->
                    val piece = String(bytes, Charsets.UTF_8)
                    out.append(piece)
                    onPiece(piece)
                    job.isActive
                },
            )
            when (rc) {
                LlamaNative.RC_OK -> out.toString()
                LlamaNative.RC_CANCELLED -> throw CancellationException("generation cancelled")
                LlamaNative.RC_PROMPT_TOO_LONG -> throw AiBackendException("The request is too long for this model")
                else -> throw AiBackendException("The on-device model failed while generating (code $rc)")
            }
        }
    }

    override suspend fun unload() = lock.withLock { withContext(Dispatchers.IO) { releaseLocked() } }

    override fun systemInfo(): String = if (LlamaNative.ensureLoaded()) {
        LlamaNative.nativeInit(nativeLibDir)
        LlamaNative.nativeSystemInfo()
    } else {
        "native library unavailable"
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            LlamaNative.nativeRelease(handle)
            handle = 0L
        }
        loadedModelId = null
    }

    private companion object {
        /** Prompts are short (a few hundred tokens); a small context keeps the KV cache cheap on phones. */
        const val CONTEXT_TOKENS = 2048
    }
}

/**
 * [TextGenerator] for the on-device backend. [activeModel] is read on every call, so selecting a
 * different model in settings takes effect on the next request (the old model is swapped out).
 */
class OnDeviceTextGenerator(
    private val runtime: OnDeviceRuntime,
    private val activeModel: () -> ActiveModel?,
) : TextGenerator {
    override val id: String = "on-device"

    override suspend fun describe(): String? = activeModel()?.displayName

    override suspend fun isReady(): Boolean = runtime.isSupported && activeModel()?.file?.isFile == true

    override suspend fun generate(request: AiRequest): GeneratedText {
        val model = activeModel() ?: throw AiBackendException("No on-device model is selected")
        runtime.load(model.id, model.file)
        return GeneratedText(runtime.generate(request), model.displayName)
    }
}
