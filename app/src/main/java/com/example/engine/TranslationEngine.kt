package com.example.engine

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object TranslationEngine {
    private const val TAG = "TranslationEngine"
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    @Volatile
    private var languageIdentifier: LanguageIdentifier? = null

    private fun getLanguageIdentifier(): LanguageIdentifier {
        return languageIdentifier ?: synchronized(this) {
            languageIdentifier ?: LanguageIdentification.getClient().also {
                languageIdentifier = it
            }
        }
    }

    // Cache translators for performance
    private val translatorCache = ConcurrentHashMap<String, Translator>()

    suspend fun identifyLanguage(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        suspendCancellableCoroutine { continuation ->
            getLanguageIdentifier().identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    if (languageCode == "und") {
                        continuation.resume(null)
                    } else {
                        continuation.resume(languageCode)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Language identification failed", e)
                    continuation.resume(null)
                }
        }
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        requireWifi: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success("")
        if (sourceLang == targetLang) return@withContext Result.success(text)

        val key = "${sourceLang}_$targetLang"
        val translator = translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }

        try {
            // First ensure models are downloaded or available
            val conditionsBuilder = DownloadConditions.Builder()
            if (requireWifi) {
                conditionsBuilder.requireWifi()
            }
            val conditions = conditionsBuilder.build()

            val downloaded = suspendCancellableCoroutine<Boolean> { cont ->
                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        cont.resume(true)
                    }
                    .addOnFailureListener { err ->
                        Log.e(TAG, "Model download failed for $key", err)
                        cont.resume(false)
                    }
            }

            if (!downloaded) {
                return@withContext Result.failure(Exception("Could not prepare language models for $sourceLang to $targetLang"))
            }

            // Perform translation
            val translated = suspendCancellableCoroutine<String?> { cont ->
                translator.translate(text)
                    .addOnSuccessListener { result ->
                        cont.resume(result)
                    }
                    .addOnFailureListener { err ->
                        Log.e(TAG, "Translation error", err)
                        cont.resume(null)
                    }
            }

            if (translated != null) {
                Result.success(translated)
            } else {
                Result.failure(Exception("Translation produced no output"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in translate coroutine", e)
            Result.failure(e)
        }
    }

    suspend fun getDownloadedModels(): Set<String> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val languageCodes = models.map { it.language }.toSet()
                    cont.resume(languageCodes)
                }
                .addOnFailureListener {
                    cont.resume(emptySet())
                }
        }
    }

    suspend fun downloadModel(langCode: String, requireWifi: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val model = TranslateRemoteModel.Builder(langCode).build()
        val conditionsBuilder = DownloadConditions.Builder()
        if (requireWifi) {
            conditionsBuilder.requireWifi()
        }
        val conditions = conditionsBuilder.build()

        suspendCancellableCoroutine { cont ->
            modelManager.download(model, conditions)
                .addOnSuccessListener {
                    cont.resume(Result.success(Unit))
                }
                .addOnFailureListener { err ->
                    cont.resume(Result.failure(err))
                }
        }
    }

    suspend fun deleteModel(langCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val model = TranslateRemoteModel.Builder(langCode).build()
        suspendCancellableCoroutine { cont ->
            modelManager.deleteDownloadedModel(model)
                .addOnSuccessListener {
                    // Invalidate cached translators using this model
                    translatorCache.entries.removeIf { it.key.contains(langCode) }
                    cont.resume(Result.success(Unit))
                }
                .addOnFailureListener { err ->
                    cont.resume(Result.failure(err))
                }
        }
    }

    fun close() {
        for (translator in translatorCache.values) {
            translator.close()
        }
        translatorCache.clear()
        languageIdentifier?.close()
        languageIdentifier = null
    }
}
