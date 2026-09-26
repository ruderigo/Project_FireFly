package com.example.data

import kotlinx.coroutines.flow.Flow

class FriendRepository(private val friendDao: FriendDao) {
    val allFriends: Flow<List<FriendEntity>> = friendDao.getAllFriends()

    suspend fun getFriendByHash(hash: String): FriendEntity? = friendDao.getFriendByHash(hash)

    fun observeFriendByHash(hash: String): Flow<FriendEntity?> = friendDao.observeFriendByHash(hash)

    suspend fun insertFriend(friend: FriendEntity) = friendDao.insertFriend(friend)

    suspend fun updateFriend(friend: FriendEntity) = friendDao.updateFriend(friend)

    suspend fun deleteFriend(friend: FriendEntity) = friendDao.deleteFriend(friend)

    suspend fun deleteFriendByHash(hash: String) = friendDao.deleteFriendByHash(hash)

    fun isFriend(hash: String): Flow<Boolean> = friendDao.isFriend(hash)
}
