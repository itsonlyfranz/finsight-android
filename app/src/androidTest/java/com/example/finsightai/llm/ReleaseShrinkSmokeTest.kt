package com.example.finsightai.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the release build's R8 shrinking against breaking the llama.cpp JNI bridge. CI runs this
 * against the shrunk release APK (`-PciDebugSigning`); it also passes on the debug build.
 *
 * Launching the app is checked separately with plain `adb` in the workflow (the test APK carries its
 * own desugared java.time copy, so loading both in one process is not representative).
 *
 * None of it needs a model: every native entry point is called for real, so a stripped or renamed
 * JNI class/method fails here with UnsatisfiedLinkError instead of crashing users.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseShrinkSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyNativeMethodStillBindsToItsCSymbol() {
        assertTrue("libfinsight_llm.so must load", LlamaNative.ensureLoaded())

        LlamaNative.nativeInit(context.applicationInfo.nativeLibraryDir)
        assertTrue(LlamaNative.nativeSystemInfo().isNotBlank())

        val out = LongArray(1)
        assertEquals(LlamaNative.RC_LOAD_FAILED, LlamaNative.nativeLoad("/nonexistent/model.gguf", 512, out))

        val sink = TokenSink { true }
        assertEquals(
            LlamaNative.RC_BAD_HANDLE,
            LlamaNative.nativeGenerate(0L, ByteArray(0), ByteArray(0), 1, 0f, sink),
        )
        LlamaNative.nativeRelease(0L) // no-op on a null handle, but must resolve
    }

    @Test
    fun tokenSinkCallbackKeepsTheNameNativeCodeLooksUp() {
        // nativeGenerate() does GetMethodID(GetObjectClass(sink), "onToken", "([B)Z").
        val sink = TokenSink { true }
        val method = sink.javaClass.getMethod("onToken", ByteArray::class.java)
        assertEquals(java.lang.Boolean.TYPE, method.returnType)
        assertEquals(true, method.invoke(sink, ByteArray(0)))
    }
}
