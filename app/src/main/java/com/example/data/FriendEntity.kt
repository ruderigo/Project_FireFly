package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey
    val destinationHash: String,
    val displayName: String,
    val identityKey: String = "",
    val hops: Int = 0,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val rssi: Int = -75,
    val isReachable: Boolean = true
)
