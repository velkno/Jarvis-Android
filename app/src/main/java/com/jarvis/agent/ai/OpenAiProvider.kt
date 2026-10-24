package com.jarvis.agent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** يتكلم واجهة OpenAI Chat Completions (tool calling). */
class OpenAiProvider(
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
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system"); put("content", system)
                })
                messages.forEach { m ->
                    add(encodeMessage(m))
                    // Chat Completions tool messages accept text only. The subsequent
                    // user message carries the image and keeps the tool_call_id valid.
                    if (m.role == Role.TOOL && m.image != null) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "صورة الشاشة الحية الملتقطة بواسطة read_screen:")
                                })
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,${m.image.jpegBase64}")
                                        put("detail", "high")
                                    }
                                })
                            }
                        })
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(encodeTool(it)) } }
            }
        }

        val request = Request.Builder()
            .url("${cfg.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody(mediaType))
            .build()

        val resp = http.execute(request)
        val text = resp.body
            if (!resp.isSuccessful) error("OpenAI ${resp.code}: $text")
            parseResponse(json.parseToJsonElement(text).jsonObject)

    }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        when (m.role) {
            Role.USER -> { put("role", "user"); put("content", m.content) }
            Role.ASSISTANT -> {
                put("role", "assistant")
                if (m.content.isEmpty()) put("content", JsonNull) else put("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        m.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("id", tc.id); put("type", "function")
                                putJsonObject("function") {
                                    put("name", tc.name); put("arguments", tc.argumentsJson)
                                }
                            })
                        }
                    }
                }
            }
            Role.TOOL -> {
                put("role", "tool"); put("tool_call_id", m.toolCallId ?: "")
                put("content", m.content)
            }
            Role.SYSTEM -> { put("role", "system"); put("content", m.content) }
        }
    }

    private fun encodeTool(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name); put("description", t.description)
            put("parameters", encodeSchema(t))
        }
    }

    private fun encodeSchema(t: ToolSpec): JsonObject = buildJsonObject {
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

    private fun parseResponse(obj: JsonObject): LlmResult {
        val choice = obj["choices"]!!.jsonArray[0].jsonObject
        val msg = choice["message"]!!.jsonObject
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop"
        val content = msg["content"]?.jsonPrimitive?.contentOrNull
        val calls = msg["tool_calls"]?.jsonArray?.map { el ->
            val c = el.jsonObject
            val fn = c["function"]!!.jsonObject
            ToolCall(
                id = c["id"]!!.jsonPrimitive.content,
                name = fn["name"]!!.jsonPrimitive.content,
                argumentsJson = fn["arguments"]!!.jsonPrimitive.content
            )
        } ?: emptyList()
        return LlmResult(content, calls, finish)
    }
}
