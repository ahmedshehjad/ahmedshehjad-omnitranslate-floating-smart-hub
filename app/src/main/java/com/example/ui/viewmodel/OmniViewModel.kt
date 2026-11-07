package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ClipboardItem
import com.example.engine.LanguageCatalog
import com.example.engine.OcrEngine
import com.example.engine.SupportedLanguage
import com.example.engine.TranslationEngine
import com.example.engine.TtsManager
import com.example.service.FloatingBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OmniViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val clipboardDao = db.clipboardDao()
    private val ttsManager = TtsManager(application)

    // Bubble running state (from service)
    val isBubbleRunning: StateFlow<Boolean> = FloatingBubbleService.isRunning

    // Translation State
    private val _sourceLang = MutableStateFlow(LanguageCatalog.languages[0]) // English
    val sourceLang: StateFlow<SupportedLanguage> = _sourceLang.asStateFlow()

    private val _targetLang = MutableStateFlow(LanguageCatalog.languages[1]) // Bengali
    val targetLang: StateFlow<SupportedLanguage> = _targetLang.asStateFlow()

    private val _sourceText = MutableStateFlow("")
    val sourceText: StateFlow<String> = _sourceText.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()

    private val _detectedLanguageCode = MutableStateFlow<String?>(null)
    val detectedLanguageCode: StateFlow<String?> = _detectedLanguageCode.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    private val _translationError = MutableStateFlow<String?>(null)
    val translationError: StateFlow<String?> = _translationError.asStateFlow()

    // Clipboard History State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilterTag = MutableStateFlow("All")
    val selectedFilterTag: StateFlow<String> = _selectedFilterTag.asStateFlow()

    val clipboardHistory: StateFlow<List<ClipboardItem>> = combine(
        clipboardDao.getAllItems(),
        _searchQuery,
        _selectedFilterTag
    ) { items, query, tag ->
        var filtered = items
        if (tag == "Favorites") {
            filtered = filtered.filter { it.isFavorite }
        } else if (tag != "All") {
            filtered = filtered.filter { it.tag.equals(tag, ignoreCase = true) }
        }

        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.originalText.contains(query, ignoreCase = true) ||
                        it.translatedText.contains(query, ignoreCase = true)
            }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Offline Models State
    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    private val _downloadingModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadingModels: StateFlow<Set<String>> = _downloadingModels.asStateFlow()

    // OCR State
    private val _ocrImageUri = MutableStateFlow<Uri?>(null)
    val ocrImageUri: StateFlow<Uri?> = _ocrImageUri.asStateFlow()

    private val _ocrExtractedText = MutableStateFlow("")
    val ocrExtractedText: StateFlow<String> = _ocrExtractedText.asStateFlow()

    private val _isOcrProcessing = MutableStateFlow(false)
    val isOcrProcessing: StateFlow<Boolean> = _isOcrProcessing.asStateFlow()

    // Settings
    private val _ttsSpeed = MutableStateFlow(1.0f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    init {
        refreshDownloadedModels()
    }

    fun setSourceLanguage(lang: SupportedLanguage) {
        _sourceLang.value = lang
    }

    fun setTargetLanguage(lang: SupportedLanguage) {
        _targetLang.value = lang
    }

    fun swapLanguages() {
        val temp = _sourceLang.value
        _sourceLang.value = _targetLang.value
        _targetLang.value = temp
        val tempText = _sourceText.value
        _sourceText.value = _translatedText.value
        _translatedText.value = tempText
    }

    fun updateSourceText(text: String) {
        _sourceText.value = text
        if (text.isNotBlank()) {
            detectLanguageAuto(text)
        } else {
            _detectedLanguageCode.value = null
            _translatedText.value = ""
        }
    }

    private fun detectLanguageAuto(text: String) {
        viewModelScope.launch {
            val detected = TranslationEngine.identifyLanguage(text)
            _detectedLanguageCode.value = detected
        }
    }

    fun applyDetectedSource() {
        val code = _detectedLanguageCode.value ?: return
        val found = LanguageCatalog.getByCode(code)
        _sourceLang.value = found
    }

    fun performTranslation(saveToHistory: Boolean = true) {
        val text = _sourceText.value.trim()
        if (text.isBlank()) return

        _isTranslating.value = true
        _translationError.value = null

        viewModelScope.launch {
            val result = TranslationEngine.translate(
                text = text,
                sourceLang = _sourceLang.value.code,
                targetLang = _targetLang.value.code
            )

            _isTranslating.value = false
            result.onSuccess { translated ->
                _translatedText.value = translated
                if (saveToHistory) {
                    clipboardDao.insert(
                        ClipboardItem(
                            originalText = text,
                            translatedText = translated,
                            sourceLanguage = _sourceLang.value.code,
                            targetLanguage = _targetLang.value.code,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }.onFailure { err ->
                _translationError.value = err.localizedMessage ?: "Translation failed"
            }
        }
    }

    fun speak(text: String, langCode: String) {
        ttsManager.speak(text, langCode, _ttsSpeed.value)
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
    }

    // Clipboard History Management
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterTag(tag: String) {
        _selectedFilterTag.value = tag
    }

    fun toggleFavorite(item: ClipboardItem) {
        viewModelScope.launch {
            clipboardDao.update(item.copy(isFavorite = !item.isFavorite))
        }
    }

    fun deleteClipboardItem(item: ClipboardItem) {
        viewModelScope.launch {
            clipboardDao.delete(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            clipboardDao.clearAll()
        }
    }

    fun saveDirectClipboard(original: String, translated: String, tag: String = "General") {
        viewModelScope.launch {
            clipboardDao.insert(
                ClipboardItem(
                    originalText = original,
                    translatedText = translated,
                    sourceLanguage = _sourceLang.value.code,
                    targetLanguage = _targetLang.value.code,
                    tag = tag
                )
            )
        }
    }

    // Offline Model Management
    fun refreshDownloadedModels() {
        viewModelScope.launch {
            _downloadedModels.value = TranslationEngine.getDownloadedModels()
        }
    }

    fun downloadModel(langCode: String) {
        viewModelScope.launch {
            _downloadingModels.value = _downloadingModels.value + langCode
            TranslationEngine.downloadModel(langCode)
            _downloadingModels.value = _downloadingModels.value - langCode
            refreshDownloadedModels()
        }
    }

    fun deleteModel(langCode: String) {
        viewModelScope.launch {
            TranslationEngine.deleteModel(langCode)
            refreshDownloadedModels()
        }
    }

    // OCR Processing
    fun setOcrImageUri(uri: Uri?, context: Context) {
        _ocrImageUri.value = uri
        if (uri != null) {
            _isOcrProcessing.value = true
            viewModelScope.launch {
                val result = OcrEngine.recognizeTextFromUri(context, uri)
                _isOcrProcessing.value = false
                result.onSuccess { text ->
                    _ocrExtractedText.value = text
                }.onFailure { err ->
                    _ocrExtractedText.value = "OCR Failed: ${err.localizedMessage}"
                }
            }
        }
    }

    fun setOcrBitmap(bitmap: Bitmap) {
        _isOcrProcessing.value = true
        viewModelScope.launch {
            val result = OcrEngine.recognizeTextFromBitmap(getApplication(), bitmap)
            _isOcrProcessing.value = false
            result.onSuccess { text ->
                _ocrExtractedText.value = text
            }.onFailure { err ->
                _ocrExtractedText.value = "OCR Failed: ${err.localizedMessage}"
            }
        }
    }

    fun sendOcrToTranslator() {
        if (_ocrExtractedText.value.isNotBlank()) {
            _sourceText.value = _ocrExtractedText.value
            detectLanguageAuto(_ocrExtractedText.value)
        }
    }

    // Floating Bubble Toggle
    fun toggleFloatingBubble(context: Context) {
        if (isBubbleRunning.value) {
            FloatingBubbleService.stop(context)
        } else {
            FloatingBubbleService.start(context)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }
}
