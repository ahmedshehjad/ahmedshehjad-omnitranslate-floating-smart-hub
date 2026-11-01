package com.example.engine

import com.google.mlkit.nl.translate.TranslateLanguage

data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flagEmoji: String
)

object LanguageCatalog {
    val languages = listOf(
        SupportedLanguage(TranslateLanguage.ENGLISH, "English", "English", "🇺🇸"),
        SupportedLanguage(TranslateLanguage.BENGALI, "Bengali", "বাংলা", "🇧🇩"),
        SupportedLanguage(TranslateLanguage.SPANISH, "Spanish", "Español", "🇪🇸"),
        SupportedLanguage(TranslateLanguage.HINDI, "Hindi", "हिन्दी", "🇮🇳"),
        SupportedLanguage(TranslateLanguage.ARABIC, "Arabic", "العربية", "🇸🇦"),
        SupportedLanguage(TranslateLanguage.FRENCH, "French", "Français", "🇫🇷"),
        SupportedLanguage(TranslateLanguage.GERMAN, "German", "Deutsch", "🇩🇪"),
        SupportedLanguage(TranslateLanguage.JAPANESE, "Japanese", "日本語", "🇯🇵"),
        SupportedLanguage(TranslateLanguage.CHINESE, "Chinese", "中文", "🇨🇳"),
        SupportedLanguage(TranslateLanguage.PORTUGUESE, "Portuguese", "Português", "🇧🇷"),
        SupportedLanguage(TranslateLanguage.RUSSIAN, "Russian", "Русский", "🇷🇺"),
        SupportedLanguage(TranslateLanguage.ITALIAN, "Italian", "Italiano", "🇮🇹"),
        SupportedLanguage(TranslateLanguage.TURKISH, "Turkish", "Türkçe", "🇹🇷"),
        SupportedLanguage(TranslateLanguage.KOREAN, "Korean", "한국어", "🇰🇷"),
        SupportedLanguage(TranslateLanguage.INDONESIAN, "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        SupportedLanguage(TranslateLanguage.URDU, "Urdu", "اردو", "🇵🇰"),
        SupportedLanguage(TranslateLanguage.VIETNAMESE, "Vietnamese", "Tiếng Việt", "🇻🇳")
    )

    fun getByCode(code: String): SupportedLanguage {
        return languages.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: SupportedLanguage(code, code.uppercase(), code.uppercase(), "🌐")
    }
}
