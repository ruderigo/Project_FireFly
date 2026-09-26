package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY lastSeenTimestamp DESC")
    fun getAllFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends WHERE destinationHash = :hash LIMIT 1")
    suspend fun getFriendByHash(hash: String): FriendEntity?

    @Query("SELECT * FROM friends WHERE destinationHash = :hash LIMIT 1")
    fun observeFriendByHash(hash: String): Flow<FriendEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: FriendEntity)

    @Update
    suspend fun updateFriend(friend: FriendEntity)

    @Delete
    suspend fun deleteFriend(friend: FriendEntity)

    @Query("DELETE FROM friends WHERE destinationHash = :hash")
    suspend fun deleteFriendByHash(hash: String)

    @Query("SELECT EXISTS(SELECT 1 FROM friends WHERE destinationHash = :hash)")
    fun isFriend(hash: String): Flow<Boolean>
}
