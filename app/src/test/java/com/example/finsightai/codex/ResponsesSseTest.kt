package com.example.finsightai.codex

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesSseTest {
    private fun delta(t: String) = """data: {"type":"response.output_text.delta","delta":"$t"}""" + "\n\n"

    private fun read(s: String, chunk: Int = Int.MAX_VALUE): SseResult {
        val base = StringReader(s)
        val reader = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int) = base.read(cbuf, off, minOf(len, chunk))
            override fun close() = base.close()
        }
        return ResponsesSse.read(BufferedReader(reader))
    }

    @Test fun concatenatesDeltasAndCompletes() {
        val r = read("event: response.created\ndata: {\"type\":\"response.created\"}\n\n" + delta("Hel") + delta("lo ") + delta("world") +
            "data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        assertEquals("Hello world", r.text); assertTrue(r.completed); assertNull(r.error)
    }

    @Test fun independentOfChunkBoundaries() {
        val stream = delta("a") + ": keep-alive\n\n" + delta("b") + "data: {\"type\":\"response.completed\"}\n\n"
        listOf(1, 2, 3, 7).forEach { assertEquals("ab", read(stream, it).text) }
    }

    @Test fun handlesCrlfAndMissingTrailingBlankLine() {
        val r = read("data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\r\n\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"y\"}")
        assertEquals("xy", r.text); assertFalse(r.completed)
    }

    @Test fun unicodeAndEscapes() {
        assertEquals("₱500 \"ok\"\n", read(delta("₱500 \\\"ok\\\"\\n")).text)
    }

    @Test fun errorEventSurfaces() {
        val r = read(delta("partial") + "data: {\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}\n\n")
        assertEquals("rate limited", r.error)
    }

    @Test fun failedResponseSurfaces() {
        val r = read("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"boom\"}}}\n\n")
        assertEquals("boom", r.error)
    }

    @Test fun ignoresUnknownAndMalformedEvents() {
        val r = read("data: not json\n\ndata: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"zzz\"}\n\n" + delta("ok") + "data: [DONE]\n\n")
        assertEquals("ok", r.text); assertTrue(r.completed)
    }
}
