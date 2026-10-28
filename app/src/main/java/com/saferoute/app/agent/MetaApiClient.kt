package com.saferoute.app.agent

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Minimal Meta Model API (Chat Completions) client. Used only while online, never on the alert path. */
class MetaApiClient(private val apiKey: String) {

    data class Usage(val promptTokens: Int, val completionTokens: Int)

    data class Reply(val message: JSONObject, val toolCalls: JSONArray, val usage: Usage)

    /** One retry: the API returned transient 400s that did not reproduce on retry (VALIDATION V25). */
    fun chat(messages: JSONArray, tools: JSONArray): Reply = try {
        chatOnce(messages, tools)
    } catch (e: HttpException) {
        if (e.code == 400 || e.code == 429 || e.code >= 500) { Thread.sleep(2_000); chatOnce(messages, tools) } else throw e
    }

    class HttpException(val code: Int, body: String) : IllegalStateException("Meta API HTTP $code: ${body.take(300)}")

    private fun chatOnce(messages: JSONArray, tools: JSONArray): Reply {
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("tools", tools)
            // Reasoning tokens count toward max_tokens; small limits return empty content (VALIDATION V11b).
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", 0)
        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) throw HttpException(code, text)
            val json = JSONObject(text)
            val message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val usage = json.optJSONObject("usage")
            return Reply(
                message = message,
                toolCalls = message.optJSONArray("tool_calls") ?: JSONArray(),
                usage = Usage(usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0),
            )
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val URL_CHAT = "https://api.meta.ai/v1/chat/completions"
        const val MODEL = "muse-spark-1.3"
        const val MAX_TOKENS = 2048
    }
}
