package com.jarvis.agent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Responses API tool calling for GPT-6 reasoning models.
 *
 * Uses store=false and replays model reasoning with encrypted_content in order
 * to preserve the tool-use chain without keeping a server-side conversation.
 */
class OpenAiResponsesProvider(
    private val cfg: ProviderConfig,
    private val http: HttpClientFactory
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json".toMediaType()

    // Reasoning objects returned for each assistant tool-call round.
    private val reasoningByCallId = mutableMapOf<String, List<JsonObject>>()

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>
    ): LlmResult = withContext(Dispatchers.IO) {
        // A new task starts with a fresh USER message (old task histories are compacted).
        if (messages.size == 1) reasoningByCallId.clear()

        val body = buildJsonObject {
            put("model", cfg.model)
            put("instructions", system)
            put("store", false)
            putJsonArray("include") { add("reasoning.encrypted_content") }
            putJsonArray("input") { messages.forEach { encodeMessage(it).forEach(::add) } }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(encodeTool(it)) } }
                // UI gestures must be sequential and verified rather than speculative.
                put("parallel_tool_calls", false)
            }
        }

        val request = Request.Builder()
            .url(cfg.baseUrl + "/responses")
            .header("Authorization", "Bearer " + cfg.apiKey)
            .post(body.toString().toRequestBody(media))
            .build()

        val response = http.execute(request)
        val payload = response.body
        if (!response.isSuccessful) {
            error("OpenAI Responses HTTP " + response.code +
                ". Check model availability, API billing, and tool permissions.")
        }
        parseResponse(json.parseToJsonElement(payload).jsonObject)
    }

    private fun encodeMessage(m: ChatMessage): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        when (m.role) {
            Role.USER, Role.SYSTEM -> result.add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "input_text"); put("text", m.content)
                    })
                }
            })
            Role.ASSISTANT -> {
                val firstId = m.toolCalls.firstOrNull()?.id
                if (firstId != null) reasoningByCallId[firstId]?.let { result.addAll(it) }
                if (m.content.isNotBlank()) {
                    result.add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "output_text"); put("text", m.content)
                            })
                        }
                    })
                }
                m.toolCalls.forEach { call ->
                    result.add(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", call.id)
                        put("name", call.name)
                        put("arguments", call.argumentsJson)
                    })
                }
            }
            Role.TOOL -> {
                result.add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", m.toolCallId.orEmpty())
                    put("output", m.content)
                })
                m.image?.let { img ->
                    result.add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", "صورة الشاشة الحالية من read_screen")
                            })
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:image/jpeg;base64," + img.jpegBase64)
                                put("detail", "high")
                            })
                        }
                    })
                }
            }
        }
        return result
    }

    private fun encodeTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", spec.name)
        put("description", spec.description)
        put("strict", false) // Existing optional arguments are intentionally optional.
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                spec.parameters.forEach { (name, p) ->
                    putJsonObject(name) {
                        put("type", p.type)
                        put("description", p.description)
                        p.enumValues?.let { choices ->
                            putJsonArray("enum") { choices.forEach { add(it) } }
                        }
                    }
                }
            }
            putJsonArray("required") { spec.required.forEach { add(it) } }
            put("additionalProperties", false)
        }
    }

    private fun parseResponse(body: JsonObject): LlmResult {
        val status = body["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val output = body["output"]?.jsonArray ?: JsonArray(emptyList())
        val calls = mutableListOf<ToolCall>()
        val text = StringBuilder()
        val reasoning = mutableListOf<JsonObject>()
        output.forEach { item ->
            val o = item.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "reasoning" -> reasoning.add(o)
                "function_call" -> {
                    val id = o["call_id"]?.jsonPrimitive?.contentOrNull
                    val name = o["name"]?.jsonPrimitive?.contentOrNull
                    if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                        calls.add(ToolCall(
                            id = id,
                            name = name,
                            argumentsJson = o["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                        ))
                    }
                }
                "message" -> o["content"]?.jsonArray?.forEach { block ->
                    val c = block.jsonObject
                    if (c["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        text.append(c["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
            }
        }
        if (calls.isNotEmpty() && reasoning.isNotEmpty()) {
            reasoningByCallId[calls[0].id] = reasoning
            if (reasoningByCallId.size > 40) reasoningByCallId.clear()
        }
        if (status == "failed" || status == "incomplete") {
            error("OpenAI response " + status + " (try another model or a smaller task).")
        }
        return LlmResult(text.toString().ifBlank { null }, calls, status)
    }
}
