package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object OcrEngine {
    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizer = it
            }
        }
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        suspendCancellableCoroutine { continuation ->
            getRecognizer().process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(Result.success(visionText.text))
                }
                .addOnFailureListener { e ->
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun recognizeTextFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap != null) {
                recognizeTextFromBitmap(bitmap)
            } else {
                Result.failure(Exception("Unable to decode image from selected URI"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
