package com.jarvis.agent.ai

/** رسالة في المحادثة بصيغة موحّدة بين كل المزوّدين. */
data class ChatMessage(
    val role: Role,
    val content: String,
    // عند role=TOOL: ناتج تنفيذ الأداة ومعرّفها
    val toolCallId: String? = null,
    // عند role=ASSISTANT: استدعاءات أدوات طلبها النموذج
    val toolCalls: List<ToolCall> = emptyList(),
    /** Screen image attached only to the most recent screen observation. */
    val image: ScreenImage? = null
)

/** A JPEG screenshot stored in memory for one multimodal model request. */
data class ScreenImage(
    val jpegBase64: String,
    val width: Int,
    val height: Int
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** تعريف أداة يمكن للنموذج استدعاؤها. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** مخطط JSON للمعاملات (object). */
    val parameters: Map<String, ParamSpec>,
    val required: List<String> = emptyList()
)

data class ParamSpec(
    val type: String,           // "string" | "integer" | "number" | "boolean"
    val description: String,
    val enumValues: List<String>? = null
)

/** طلب استدعاء أداة صادر عن النموذج. */
data class ToolCall(
    val id: String,
    val name: String,
    /** معاملات بصيغة JSON نصية. */
    val argumentsJson: String,
    /** توقيع تفكير Gemini (thoughtSignature) يجب إعادته كما هو في الدورة التالية. */
    val signature: String? = null
)

/** ناتج دورة واحدة من النموذج. */
data class LlmResult(
    val assistantText: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String
)

/** المزوّدون المدعومون. */
enum class Provider(val displayName: String) {
    OPENAI("OpenAI (ChatGPT)"),
    ANTHROPIC("Claude"),
    GEMINI("Gemini");
}

/** إعدادات مزوّد واحد. */
data class ProviderConfig(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    val baseUrlOverride: String? = null
) {
    val baseUrl: String
        get() = baseUrlOverride ?: when (provider) {
            Provider.OPENAI -> "https://api.openai.com/v1"
            Provider.ANTHROPIC -> "https://api.anthropic.com/v1"
            Provider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        }

    companion object {
        /** نماذج افتراضية مقترحة لكل مزوّد. */
        fun defaultModel(p: Provider): String = when (p) {
            Provider.OPENAI -> "gpt-6.1-sol"
            Provider.ANTHROPIC -> "claude-sonnet-4-5"
            Provider.GEMINI -> "gemini-4-argon"
        }

        /** نماذج مقترحة تظهر كأزرار سريعة في شاشة المفاتيح. */
        fun suggestedModels(p: Provider): List<String> = when (p) {
            Provider.OPENAI -> listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-4o")
            Provider.ANTHROPIC -> listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5")
            Provider.GEMINI -> listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite")
        }
    }
}
