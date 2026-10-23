package com.jarvis.agent.agent

import android.content.Context
import com.jarvis.agent.ai.HttpClientFactory
import com.jarvis.agent.ai.LlmProvider
import com.jarvis.agent.control.DeviceTools
import com.jarvis.agent.data.KeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** سطر في سجل المحادثة المعروض. */
data class LogLine(val kind: Kind, val text: String) {
    enum class Kind { USER, AGENT, ACTION, OBSERVATION, ERROR }
}

/**
 * جلسة وكيل واحدة مشتركة بين شاشة الدردشة والفقاعة العائمة.
 */
object AgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClientFactory()
    private var tools: DeviceTools? = null

    private var loop: AgentLoop? = null
    private var loopKey: String? = null
    private var job: Job? = null
    private val generation = AtomicLong(0)

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** يُستدعى عند انتهاء المهمة برسالة نهائية (للنطق مثلاً). */
    var onFinalReply: ((String) -> Unit)? = null

    private fun append(kind: LogLine.Kind, text: String) {
        _log.update { it + LogLine(kind, text) }
    }

    /** يرسل أمراً للوكيل. */
    fun send(
        context: Context, text: String,
        onFinish: ((success: Boolean, summary: String) -> Unit)? = null
    ) {
        if (text.isBlank() || _busy.value) {
            onFinish?.invoke(false, "Agent busy or task empty")
            return
        }
        if (tools == null) tools = DeviceTools(context.applicationContext)
        val cfg = KeyStore(context.applicationContext).activeConfig()
        if (cfg == null) {
            append(LogLine.Kind.ERROR, "أضف مفتاح API واختر مزوّداً من الإعدادات أولاً.")
            onFinish?.invoke(false, "AI API key not configured")
            return
        }
        // أعد بناء الحلقة إن تغيّر المزوّد/النموذج/المفتاح
        val key = "${cfg.provider}|${cfg.model}|${cfg.apiKey.hashCode()}"
        if (loop == null || loopKey != key) {
            loop = AgentLoop(LlmProvider.create(cfg, http), tools!!)
            loopKey = key
        }
        append(LogLine.Kind.USER, text)
        _busy.value = true
        val requestId = generation.incrementAndGet()
        val activeLoop = loop!!

        job = scope.launch {
            try {
                activeLoop.run(text) { ev ->
                    if (generation.get() != requestId) throw CancellationException("Task stopped")
                    when (ev) {
                        is AgentLoop.Event.Thinking -> append(LogLine.Kind.AGENT, ev.text)
                        is AgentLoop.Event.Action -> append(
                            LogLine.Kind.ACTION,
                            if (ev.tool == "type_text" || ev.tool == "clipboard_write")
                                "${ev.tool} [نص مخفي لحماية الخصوصية]"
                            else "${ev.tool} ${ev.args.take(180)}"
                        )
                        is AgentLoop.Event.Observation -> append(
                            LogLine.Kind.OBSERVATION, ev.result.take(400)
                        )
                        is AgentLoop.Event.Done -> {
                            append(LogLine.Kind.AGENT, "✓ ${ev.summary}")
                            onFinish?.invoke(true, ev.summary)
                            withContext(Dispatchers.Main) {
                                if (generation.get() == requestId) onFinalReply?.invoke(ev.summary)
                            }
                        }
                        is AgentLoop.Event.Failed -> {
                            append(LogLine.Kind.ERROR, ev.error)
                            onFinish?.invoke(false, ev.error)
                        }
                    }
                }
            } catch (e: CancellationException) {
                onFinish?.invoke(false, "Task stopped")
                throw e
            } catch (e: Exception) {
                if (generation.get() == requestId) {
                    append(LogLine.Kind.ERROR, e.message ?: "فشل غير متوقع")
                    onFinish?.invoke(false, e.message ?: "Unexpected failure")
                }
            } finally {
                // An older cancelled task must never mark a newer task as idle.
                if (generation.get() == requestId) _busy.value = false
            }
        }
    }

    /** إيقاف المهمة الجارية فوراً. */
    fun stop() {
        if (!_busy.value) return
        generation.incrementAndGet()
        job?.cancel()
        job = null
        // Old provider/tool history is discarded, not mutated while still being cancelled.
        loop = null
        loopKey = null
        _busy.value = false
        append(LogLine.Kind.ERROR, "تم إيقاف المهمة.")
    }

    /** رسالة تنبيه للمستخدم دون إرسالها للوكيل. */
    fun note(text: String) = append(LogLine.Kind.ERROR, text)

    /** اختبار سريع للمفتاح والنموذج دون أدوات. */
    suspend fun testConnection(cfg: com.jarvis.agent.ai.ProviderConfig): String = try {
        val r = LlmProvider.create(cfg, http).chat(
            "أجب بكلمة واحدة.",
            listOf(com.jarvis.agent.ai.ChatMessage(com.jarvis.agent.ai.Role.USER, "قل: يعمل")),
            emptyList()
        )
        "✓ الاتصال ناجح (${cfg.model}): ${r.assistantText?.take(40) ?: "رد فارغ"}"
    } catch (e: Exception) {
        "✗ ${e.message}"
    }

    fun clear() {
        loop?.reset()
        _log.value = emptyList()
    }
}
