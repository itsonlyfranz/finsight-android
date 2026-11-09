package com.example.finsightai.codex

import java.io.BufferedReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Outcome of reading one Responses SSE stream. */
internal data class SseResult(val text: String, val completed: Boolean, val error: String?)

/**
 * Reads a Responses-API server-sent-event stream, concatenating `response.output_text.delta`
 * deltas. Tolerant of `event:` lines, comments, CRLF, split chunks (it works on lines, so it is
 * independent of how the bytes were chunked), `[DONE]`, and unknown event types.
 */
internal object ResponsesSse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(reader: BufferedReader, onDelta: (String) -> Unit = {}): SseResult {
        val text = StringBuilder()
        val data = StringBuilder()
        var completed = false
        var error: String? = null

        fun dispatch() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.setLength(0)
            if (payload.trim() == "[DONE]") {
                completed = true
                return
            }
            val obj = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return }
            when (obj.str("type")) {
                "response.output_text.delta" -> obj.str("delta")?.let {
                    text.append(it)
                    onDelta(it)
                }
                "response.completed", "response.done" -> completed = true
                "response.incomplete" -> completed = true
                "response.failed" -> error = errorMessage(obj["response"]?.let { (it as? JsonObject)?.get("error") })
                    ?: "The Codex response failed."
                "error" -> error = errorMessage(obj["error"] ?: obj) ?: "Codex reported an error."
            }
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                else -> Unit // event:, id:, retry:
            }
            if (error != null || completed) break
        }
        dispatch() // stream ended without a trailing blank line
        return SseResult(text.toString(), completed, error)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun errorMessage(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.str("message") ?: element.str("code") ?: element.str("type")
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }
}
