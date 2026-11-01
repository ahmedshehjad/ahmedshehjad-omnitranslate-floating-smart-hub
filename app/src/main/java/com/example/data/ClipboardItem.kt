package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_items")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val translatedText: String = "",
    val sourceLanguage: String = "en",
    val targetLanguage: String = "bn",
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val tag: String = "General"
)
