package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectMessageDao {
    @Query("SELECT * FROM direct_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<DirectMessageEntity>>

    @Query("""
        SELECT * FROM direct_messages 
        WHERE LOWER(peerHash) = LOWER(:peerHash) 
           OR (:peerHash != '' AND LENGTH(:peerHash) >= 8 AND LENGTH(peerHash) >= 8 AND (
               SUBSTR(LOWER(peerHash), 1, 8) = SUBSTR(LOWER(:peerHash), 1, 8)
           ))
        ORDER BY timestamp ASC
    """)
    fun getMessagesForPeer(peerHash: String): Flow<List<DirectMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: DirectMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<DirectMessageEntity>)

    @Query("""
        DELETE FROM direct_messages 
        WHERE LOWER(peerHash) = LOWER(:peerHash) 
           OR (:peerHash != '' AND LENGTH(:peerHash) >= 8 AND LENGTH(peerHash) >= 8 AND (
               SUBSTR(LOWER(peerHash), 1, 8) = SUBSTR(LOWER(:peerHash), 1, 8)
           ))
    """)
    suspend fun deleteMessagesForPeer(peerHash: String)

    @Query("DELETE FROM direct_messages")
    suspend fun clearAll()
}
