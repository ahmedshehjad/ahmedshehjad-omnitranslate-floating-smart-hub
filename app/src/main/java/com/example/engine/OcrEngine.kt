package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.adaptech.tesseract4android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object OcrEngine {
    private const val TAG = "OcrEngine"
    private const val BENGALI_MODEL_URL =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/ben.traineddata"
    private const val MIN_MODEL_BYTES = 1_000_000L

    @Volatile
    private var recognizer: TextRecognizer? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizer = it
            }
        }
    }

    suspend fun recognizeTextFromBitmap(context: Context, bitmap: Bitmap): Result<String> =
        withContext(Dispatchers.IO) {
            val image = InputImage.fromBitmap(bitmap, 0)
            val latinResult = suspendCancellableCoroutine<Result<String>> { continuation ->
                getRecognizer().process(image)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(Result.success(visionText.text))
                    }
                    .addOnFailureListener { error ->
                        continuation.resume(Result.failure(error))
                    }
            }

            // ML Kit's bundled recognizer is Latin-script only. Try the Bengali Tesseract
            // model as a second pass and prefer it only when it actually returns Bengali text.
            val bengaliText = try {
                recognizeBengaliText(context, bitmap)
            } catch (error: Exception) {
                Log.w(TAG, "Bengali OCR unavailable; retaining Latin OCR result", error)
                null
            }

            if (!bengaliText.isNullOrBlank() && containsBengaliScript(bengaliText)) {
                Result.success(bengaliText.trim())
            } else {
                latinResult
            }
        }

    suspend fun recognizeTextFromUri(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (bitmap != null) {
                    recognizeTextFromBitmap(context, bitmap)
                } else {
                    Result.failure(Exception("Unable to decode image from selected URI"))
                }
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private fun recognizeBengaliText(context: Context, bitmap: Bitmap): String? {
        val dataDirectory = File(context.filesDir, "tesseract")
        val tessdataDirectory = File(dataDirectory, "tessdata")
        val modelFile = File(tessdataDirectory, "ben.traineddata")

        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
            if (!tessdataDirectory.exists() && !tessdataDirectory.mkdirs()) {
                throw IllegalStateException("Could not create private OCR model directory")
            }

            val temporaryFile = File(tessdataDirectory, "ben.traineddata.download")
            val request = Request.Builder().url(BENGALI_MODEL_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Bengali OCR model download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Bengali OCR model response was empty")
                temporaryFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }

            if (temporaryFile.length() < MIN_MODEL_BYTES) {
                temporaryFile.delete()
                throw IllegalStateException("Downloaded Bengali OCR model is incomplete")
            }
            if (!temporaryFile.renameTo(modelFile)) {
                temporaryFile.delete()
                throw IllegalStateException("Could not install Bengali OCR model")
            }
        }

        val api = TessBaseAPI()
        return try {
            if (!api.init(dataDirectory.absolutePath, "ben")) {
                throw IllegalStateException("Tesseract could not initialize the Bengali OCR model")
            }
            api.setImage(bitmap)
            api.getUTF8Text()
        } finally {
            api.recycle()
        }
    }

    private fun containsBengaliScript(text: String): Boolean {
        val bengaliCharacters = text.count { it in '\u0980'..'\u09FF' }
        val lettersAndDigits = text.count { it.isLetterOrDigit() }
        return bengaliCharacters > 0 && bengaliCharacters * 5 >= lettersAndDigits
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
