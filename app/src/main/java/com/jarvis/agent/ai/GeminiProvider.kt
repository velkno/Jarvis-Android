package com.jarvis.agent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** يتكلم واجهة Google Gemini generateContent (function calling). */
class GeminiProvider(
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
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") { mergeToolResults(messages).forEach { add(it) } }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { add(encodeTool(it)) }
                        }
                    })
                }
            }
        }

        val url = "${cfg.baseUrl}/models/${cfg.model}:generateContent?key=${cfg.apiKey}"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(mediaType))
            .build()

        val resp = http.execute(request)
        val text = resp.body
            if (!resp.isSuccessful) error(GeminiErrors.explain(resp.code, text, cfg.model))
            parseResponse(json.parseToJsonElement(text).jsonObject)

    }

    /** Gemini: كل functionResponse لدورة واحدة تُجمع في محتوى user واحد. */
    private fun mergeToolResults(messages: List<ChatMessage>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            if (messages[i].role == Role.TOOL) {
                val parts = mutableListOf<JsonObject>()
                while (i < messages.size && messages[i].role == Role.TOOL) {
                    val t = messages[i]
                    parts.add(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", t.toolCallId ?: "tool")
                            putJsonObject("response") { put("result", t.content) }
                        }
                    })
                    // Inline JPEG as a sibling part of this tool response.
                    t.image?.let { img ->
                        parts.add(buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", img.jpegBase64)
                            }
                        })
                    }
                    i++
                }
                out.add(buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(parts))
                })
            } else {
                out.add(encodeMessage(messages[i])); i++
            }
        }
        return out
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        when (m.role) {
            Role.USER, Role.SYSTEM -> {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", m.content) }) }
            }
            Role.ASSISTANT -> {
                put("role", "model")
                putJsonArray("parts") {
                    if (m.content.isNotEmpty())
                        add(buildJsonObject { put("text", m.content) })
                    m.toolCalls.forEach { tc ->
                        add(buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", tc.name)
                                put("args", json.parseToJsonElement(
                                    tc.argumentsJson.ifEmpty { "{}" }))
                            }
                            // نماذج Gemini الحديثة ترفض الطلب إن لم يُعَد التوقيع
                            tc.signature?.let { put("thoughtSignature", it) }
                        })
                    }
                }
            }
            Role.TOOL -> {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", m.toolCallId ?: "tool")
                            putJsonObject("response") { put("result", m.content) }
                        }
                    })
                }
            }
        }
    }

    private fun encodeTool(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name); put("description", t.description)
        putJsonObject("parameters") {
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
        val candidate = obj["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?: return LlmResult(null, emptyList(), "stop")
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())
        val sb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var idx = 0
        parts.forEach { el ->
            val p = el.jsonObject
            p["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
            p["functionCall"]?.jsonObject?.let { fc ->
                calls.add(
                    ToolCall(
                        id = "gemini_${idx++}_${fc["name"]!!.jsonPrimitive.content}",
                        name = fc["name"]!!.jsonPrimitive.content,
                        argumentsJson = (fc["args"] ?: JsonObject(emptyMap())).toString(),
                        signature = p["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    )
                )
            }
        }
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
        return LlmResult(sb.toString().ifEmpty { null }, calls, finish)
    }
}

/** يحوّل أخطاء Gemini الشائعة إلى رسائل مفهومة. */
object GeminiErrors {
    fun explain(code: Int, body: String, model: String): String = when {
        code == 404 -> "النموذج \"$model\" غير موجود أو غير متاح لمفتاحك. تأكد من الاسم (مثلاً gemini-4-argon)."
        code == 403 || body.contains("PERMISSION_DENIED") ->
            "مفتاحك لا يملك صلاحية هذا النموذج. نماذج مثل gemini-4-argon تحتاج تفعيل الفوترة (Paid tier) في AI Studio — اشتراك Gemini Pro في التطبيق لا يشمل الـ API."
        code == 429 -> "تجاوزت حد الاستخدام (Rate limit/Quota). انتظر قليلاً أو فعّل الفوترة في AI Studio."
        code == 400 && body.contains("API_KEY_INVALID") -> "مفتاح Gemini غير صالح. انسخه من جديد من aistudio.google.com."
        else -> "Gemini $code: ${body.take(500)}"
    }
}
