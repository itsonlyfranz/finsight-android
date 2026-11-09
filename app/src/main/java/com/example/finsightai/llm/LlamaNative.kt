package com.example.finsightai.llm

/** Receives streamed UTF-8 text pieces from native code; return false to cancel generation. */
fun interface TokenSink {
    fun onToken(piece: ByteArray): Boolean
}

/**
 * Thin JNI surface over llama.cpp (see src/main/cpp/finsight_llm.cpp). Not thread-safe per
 * handle: [LlamaCppEngine] serialises access.
 */
internal object LlamaNative {
    const val RC_OK = 0
    const val RC_LOAD_FAILED = 1
    const val RC_CONTEXT_FAILED = 2
    const val RC_PROMPT_TOO_LONG = 3
    const val RC_DECODE_FAILED = 4
    const val RC_CANCELLED = 5
    const val RC_BAD_HANDLE = 6

    @Volatile private var libraryLoaded = false

    /** True when the native library could be loaded on this device (arm64 builds only). */
    fun isAvailable(): Boolean = ensureLoaded()

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("finsight_llm")
            libraryLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeSystemInfo(): String
    external fun nativeLoad(path: String, nCtx: Int, outHandle: LongArray): Int
    external fun nativeRelease(handle: Long)
    external fun nativeGenerate(
        handle: Long,
        system: ByteArray,
        user: ByteArray,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink,
    ): Int
}
