package com.example.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

const val STUMP_DM_VIRTUAL_HASH = "stump-dm"

class DirectMessageRepository(private val dao: DirectMessageDao) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active conversation thread StateFlow
    private val _activePeerHash = MutableStateFlow("")
    val activePeerHash: StateFlow<String> = _activePeerHash.asStateFlow()

    private val _activeConversationMessages = MutableStateFlow<List<DirectMessageEntity>>(emptyList())
    val activeConversationMessages: StateFlow<List<DirectMessageEntity>> = _activeConversationMessages.asStateFlow()

    // Outbound deduplication guard
    private val outboundLock = Any()
    private var lastOutboundTarget: String? = null
    private var lastOutboundContent: String? = null
    private var lastOutboundTimestamp: Long = 0L

    companion object {
        const val STUMP_DM_VIRTUAL_HASH = com.example.data.STUMP_DM_VIRTUAL_HASH

        private val verifiedStumpHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun registerStumpHash(hash: String) {
            val clean = hash.replace("<", "").replace(">", "").replace(":", "").replace(" ", "").trim().lowercase()
            if (clean.isNotBlank()) {
                verifiedStumpHashes.add(clean)
                if (clean.length >= 8) {
                    verifiedStumpHashes.add(clean.take(8))
                }
            }
        }

        fun isStumpHash(hash: String): Boolean {
            val clean = hash.replace("<", "").replace(">", "").replace(":", "").replace(" ", "").trim().lowercase()
            if (clean.isEmpty()) return false
            if (clean == STUMP_DM_VIRTUAL_HASH) return true
            for (stump in verifiedStumpHashes) {
                if (clean == stump || clean.startsWith(stump) || (clean.length >= 8 && stump.startsWith(clean.take(8)))) {
                    return true
                }
            }
            return false
        }

        fun normalizeHash(hash: String): String {
            return hash.replace("<", "").replace(">", "").replace(":", "").replace(" ", "").trim().lowercase()
        }
    }

    init {
        scope.launch {
            try {
                _activePeerHash.collectLatest { peerHash ->
                    try {
                        val cleanPeer = normalizeHash(peerHash)
                        if (cleanPeer.isBlank()) {
                            dao.getAllMessages().collectLatest { all ->
                                _activeConversationMessages.value = all
                            }
                        } else {
                            dao.getMessagesForPeer(cleanPeer).collectLatest { peerMsgs ->
                                _activeConversationMessages.value = peerMsgs
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DirectMessageRepo", "Error collecting messages for peer: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DirectMessageRepo", "Error in activePeerHash scope: ${e.message}", e)
            }
        }
    }

    fun setActivePeer(peerHash: String) {
        _activePeerHash.value = normalizeHash(peerHash)
    }

    fun getMessagesForPeerFlow(peerHash: String): Flow<List<DirectMessageEntity>> {
        return dao.getMessagesForPeer(normalizeHash(peerHash))
    }

    fun isDuplicateOutbound(targetHash: String, content: String): Boolean {
        synchronized(outboundLock) {
            val now = System.currentTimeMillis()
            val cleanTarget = normalizeHash(targetHash)
            if (lastOutboundTarget == cleanTarget &&
                lastOutboundContent == content &&
                (now - lastOutboundTimestamp) < 1500L
            ) {
                return true
            }
            lastOutboundTarget = cleanTarget
            lastOutboundContent = content
            lastOutboundTimestamp = now
            return false
        }
    }

    suspend fun insertMessage(message: DirectMessageEntity) {
        try {
            val normalized = message.copy(
                peerHash = normalizeHash(message.peerHash),
                senderHash = normalizeHash(message.senderHash),
                recipientHash = normalizeHash(message.recipientHash)
            )
            if (!normalized.isIncoming) {
                val target = normalized.recipientHash.ifEmpty { normalized.peerHash }
                synchronized(outboundLock) {
                    val now = System.currentTimeMillis()
                    if (lastOutboundTarget == target &&
                        lastOutboundContent == normalized.content &&
                        (now - lastOutboundTimestamp) < 1500L
                    ) {
                        android.util.Log.d("DirectMessageRepo", "Duplicate outbound message dropped (<1500ms): ${normalized.content}")
                        return
                    }
                    lastOutboundTarget = target
                    lastOutboundContent = normalized.content
                    lastOutboundTimestamp = now
                }
            }
            dao.insertMessage(normalized)
        } catch (e: Exception) {
            android.util.Log.e("DirectMessageRepo", "Error inserting direct message: ${e.message}", e)
        }
    }

    suspend fun insertMessages(messages: List<DirectMessageEntity>) {
        try {
            val toInsert = mutableListOf<DirectMessageEntity>()
            val now = System.currentTimeMillis()
            for (raw in messages) {
                val normalized = raw.copy(
                    peerHash = normalizeHash(raw.peerHash),
                    senderHash = normalizeHash(raw.senderHash),
                    recipientHash = normalizeHash(raw.recipientHash)
                )
                if (!normalized.isIncoming) {
                    val target = normalized.recipientHash.ifEmpty { normalized.peerHash }
                    var isDup = false
                    synchronized(outboundLock) {
                        if (lastOutboundTarget == target &&
                            lastOutboundContent == normalized.content &&
                            (now - lastOutboundTimestamp) < 1500L
                        ) {
                            isDup = true
                        } else {
                            lastOutboundTarget = target
                            lastOutboundContent = normalized.content
                            lastOutboundTimestamp = now
                        }
                    }
                    if (isDup) {
                        android.util.Log.d("DirectMessageRepo", "Duplicate outbound message in batch dropped (<1500ms): ${normalized.content}")
                        continue
                    }
                }
                toInsert.add(normalized)
            }
            if (toInsert.isNotEmpty()) {
                dao.insertMessages(toInsert)
            }
        } catch (e: Exception) {
            android.util.Log.e("DirectMessageRepo", "Error batch inserting messages: ${e.message}", e)
        }
    }

    suspend fun deleteMessagesForPeer(peerHash: String) {
        dao.deleteMessagesForPeer(normalizeHash(peerHash))
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}

typealias ChatRepository = DirectMessageRepository
