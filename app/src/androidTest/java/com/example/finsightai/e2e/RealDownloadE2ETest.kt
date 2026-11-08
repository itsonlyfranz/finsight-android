package com.example.finsightai.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.finsightai.llm.CuratedModels
import com.example.finsightai.theme.FinSightAITheme
import com.example.finsightai.ui.aisetup.AiSetupScreen
import com.example.finsightai.ui.aisetup.AiSetupViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * AI Setup UI driving the real HttpModelDownloader against a local HTTP server, so partial-file
 * cleanup on cancel and on failure is checked on disk rather than through a fake.
 */
@RunWith(AndroidJUnit4::class)
class RealDownloadE2ETest {
    private enum class Mode { TRICKLE, DROP }

    @get:Rule val rule = createComposeRule()
    private val spec = CuratedModels.bonsai17b
    private val server = ServerSocket(0)
    @Volatile private var mode = Mode.TRICKLE
    private lateinit var h: TestHarness

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        runCatching {
            val input = it.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = it.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${spec.sizeBytes}\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(8 * 1024) { 1 }
            when (mode) {
                Mode.DROP -> repeat(8) { out.write(chunk) }
                Mode.TRICKLE -> {
                    while (!server.isClosed) {
                        out.write(chunk)
                        out.flush()
                        Thread.sleep(50)
                    }
                }
            }
            out.flush()
        }
    }

    private fun start(mode: Mode) {
        this.mode = mode
        h = TestHarness(downloadBaseUrl = "http://127.0.0.1:${server.localPort}")
        lateinit var vm: AiSetupViewModel
        rule.runOnUiThread { vm = AiSetupViewModel(h.services) }
        rule.setContent { FinSightAITheme { AiSetupScreen(vm) } }
        rule.onNodeWithTag("ai_setup_list").performScrollToNode(hasTestTag("download_${spec.id}"))
        rule.onNodeWithTag("download_${spec.id}").performClick()
    }

    @After fun tearDown() {
        server.close()
        if (::h.isInitialized) h.close()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun cancelledDownloadRemovesItsPartialFile() {
        start(Mode.TRICKLE)
        val part = h.store.partFile(spec)
        rule.waitUntil(10_000) { part.isFile && part.length() > 0 }
        waitForTag("cancel_${spec.id}")

        rule.onNodeWithTag("cancel_${spec.id}").performClick()
        waitForTag("download_${spec.id}")
        rule.waitUntil(10_000) { !part.exists() }
        assertFalse(h.store.isInstalled(spec.id))
    }

    @Test fun failedDownloadShowsErrorAndLeavesNoPartialFile() {
        start(Mode.DROP)
        waitForTag("error_${spec.id}")
        rule.onNodeWithTag("error_${spec.id}").assertTextContains("Network problem", substring = true)
        rule.waitUntil(5_000) { !h.store.partFile(spec).exists() }
        assertFalse(h.store.isInstalled(spec.id))
    }
}
