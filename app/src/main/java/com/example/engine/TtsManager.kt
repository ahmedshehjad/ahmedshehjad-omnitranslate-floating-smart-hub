package com.example.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeech: Pair<String, String>? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setSpeechRate(1.0f)
            pendingSpeech?.let { (text, langCode) ->
                speak(text, langCode)
                pendingSpeech = null
            }
        } else {
            Log.e("TtsManager", "TTS initialization failed: $status")
        }
    }

    fun speak(text: String, langCode: String, speed: Float = 1.0f) {
        if (text.isBlank()) return
        if (!isInitialized) {
            pendingSpeech = text to langCode
            return
        }

        val locale = when (langCode.lowercase()) {
            "bn" -> Locale("bn", "BD")
            "en" -> Locale.US
            "es" -> Locale("es", "ES")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "ja" -> Locale.JAPANESE
            "zh" -> Locale.CHINESE
            "pt" -> Locale("pt", "BR")
            "ru" -> Locale("ru", "RU")
            "it" -> Locale.ITALIAN
            "tr" -> Locale("tr", "TR")
            "ko" -> Locale.KOREAN
            else -> Locale(langCode)
        }

        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to default locale
            tts?.setLanguage(Locale.getDefault())
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "omni_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
