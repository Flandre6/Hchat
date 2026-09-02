package h.Hchat.hooks.items.script.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ScriptPluginAgentMcpClient(
    private val endpoint: String,
    private val authorization: String,
    private val cancellation: ScriptPluginAgentCancellation? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val requestId = AtomicLong(0L)
    private var sessionId: String = ""
    private var initialized = false
    private var serverInstructions: String = ""

    fun listTools(): String {
        initialize()
        val tools = JSONArray()
        val visitedCursors = HashSet<String>()
        var cursor = ""
        do {
            if (cursor.isNotBlank() && !visitedCursors.add(cursor)) break
            val params = JSONObject().apply {
                if (cursor.isNotBlank()) put("cursor", cursor)
            }
            val result = request("tools/list", params)
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until page.length()) tools.put(page.opt(index))
            cursor = result.optString("nextCursor", "").trim()
        } while (cursor.isNotBlank())
        return JSONObject().apply {
            if (serverInstructions.isNotBlank()) put("instructions", serverInstructions)
            put("tools", tools)
        }.toString()
    }

    fun callTool(name: String, arguments: JSONObject): String {
        require(name.isNotBlank()) { "MCP 工具名为空" }
        initialize()
        val result = request(
            "tools/call",
            JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            }
        )
        return result.toString()
    }

    private fun initialize() {
        if (initialized) return
        val result = request(
            "initialize",
            JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "Hchat Plugin Agent")
                    put("version", "1.0")
                })
            }
        )
        if (result.optString("protocolVersion").isBlank()) {
            throw IllegalStateException("MCP initialize 返回无效")
        }
        serverInstructions = result.optString("instructions", "").trim().take(4_000)
        notify("notifications/initialized", JSONObject())
        initialized = true
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = requestId.incrementAndGet()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return parseResponse(post(payload, expectResponse = true), id).also { response ->
            response.optJSONObject("error")?.let { error ->
                throw IllegalStateException("MCP $method 失败: ${error.optString("message", error.toString())}")
            }
        }.optJSONObject("result") ?: throw IllegalStateException("MCP $method 缺少 result")
    }

    private fun notify(method: String, params: JSONObject) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        post(payload, expectResponse = false)
    }

    private fun post(payload: JSONObject, expectResponse: Boolean): String {
        cancellation?.throwIfCancelled()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", "2024-11-05")
            .apply {
                if (sessionId.isNotBlank()) header("Mcp-Session-Id", sessionId)
                if (authorization.isNotBlank()) header("Authorization", authorization)
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = httpClient.newCall(request)
        cancellation?.bind(call)
        return try {
            call.execute().use { response ->
                cancellation?.throwIfCancelled()
                if (response.header("Mcp-Session-Id").orEmpty().isNotBlank()) {
                    sessionId = response.header("Mcp-Session-Id").orEmpty()
                }
                if (!response.isSuccessful) throw IllegalStateException("MCP HTTP ${response.code}")
                if (!expectResponse) return@use ""
                response.body?.string().orEmpty().ifBlank { throw IllegalStateException("MCP 返回为空") }
            }
        } catch (error: Throwable) {
            if (cancellation?.isCancellation(error) == true) {
                throw java.util.concurrent.CancellationException("Agent 已中断")
            }
            throw error
        } finally {
            cancellation?.unbind(call)
        }
    }

    private fun parseResponse(raw: String, expectedId: Long): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed).takeIf { it.optLong("id", Long.MIN_VALUE) == expectedId }
                ?: throw IllegalStateException("MCP 返回的请求 ID 不匹配")
        }
        val responses = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        return responses.firstOrNull { it.optLong("id", Long.MIN_VALUE) == expectedId }
            ?: throw IllegalStateException("MCP SSE 中没有匹配的请求响应")
    }
}
