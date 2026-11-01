package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboard_items WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavoriteItems(): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboard_items WHERE tag = :tag ORDER BY timestamp DESC")
    fun getItemsByTag(tag: String): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboard_items WHERE originalText LIKE '%' || :query || '%' OR translatedText LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchItems(query: String): Flow<List<ClipboardItem>>

    @Query("SELECT COUNT(*) FROM clipboard_items")
    fun getCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun getCountDirect(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardItem): Long

    @Update
    suspend fun update(item: ClipboardItem)

    @Delete
    suspend fun delete(item: ClipboardItem)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_items")
    suspend fun clearAll()
}
