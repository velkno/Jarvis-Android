package com.jarvis.agent.ai

/**
 * الواجهة الموحّدة. أي مزوّد (OpenAI / Claude / Gemini) يطبّقها،
 * فتتعامل حلقة الوكيل مع الذكاء الاصطناعي دون أن تعرف أي واجهة API بالضبط.
 */
interface LlmProvider {
    /**
     * يرسل المحادثة + الأدوات المتاحة، ويعيد إمّا نصاً وإمّا طلب استدعاء أداة.
     */
    suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>
    ): LlmResult

    companion object {
        fun create(config: ProviderConfig, http: HttpClientFactory): LlmProvider =
            when (config.provider) {
                Provider.OPENAI -> OpenAiProvider(config, http)
                Provider.ANTHROPIC -> AnthropicProvider(config, http)
                Provider.GEMINI -> GeminiProvider(config, http)
            }
    }
}
