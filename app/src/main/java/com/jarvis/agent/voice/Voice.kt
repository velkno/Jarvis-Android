package com.jarvis.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * الصوت: تحويل الكلام إلى نص (STT) والنص إلى كلام (TTS).
 * اللغة الافتراضية ar-MA (العربية المغربية) مع رجوع للعربية العامة.
 */
class Voice(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var language: String = "ar-MA"

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.forLanguageTag(language))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("ar"))
                }
                ttsReady = true
            }
        }
    }

    fun isRecognitionAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * يبدأ الاستماع. onPartial للنص الجزئي أثناء الكلام، onResult للنص النهائي.
     * يجب استدعاؤه من الخيط الرئيسي.
     */
    fun listen(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (!isRecognitionAvailable()) {
            onError("التعرف على الكلام غير متاح على هذا الهاتف.")
            return
        }
        recognizer?.destroy()
        recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            onError("تعذّر بدء التعرف الصوتي: ${e.message}")
            return
        }
        recognizer?.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onResult(text) else onError("لم أسمع شيئاً")
                }
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }
                override fun onError(error: Int) { onError(errorText(error)) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            onError("تعذّر تشغيل الميكروفون: ${e.message}")
        }
    }

    fun stopListening() { recognizer?.stopListening() }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "لم أفهم، أعد من فضلك"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "لم أسمع شيئاً"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "صلاحية الميكروفون غير ممنوحة"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "مشكلة في الشبكة"
        else -> "خطأ في التعرف على الصوت ($code)"
    }
}
