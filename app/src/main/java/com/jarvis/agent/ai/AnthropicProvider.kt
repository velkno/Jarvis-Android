package com.jarvis.agent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** يتكلم واجهة Anthropic Messages (tool use). */
class AnthropicProvider(
    private val cfg: ProviderConfig,
    private val http: HttpClientFactory
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>
    ): LlmResult = withContext(Dispatchers.IO) {

        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", 4096)
            put("system", system)
            putJsonArray("messages") { mergeToolResults(messages).forEach { add(it) } }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(encodeTool(it)) } }
            }
        }

        val request = Request.Builder()
            .url("${cfg.baseUrl}/messages")
            .addHeader("x-api-key", cfg.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(mediaType))
            .build()

        http.client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Anthropic ${resp.code}: $text")
            parseResponse(json.parseToJsonElement(text).jsonObject)
        }
    }

    /** Claude يشترط أن تأتي كل نتائج الأدوات لدورة واحدة في رسالة user واحدة. */
    private fun mergeToolResults(messages: List<ChatMessage>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role == Role.TOOL) {
                val blocks = mutableListOf<JsonObject>()
                while (i < messages.size && messages[i].role == Role.TOOL) {
                    val t = messages[i]
                    blocks.add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", t.toolCallId ?: "")
                        if (t.image == null) {
                            put("content", t.content)
                        } else {
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", t.content) })
                                add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", t.image.jpegBase64)
                                    }
                                })
                            }
                        }
                    })
                    i++
                }
                out.add(buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(blocks))
                })
            } else {
                out.add(encodeMessage(m)); i++
            }
        }
        return out
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        when (m.role) {
            Role.USER -> { put("role", "user"); put("content", m.content) }
            Role.TOOL -> {
                // نتائج الأدوات تُرسل كرسالة user تحوي tool_result
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolCallId ?: "")
                        put("content", m.content)
                    })
                }
            }
            Role.ASSISTANT -> {
                put("role", "assistant")
                putJsonArray("content") {
                    if (m.content.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "text"); put("text", m.content)
                        })
                    }
                    m.toolCalls.forEach { tc ->
                        add(buildJsonObject {
                            put("type", "tool_use"); put("id", tc.id)
                            put("name", tc.name)
                            put("input", json.parseToJsonElement(
                                tc.argumentsJson.ifEmpty { "{}" }))
                        })
                    }
                }
            }
            Role.SYSTEM -> { put("role", "user"); put("content", m.content) }
        }
    }

    private fun encodeTool(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name); put("description", t.description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                t.parameters.forEach { (name, p) ->
                    put(name, buildJsonObject {
                        put("type", p.type); put("description", p.description)
                        p.enumValues?.let { ev ->
                            putJsonArray("enum") { ev.forEach { add(it) } }
                        }
                    })
                }
            }
            putJsonArray("required") { t.required.forEach { add(it) } }
        }
    }

    private fun parseResponse(obj: JsonObject): LlmResult {
        val stopReason = obj["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
        val blocks = obj["content"]!!.jsonArray
        val sb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        blocks.forEach { el ->
            val b = el.jsonObject
            when (b["type"]?.jsonPrimitive?.content) {
                "text" -> sb.append(b["text"]?.jsonPrimitive?.content.orEmpty())
                "tool_use" -> calls.add(
                    ToolCall(
                        id = b["id"]!!.jsonPrimitive.content,
                        name = b["name"]!!.jsonPrimitive.content,
                        argumentsJson = b["input"]!!.jsonObject.toString()
                    )
                )
            }
        }
        return LlmResult(sb.toString().ifEmpty { null }, calls, stopReason)
    }
}
