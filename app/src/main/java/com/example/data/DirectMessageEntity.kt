package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "direct_messages")
data class DirectMessageEntity(
    @PrimaryKey
    val id: String,
    val peerHash: String,
    val senderHash: String,
    val recipientHash: String,
    val content: String,
    val isIncoming: Boolean,
    val status: String = "Delivered",
    val timestamp: Long = System.currentTimeMillis(),
    val isDirectLink: Boolean = false
)
