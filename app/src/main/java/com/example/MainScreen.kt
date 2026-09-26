package com.example

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.example.data.AppDatabase
import com.example.data.FriendEntity
import com.example.data.FriendRepository
import com.example.data.DirectMessageEntity
import com.example.data.DirectMessageRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val STUMP_DM_VIRTUAL_HASH = com.example.data.STUMP_DM_VIRTUAL_HASH

data class MeshPeer(
    val hash: String,
    val name: String,
    val hops: Int,
    val timestamp: Long
)

typealias DiscoveredPeer = MeshPeer

data class MeshMessage(
    val id: String,
    val peer: String,
    val content: String,
    val isIncoming: Boolean,
    val status: String,
    val timestamp: Double,
    val isDm: Boolean = true
)

enum class AppTab(val title: String) {
    NETWORK("Network"),
    MESSAGES("Messages"),
    STUMP("Stump Node")
}

@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBackendReady by viewModel.isBackendReady.collectAsState()
    MainScreen(
        modifier = modifier,
        usbBridge = viewModel.usbBridge,
        context = context,
        mainViewModel = viewModel,
        isBackendReady = isBackendReady
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    usbBridge: UsbRNodeBridge,
    context: Context,
    mainViewModel: MainViewModel? = null,
    isBackendReady: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()
    val bridgeState by usbBridge.bridgeState.collectAsState()
    val activeTransportType by usbBridge.activeTransportType.collectAsState()
    val lastError by usbBridge.lastError.collectAsState()
    val isUsbPluggedIn = usbBridge.isUsbDevicePluggedIn()
    val currentTxPower by usbBridge.txPowerFlow.collectAsState()
    val chatViewModel: ChatViewModel = viewModel()
    val isSendingChat by chatViewModel.isSending.collectAsState()

    var showBleScannerSheet by remember { mutableStateOf(false) }
    val discoveredBleNodes by usbBridge.bleTransport.discoveredNodes.collectAsState()
    val isBleScanning by usbBridge.bleTransport.isScanning.collectAsState()
    val isBleConnecting by usbBridge.bleTransport.isConnecting.collectAsState()

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (bm?.adapter?.isEnabled != true) {
                Toast.makeText(context, "Please turn on Bluetooth to scan for RNodes", Toast.LENGTH_LONG).show()
            } else {
                showBleScannerSheet = true
                usbBridge.bleTransport.startScanning()
            }
        } else {
            Toast.makeText(context, "Bluetooth permissions are required to scan for RNodes", Toast.LENGTH_SHORT).show()
        }
    }

    fun openBleScanner() {
        val hasAll = bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (hasAll) {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (bm?.adapter?.isEnabled != true) {
                Toast.makeText(context, "Please turn on Bluetooth to scan for RNodes", Toast.LENGTH_LONG).show()
            } else {
                showBleScannerSheet = true
                usbBridge.bleTransport.startScanning()
            }
        } else {
            blePermissionLauncher.launch(bluetoothPermissions)
        }
    }

    // BLE connection state handled via MainActivity and user actions

    // Auto-dismiss BLE sheet when connected
    LaunchedEffect(bridgeState) {
        if (bridgeState == BridgeState.ONLINE && showBleScannerSheet) {
            showBleScannerSheet = false
            val nodeName = usbBridge.bleTransport.connectedDeviceName ?: "Heltec RNode"
            Toast.makeText(context, "Connected to $nodeName", Toast.LENGTH_SHORT).show()
        }
    }

    var selectedTab by remember { mutableStateOf(AppTab.NETWORK) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rnsHash by remember { mutableStateOf("") }
    var isRnsStarting by remember { mutableStateOf(false) }
    var txCount by remember { mutableStateOf(0L) }
    var rxCount by remember { mutableStateOf(0L) }

    val eventLog = remember { mutableStateListOf<String>() }
    val discoveredPeers = remember { mutableStateListOf<MeshPeer>() }
    var meshMessages by remember { mutableStateOf<List<MeshMessage>>(emptyList()) }

    // Message screen active target peer
    var activeChatPeerHash by remember { mutableStateOf("") }

    // Room Database for Saved Friends / Contacts & Direct Messages
    val appDb = remember { AppDatabase.getDatabase(context) }
    val friendRepository = remember { FriendRepository(appDb.friendDao()) }
    val savedFriends by friendRepository.allFriends.collectAsState(initial = emptyList())
    val directMessageRepository = remember { DirectMessageRepository(appDb.directMessageDao()) }
    val activeConversationMessages by directMessageRepository.activeConversationMessages.collectAsState()
    val radioTelemetry by RadioTelemetryState.telemetry.collectAsState()

    LaunchedEffect(Unit) {
        MeshService.setDmRepository(directMessageRepository)
    }

    LaunchedEffect(activeChatPeerHash) {
        directMessageRepository.setActivePeer(activeChatPeerHash)
    }

    val onSaveFriendAction: (String, String, Int) -> Unit = { hash, name, hops ->
        coroutineScope.launch(Dispatchers.IO) {
            val trimmedHash = hash.trim()
            val trimmedName = name.trim().ifEmpty { "Node ${trimmedHash.take(8)}" }
            val updated = FriendEntity(
                destinationHash = trimmedHash,
                displayName = trimmedName,
                hops = hops,
                lastSeenTimestamp = System.currentTimeMillis(),
                isReachable = true
            )
            friendRepository.insertFriend(updated)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved friend: ${updated.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onDeleteFriendAction: (String) -> Unit = { hash ->
        coroutineScope.launch(Dispatchers.IO) {
            friendRepository.deleteFriendByHash(hash.trim())
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Contact removed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Hoisted Stump Node connection & auto-discovery state (persisted across tab changes)
    val stumpPrefs = remember { context.getSharedPreferences("stump_prefs", Context.MODE_PRIVATE) }
    var stumpUrl by remember {
        mutableStateOf(stumpPrefs.getString("stump_url", "http://192.168.4.1") ?: "http://192.168.4.1")
    }
    var stumpReachable by remember { mutableStateOf<Boolean?>(null) }
    var isStumpScanning by remember { mutableStateOf(false) }
    var stumpScanProgress by remember { mutableIntStateOf(0) }
    var stumpScanTotal by remember { mutableIntStateOf(254) }
    var stumpScanStatusText by remember { mutableStateOf("") }
    val stumpClient = remember { StumpHttpClient().apply { baseUrl = stumpUrl } }

    fun updateStumpUrl(newUrl: String) {
        stumpUrl = newUrl
        stumpClient.baseUrl = newUrl
        stumpPrefs.edit().putString("stump_url", newUrl).apply()
    }

    // Independent RRC chat state strictly isolated from LXMF meshMessages
    var rrcMessages by remember { mutableStateOf<List<RrcMessage>>(emptyList()) }
    var rrcActiveRoom by remember { mutableStateOf("lxmf") }
    var rrcAvailableRooms by remember { mutableStateOf(listOf("lxmf", "main", "offtopic")) }
    var rrcActiveUsers by remember { mutableStateOf(listOf("Concierge")) }
    var rrcSinceId by remember { mutableLongStateOf(0L) }

    suspend fun startRns() {
        if (isRnsStarting) return
        val existingHash = MeshService.rnsIdentityHash
        if (!existingHash.isNullOrEmpty()) {
            rnsHash = existingHash
            return
        }
        isRnsStarting = true
        withContext(Dispatchers.IO) {
            try {
                // Await primary Reticulum engine booted by Application.onCreate / ReticulumBridge
                var waitCount = 0
                while (MeshService.rnsIdentityHash.isNullOrEmpty() && waitCount < 100 && isActive) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
                val hashStr = MeshService.rnsIdentityHash
                if (hashStr != null) {
                    withContext(Dispatchers.Main) {
                        rnsHash = hashStr
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    eventLog.add(0, "PyError: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isRnsStarting = false
                }
            }
        }
    }

    // Sync Reticulum identity hash when backend is ready (boot managed as singleton by FireflyApp)
    LaunchedEffect(isBackendReady) {
        if (isBackendReady && rnsHash.isEmpty()) {
            MeshService.rnsIdentityHash?.let { rnsHash = it }
        }
    }

    // Polling loop for Python events, discovered peers, and messages
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var cachedRnsCore: com.chaquo.python.PyObject? = null
            while (isActive) {
                try {
                    if (Python.isStarted()) {
                        if (cachedRnsCore == null) {
                            val py = Python.getInstance()
                            cachedRnsCore = py.getModule("rns_core")
                        }
                        val rnsCore = cachedRnsCore

                        if (rnsHash.isEmpty()) {
                            val idHash = MeshService.rnsIdentityHash
                            if (!idHash.isNullOrEmpty()) {
                                withContext(Dispatchers.Main) {
                                    rnsHash = idHash
                                }
                            }
                        }

                        // 1. Poll log events
                        val eventsPy = rnsCore.callAttr("poll_events")
                        val eventsList = eventsPy?.asList()
                        if (!eventsList.isNullOrEmpty()) {
                            for (ev in eventsList) {
                                val evStr = ev.toString()
                                DiagnosticLogger.logPythonRns(evStr)
                            }
                            withContext(Dispatchers.Main) {
                                for (ev in eventsList) {
                                    val evStr = ev.toString()
                                    eventLog.add(0, evStr)
                                }
                                while (eventLog.size > 200) {
                                    eventLog.removeAt(eventLog.size - 1)
                                }
                            }
                        }

                        // 2. Poll discovered peers JSON (only if RNS is active)
                        if (rnsHash.isNotEmpty()) {
                            try {
                                val jsonStr = rnsCore.callAttr("get_discovered_peers_json")?.toString()
                                if (!jsonStr.isNullOrBlank() && jsonStr != "[]") {
                                    val jsonArray = JSONArray(jsonStr)
                                    val freshList = mutableListOf<MeshPeer>()
                                    for (i in 0 until jsonArray.length()) {
                                        val obj = jsonArray.getJSONObject(i)
                                        val hashVal = obj.optString("hash", "")
                                        val nameVal = obj.optString("name", "")
                                        val hopsVal = obj.optInt("hops", 1)
                                        val tsVal = if (obj.has("timestamp")) {
                                            obj.optLong("timestamp", 0L)
                                        } else {
                                            obj.optDouble("time", 0.0).toLong()
                                        }
                                        if (hashVal.isNotEmpty()) {
                                            freshList.add(
                                                MeshPeer(
                                                    hash = hashVal,
                                                    name = if (nameVal.isNotEmpty()) nameVal else hashVal.take(8),
                                                    hops = hopsVal,
                                                    timestamp = tsVal
                                                )
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        discoveredPeers.clear()
                                        discoveredPeers.addAll(freshList)
                                    }
                                    for (peer in freshList) {
                                        if (peer.name.lowercase().contains("stump") || peer.name.lowercase().contains("labuche") || peer.name.lowercase().contains("concierge")) {
                                            DirectMessageRepository.registerStumpHash(peer.hash)
                                        }
                                        val match = savedFriends.find { it.destinationHash.equals(peer.hash, ignoreCase = true) }
                                        if (match != null && (!match.isReachable || match.hops != peer.hops)) {
                                            friendRepository.updateFriend(
                                                match.copy(
                                                    hops = peer.hops,
                                                    lastSeenTimestamp = System.currentTimeMillis(),
                                                    isReachable = true
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Log error or ignore transient parsing exception
                            }

                            // 3. Poll message history JSON and sync with Room DB
                            try {
                                val msgJson = rnsCore.callAttr("get_messages_json")?.toString()
                                if (!msgJson.isNullOrEmpty()) {
                                    val jsonArr = JSONArray(msgJson)
                                    val parsed = mutableListOf<MeshMessage>()
                                    val dmEntities = mutableListOf<DirectMessageEntity>()
                                    for (i in 0 until jsonArr.length()) {
                                        val obj = jsonArr.getJSONObject(i)
                                        val idStr = obj.optString("id", i.toString())
                                        val peerStr = obj.optString("peer", "")
                                        val contentStr = obj.optString("content", "")
                                        val isInc = obj.optBoolean("incoming", false)
                                        val statusStr = obj.optString("status", if (isInc) "Direct Message" else "Sent")
                                        val timeVal = obj.optDouble("time", 0.0)
                                        val isLinkVal = obj.optBoolean("is_link", false)

                                        val cleanPeerStr = DirectMessageRepository.normalizeHash(peerStr)

                                        parsed.add(
                                            MeshMessage(
                                                id = idStr,
                                                peer = cleanPeerStr,
                                                content = contentStr,
                                                isIncoming = isInc,
                                                status = statusStr,
                                                timestamp = timeVal,
                                                isDm = true
                                            )
                                        )
                                        val myCleanHash = DirectMessageRepository.normalizeHash(rnsHash.ifEmpty { MeshService.rnsIdentityHash ?: "" })
                                        if (isInc) {
                                            dmEntities.add(
                                                DirectMessageEntity(
                                                    id = idStr,
                                                    peerHash = cleanPeerStr,
                                                    senderHash = cleanPeerStr,
                                                    recipientHash = myCleanHash,
                                                    content = contentStr,
                                                    isIncoming = true,
                                                    status = statusStr,
                                                    timestamp = (timeVal * 1000).toLong(),
                                                    isDirectLink = isLinkVal
                                                )
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        meshMessages = parsed
                                    }
                                    if (dmEntities.isNotEmpty()) {
                                        directMessageRepository.insertMessages(dmEntities)
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }

                            // 4. Poll LoRa channel messages from rns_core and pipe to rrcMessages
                            try {
                                val chanJson = rnsCore.callAttr("poll_new_channel_msgs_json")?.toString()
                                if (!chanJson.isNullOrBlank() && chanJson != "[]") {
                                    val chanArr = JSONArray(chanJson)
                                    val newMessages = mutableListOf<RrcMessage>()
                                    for (i in 0 until chanArr.length()) {
                                        val obj = chanArr.getJSONObject(i)
                                        val nowMs = System.currentTimeMillis()
                                        val idVal = obj.optLong("id", 0L).let { if (it > 0) it else nowMs + i }
                                        val tsVal = obj.optDouble("time", nowMs / 1000.0)
                                        val rawContent = obj.optString("content", "")
                                        val lowerRaw = rawContent.lowercase()
                                        if (lowerRaw.contains("whispers:") || lowerRaw.contains("[private]") || lowerRaw.contains("[dm]") || lowerRaw.startsWith("/msg ") || lowerRaw.startsWith("/w ")) {
                                            continue
                                        }
                                        var nickVal = obj.optString("nick", "stump")
                                        var bodyVal = rawContent
                                        val nickMatch = Regex("^<([^>]+)>\\s*(.*)$").find(rawContent)
                                        if (nickMatch != null) {
                                            nickVal = nickMatch.groupValues[1].trim()
                                            bodyVal = nickMatch.groupValues[2].trim()
                                        }
                                        val kindVal = if (rawContent.startsWith("* ")) "action" else "msg"
                                        newMessages.add(
                                            RrcMessage(
                                                id = idVal,
                                                ts = tsVal,
                                                nick = nickVal,
                                                body = bodyVal,
                                                kind = kindVal
                                            )
                                        )
                                    }
                                    if (newMessages.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            rrcMessages = (rrcMessages + newMessages).distinctBy { "${it.id}_${it.ts}_${it.nick}_${it.body}" }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                } catch (e: Exception) {
                    cachedRnsCore = null
                }

                txCount = usbBridge.txPackets.get()
                rxCount = usbBridge.rxPackets.get()
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == AppTab.NETWORK,
                    onClick = { selectedTab = AppTab.NETWORK },
                    icon = {
                        BadgedBox(badge = {
                            if (discoveredPeers.isNotEmpty()) {
                                Badge { Text("${discoveredPeers.size}") }
                            }
                        }) {
                            Icon(Icons.Default.Lan, contentDescription = "Network")
                        }
                    },
                    label = { Text("Network") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.MESSAGES,
                    onClick = { selectedTab = AppTab.MESSAGES },
                    icon = {
                        BadgedBox(badge = {
                            if (meshMessages.isNotEmpty()) {
                                Badge { Text("${meshMessages.size}") }
                            }
                        }) {
                            Icon(Icons.Default.Forum, contentDescription = "Messages")
                        }
                    },
                    label = { Text("Messages") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.STUMP,
                    onClick = { selectedTab = AppTab.STUMP },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Stump Node") },
                    label = { Text("Stump Node") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                AppTab.NETWORK -> NetworkTabScreen(
                    peers = discoveredPeers,
                    txCount = txCount,
                    rxCount = rxCount,
                    context = context,
                    bridgeState = bridgeState,
                    activeTransportType = activeTransportType,
                    isUsbPluggedIn = isUsbPluggedIn,
                    activeNodeName = usbBridge.activeNodeDescriptor(),
                    isBleConnecting = isBleConnecting,
                    lastError = lastError,
                    friends = savedFriends,
                    txPower = currentTxPower,
                    rnsHash = rnsHash,
                    isRnsStarting = isRnsStarting,
                    onStartRns = { coroutineScope.launch { startRns() } },
                    onOpenSettings = { showSettingsSheet = true },
                    onConnectRadio = {
                        if (bridgeState == BridgeState.DISCONNECTED) {
                            if (usbBridge.isUsbDevicePluggedIn()) {
                                usbBridge.startServerAndConnectUsb()
                            } else {
                                openBleScanner()
                            }
                        }
                    },
                    onSetTxPower = { power -> usbBridge.setRadioTxPower(power) },
                    onSaveFriend = onSaveFriendAction,
                    onDeleteFriend = onDeleteFriendAction,
                    onScanBluetoothClicked = { openBleScanner() },
                    onDisconnectRadioClicked = {
                        usbBridge.disconnect()
                        Toast.makeText(context, "Radio disconnected", Toast.LENGTH_SHORT).show()
                    },
                    onForgetDeviceClicked = {
                        usbBridge.forgetBleDevice()
                        Toast.makeText(context, "Forgot saved Bluetooth node", Toast.LENGTH_SHORT).show()
                    },
                    onPeerMessageClicked = { peerHash ->
                        activeChatPeerHash = peerHash
                        selectedTab = AppTab.MESSAGES
                        coroutineScope.launch(Dispatchers.IO) {
                            MeshService.syncPeer(peerHash)
                        }
                    },
                    onSendAnnounce = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val success = MeshService.sendAnnounce()
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        Toast.makeText(context, "Broadcasting Announce on 915.0 MHz...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )

                AppTab.MESSAGES -> {
                    val displayMessages = remember(meshMessages, activeConversationMessages, activeChatPeerHash) {
                        if (activeConversationMessages.isNotEmpty()) {
                            activeConversationMessages.mapIndexed { idx, it ->
                                MeshMessage(
                                    id = "${it.id}_$idx",
                                    peer = it.peerHash,
                                    content = it.content,
                                    isIncoming = it.isIncoming,
                                    status = it.status,
                                    timestamp = it.timestamp / 1000.0,
                                    isDm = true
                                )
                            }
                        } else {
                            meshMessages.mapIndexed { idx, it ->
                                it.copy(id = "${it.id}_$idx")
                            }
                        }
                    }

                    MessagesTabScreen(
                        peers = discoveredPeers,
                        messages = displayMessages,
                        activePeerHash = activeChatPeerHash,
                        onActivePeerChange = {
                            activeChatPeerHash = it
                            if (!it.isNullOrBlank()) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    MeshService.syncPeer(it)
                                }
                            }
                        },
                        friends = savedFriends,
                        onSaveFriend = onSaveFriendAction,
                        isSending = isSendingChat,
                        onSendMessage = { destHash, content ->
                            val cleanDest = DirectMessageRepository.normalizeHash(destHash)
                            if (cleanDest.isNotBlank()) {
                                chatViewModel.sendMessage(cleanDest, content) { ok ->
                                    if (!ok) {
                                        Toast.makeText(context, "Failed to queue message", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onAnnounce = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    MeshService.sendAnnounce()
                                } catch (e: Exception) {}
                            }
                        }
                    )
                }

                AppTab.STUMP -> StumpTabScreen(
                    context = context,
                    eventLog = eventLog,
                    stumpClient = stumpClient,
                    stumpUrl = stumpUrl,
                    onStumpUrlChange = { updateStumpUrl(it) },
                    stumpReachable = stumpReachable,
                    onStumpReachableChange = { stumpReachable = it },
                    isScanning = isStumpScanning,
                    onIsScanningChange = { isStumpScanning = it },
                    scanProgress = stumpScanProgress,
                    onScanProgressChange = { stumpScanProgress = it },
                    scanTotal = stumpScanTotal,
                    onScanTotalChange = { stumpScanTotal = it },
                    scanStatusText = stumpScanStatusText,
                    onScanStatusTextChange = { stumpScanStatusText = it },
                    rrcMessages = rrcMessages,
                    onRrcMessagesChange = { rrcMessages = it },
                    activeRoom = rrcActiveRoom,
                    onActiveRoomChange = { rrcActiveRoom = it },
                    availableRooms = rrcAvailableRooms,
                    onAvailableRoomsChange = { rrcAvailableRooms = it },
                    sinceId = rrcSinceId,
                    onSinceIdChange = { rrcSinceId = it },
                    discoveredPeers = discoveredPeers,
                    activeChatPeerHash = activeChatPeerHash,
                    activeUsers = rrcActiveUsers,
                    onActiveUsersChange = { rrcActiveUsers = it },
                    onSendLoraMessage = { destHash, content ->
                        chatViewModel.sendMessage(destHash, content) { ok ->
                            if (!ok) {
                                Toast.makeText(context, "Failed to queue message", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            val backendLoading = !isBackendReady && rnsHash.isEmpty() && MeshService.rnsIdentityHash.isNullOrEmpty()
            if (backendLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .testTag("backend_loading_indicator")
                )
            }
        }
    }

    if (showBleScannerSheet) {
        BleScannerBottomSheet(
            onDismissRequest = {
                showBleScannerSheet = false
                usbBridge.bleTransport.stopScanning()
            },
            discoveredNodes = discoveredBleNodes,
            isScanning = isBleScanning,
            isConnecting = isBleConnecting,
            connectingAddress = usbBridge.bleTransport.connectedMacAddress,
            onNodeSelected = { node ->
                usbBridge.connectBle(node.device)
            },
            onRescan = {
                usbBridge.bleTransport.startScanning()
            }
        )
    }

    if (showSettingsSheet) {
        SettingsDiagnosticsSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = settingsSheetState,
            context = context,
            activeTransportType = activeTransportType,
            bridgeState = bridgeState,
            connectedDeviceName = usbBridge.bleTransport.connectedDeviceName,
            connectedMacAddress = usbBridge.bleTransport.connectedMacAddress,
            lastSavedMacAddress = usbBridge.lastSavedBleMac,
            lastSavedDeviceName = usbBridge.lastSavedBleName,
            onForceDisconnect = {
                usbBridge.disconnect()
            },
            onForgetDevice = {
                usbBridge.forgetBleDevice()
            },
            onManualReinitRadio = {
                usbBridge.manualReinitRadio()
            }
        )
    }
}

/**
 * Objective 2: Clean, horizontal NetworkPeerCard layout
 * Prevents vertical letter wrapping, eliminates duplicate star icons, formats hashes cleanly,
 * and preserves room for the compact trailing "Chat" button.
 */
@Composable
fun NetworkPeerCard(
    displayName: String,
    hash: String,
    hops: Int,
    timestamp: Long,
    isFriend: Boolean,
    onToggleFriend: () -> Unit,
    onChatClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shortenedHash = remember(hash) {
        if (hash.length >= 12) {
            "${hash.take(8)}...${hash.takeLast(4)}"
        } else if (hash.length > 8) {
            "${hash.take(8)}..."
        } else {
            hash
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Single circular IconButton toggleable Friend star
            IconButton(
                onClick = onToggleFriend,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("friend_star_button")
            ) {
                Icon(
                    imageVector = if (isFriend) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isFriend) "Friend Starred" else "Star Friend",
                    tint = if (isFriend) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Middle: Column preventing child elements from wrapping vertically
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Top row: Peer display name in bold text + subtle "• Friend" badge if favorited
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFriend) {
                        Text(
                            text = "• Friend",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFFFFB300),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Middle row: Shortened destination hash (e.g., 301c3454...4170) with copy action
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Peer Destination Hash", hash)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied hash: $shortenedHash", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = shortenedHash,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Hash",
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                // Bottom row: Metadata badges in a horizontal row ($hops hop, $timeAgo)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "$hops hop${if (hops != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    val timeStr = formatRelativeTime(timestamp)
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Right: Compact, non-clipping FilledTonalButton labeled "Chat" with chat bubble icon
            FilledTonalButton(
                onClick = onChatClicked,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("peer_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Chat",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun DiscoveredPeerCard(
    displayName: String,
    hash: String,
    hops: Int,
    timestamp: Long,
    isFriend: Boolean,
    onToggleFriend: () -> Unit,
    onChatClicked: () -> Unit,
    modifier: Modifier = Modifier
) = NetworkPeerCard(displayName, hash, hops, timestamp, isFriend, onToggleFriend, onChatClicked, modifier)

@Composable
fun DiscoveredTabScreen(
    peers: List<DiscoveredPeer>,
    txCount: Long,
    rxCount: Long,
    context: Context,
    bridgeState: BridgeState = BridgeState.DISCONNECTED,
    activeTransportType: TransportType = TransportType.NONE,
    isUsbPluggedIn: Boolean = false,
    activeNodeName: String = "Heltec V3",
    isBleConnecting: Boolean = false,
    lastError: String? = null,
    friends: List<FriendEntity> = emptyList(),
    txPower: Int = 17,
    rnsHash: String = "",
    isRnsStarting: Boolean = false,
    onStartRns: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onConnectRadio: () -> Unit = {},
    onSetTxPower: (Int) -> Unit = {},
    onSaveFriend: (destinationHash: String, displayName: String, hops: Int) -> Unit = { _, _, _ -> },
    onDeleteFriend: (destinationHash: String) -> Unit = {},
    onScanBluetoothClicked: () -> Unit = {},
    onDisconnectRadioClicked: () -> Unit = {},
    onForgetDeviceClicked: () -> Unit = {},
    onPeerMessageClicked: (String) -> Unit,
    onSendAnnounce: () -> Unit
) = NetworkTabScreen(
    peers, txCount, rxCount, context, bridgeState, activeTransportType, isUsbPluggedIn, activeNodeName,
    isBleConnecting, lastError, friends, txPower, rnsHash, isRnsStarting, onStartRns, onOpenSettings,
    onConnectRadio, onSetTxPower, onSaveFriend, onDeleteFriend, onScanBluetoothClicked, onDisconnectRadioClicked,
    onForgetDeviceClicked, onPeerMessageClicked, onSendAnnounce
)

@Composable
fun NetworkTabScreen(
    peers: List<DiscoveredPeer>,
    txCount: Long,
    rxCount: Long,
    context: Context,
    bridgeState: BridgeState = BridgeState.DISCONNECTED,
    activeTransportType: TransportType = TransportType.NONE,
    isUsbPluggedIn: Boolean = false,
    activeNodeName: String = "Heltec V3",
    isBleConnecting: Boolean = false,
    lastError: String? = null,
    friends: List<FriendEntity> = emptyList(),
    txPower: Int = 17,
    rnsHash: String = "",
    isRnsStarting: Boolean = false,
    onStartRns: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onConnectRadio: () -> Unit = {},
    onSetTxPower: (Int) -> Unit = {},
    onSaveFriend: (destinationHash: String, displayName: String, hops: Int) -> Unit = { _, _, _ -> },
    onDeleteFriend: (destinationHash: String) -> Unit = {},
    onScanBluetoothClicked: () -> Unit = {},
    onDisconnectRadioClicked: () -> Unit = {},
    onForgetDeviceClicked: () -> Unit = {},
    onPeerMessageClicked: (String) -> Unit,
    onSendAnnounce: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val meshManager = remember(context) { MeshManager(context) }

    var selectedFilterIndex by remember { mutableIntStateOf(0) } // 0: Nearby, 1: Starred Friends
    var showFriendDialog by remember { mutableStateOf(false) }
    var showTxPowerDialog by remember { mutableStateOf(false) }
    var dialogDestHash by remember { mutableStateOf("") }
    var dialogDisplayName by remember { mutableStateOf("") }
    var dialogHops by remember { mutableIntStateOf(0) }
    var isEditingExistingFriend by remember { mutableStateOf(false) }
    val radioTelemetry by RadioTelemetryState.telemetry.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
        // Top Bar showing Online status, Local Identity Hash, and Settings Gear (Resides JUST in Network Panel)
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Radio Status Indicator Badge
                val isOnline = bridgeState == BridgeState.ONLINE || bridgeState == BridgeState.RF_TRANSMITTING
                val statusColor = when (bridgeState) {
                    BridgeState.ONLINE, BridgeState.RF_TRANSMITTING -> Color(0xFF00E676)
                    BridgeState.ATTACHED -> Color(0xFFFFB300)
                    BridgeState.DISCONNECTED -> if (!isUsbPluggedIn) Color(0xFF9E9E9E) else Color(0xFFEF5350)
                }
                val statusText = when (bridgeState) {
                    BridgeState.ONLINE, BridgeState.RF_TRANSMITTING -> {
                        if (activeTransportType == TransportType.BLE) {
                            "ONLINE (BLE 915.0 MHz)"
                        } else {
                            "ONLINE (915.0 MHz)"
                        }
                    }
                    BridgeState.ATTACHED -> "CONNECTING"
                    BridgeState.DISCONNECTED -> if (!isUsbPluggedIn) "OFFLINE (NO RADIO)" else "DISCONNECTED"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onConnectRadio() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Top Bar Actions: Identity Chip + Settings Gear Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (rnsHash.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("RNS Identity Hash", rnsHash)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Full hash copied: ${rnsHash.take(16)}...", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "<${rnsHash.take(8)}...>",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Hash",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else if (isOnline) {
                        TextButton(
                            onClick = onStartRns,
                            enabled = !isRnsStarting
                        ) {
                            Text(if (isRnsStarting) "Starting..." else "Start Mesh")
                        }
                    }

                    // Settings & Diagnostics Gear Icon
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Deck Settings & Diagnostics",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Heltec V3 Mesh Radio Card - Telemetry Grid
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Top row: Hardware label + TX + RX counters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hardware label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (activeTransportType == TransportType.BLE) Icons.Default.BluetoothConnected else Icons.Default.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if ((bridgeState == BridgeState.ONLINE || bridgeState == BridgeState.RF_TRANSMITTING) && activeTransportType == TransportType.BLE) {
                                activeNodeName
                            } else {
                                "Heltec V3"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    // TX & RX counters
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "TX",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$txCount",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "RX",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$rxCount",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Link Status Banner / Badge:
                // - USB OTG CONNECTED (Green)
                // - BLE CONNECTED: <Device_Name> (Blue)
                // - SEARCHING / CONNECTING... (Amber + Spinner)
                // - RADIO DISCONNECTED (Grey/Red)
                val isOnline = bridgeState == BridgeState.ONLINE || bridgeState == BridgeState.RF_TRANSMITTING
                val isConnecting = bridgeState == BridgeState.ATTACHED || isBleConnecting

                val badgeText = when {
                    isOnline && activeTransportType == TransportType.USB -> "USB OTG CONNECTED"
                    isOnline && activeTransportType == TransportType.BLE -> "BLE CONNECTED: $activeNodeName"
                    isConnecting -> "SEARCHING / CONNECTING..."
                    else -> "RADIO DISCONNECTED"
                }
                val badgeBg = when {
                    isOnline && activeTransportType == TransportType.USB -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                    isOnline && activeTransportType == TransportType.BLE -> Color(0xFF0D47A1).copy(alpha = 0.15f)
                    isConnecting -> Color(0xFFE65100).copy(alpha = 0.15f)
                    else -> Color(0xFFB71C1C).copy(alpha = 0.1f)
                }
                val badgeFg = when {
                    isOnline && activeTransportType == TransportType.USB -> Color(0xFF2E7D32)
                    isOnline && activeTransportType == TransportType.BLE -> Color(0xFF1565C0)
                    isConnecting -> Color(0xFFEF6C00)
                    else -> Color(0xFFC62828)
                }
                val badgeBorder = when {
                    isOnline && activeTransportType == TransportType.USB -> Color(0xFF2E7D32).copy(alpha = 0.4f)
                    isOnline && activeTransportType == TransportType.BLE -> Color(0xFF1976D2).copy(alpha = 0.4f)
                    isConnecting -> Color(0xFFEF6C00).copy(alpha = 0.4f)
                    else -> Color(0xFFE57373).copy(alpha = 0.4f)
                }
                val dotColor = when {
                    isOnline && activeTransportType == TransportType.USB -> Color(0xFF2E7D32)
                    isOnline && activeTransportType == TransportType.BLE -> Color(0xFF1976D2)
                    isConnecting -> Color(0xFFEF6C00)
                    else -> Color(0xFFE53935)
                }

                Surface(
                    color = badgeBg,
                    contentColor = badgeFg,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = badgeFg
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom metrics row with chips: 915.0 MHz | BW 125 | SF8 | CR 4/5 | TX (interactive)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val telemetryChips = listOf(
                        "915.0 MHz",
                        "BW 125",
                        "SF8",
                        "CR 4/5"
                    )
                    items(telemetryChips) { chipText ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = chipText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Interactive TX Power Chip (Objective 1)
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .testTag("tx_power_chip")
                                .clickable { showTxPowerDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Adjust TX Power",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "TX $txPower dBm",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (radioTelemetry.batteryPercent > 0 || radioTelemetry.batteryMilliVolts > 0) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BatteryChargingFull,
                                        contentDescription = "Battery",
                                        tint = if (radioTelemetry.batteryPercent in 1..20) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (radioTelemetry.batteryPercent > 0) "${radioTelemetry.batteryPercent}%" else "${radioTelemetry.batteryMilliVolts}mV",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (radioTelemetry.channelUtilization > 0f) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "Ch ${"%.1f".format(radioTelemetry.channelUtilization)}%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Controls:
                // - When Disconnected or Connecting: Show prominent "Scan for Bluetooth Node" button
                // - When Connected to BLE: Show "Disconnect" and "Forget Device" buttons
                // - When Connected to USB: Show "Disconnect USB Radio" button
                if (!isOnline) {
                    Button(
                        onClick = onScanBluetoothClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scan_bluetooth_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan for Bluetooth Node",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else if (activeTransportType == TransportType.BLE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDisconnectRadioClicked,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("disconnect_radio_button"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }

                        FilledTonalButton(
                            onClick = onForgetDeviceClicked,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("forget_device_button"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Forget Device", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                } else if (activeTransportType == TransportType.USB) {
                    OutlinedButton(
                        onClick = onDisconnectRadioClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("disconnect_radio_button"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.UsbOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect USB Radio", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }

                if (!lastError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠ $lastError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Discovered Peers List Header with Friends Toggle & Add Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedFilterIndex == 0,
                    onClick = { selectedFilterIndex = 0 },
                    label = { Text("Nearby (${peers.size})", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = selectedFilterIndex == 1,
                    onClick = { selectedFilterIndex = 1 },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    label = { Text("Friends (${friends.size})", style = MaterialTheme.typography.labelMedium) }
                )
            }

            IconButton(
                onClick = {
                    dialogDestHash = ""
                    dialogDisplayName = ""
                    dialogHops = 0
                    isEditingExistingFriend = false
                    showFriendDialog = true
                },
                modifier = Modifier
                    .size(36.dp)
                    .testTag("add_friend_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = "Add Contact by Hash",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (selectedFilterIndex == 0) {
            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No nearby network nodes found yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(peers, key = { index, peer -> "${peer.hash}_$index" }) { _, peer ->
                        val friend = friends.find { it.destinationHash.equals(peer.hash, ignoreCase = true) }
                        val displayName = friend?.displayName ?: if (peer.name.isNotBlank()) peer.name else "Node ${peer.hash.take(8)}"
                        val isFriend = friend != null

                        NetworkPeerCard(
                            displayName = displayName,
                            hash = peer.hash,
                            hops = peer.hops,
                            timestamp = peer.timestamp,
                            isFriend = isFriend,
                            onToggleFriend = {
                                if (isFriend) {
                                    dialogDestHash = friend!!.destinationHash
                                    dialogDisplayName = friend.displayName
                                    dialogHops = friend.hops
                                    isEditingExistingFriend = true
                                    showFriendDialog = true
                                } else {
                                    val name = peer.name.ifBlank { "Node ${peer.hash.take(8)}" }
                                    onSaveFriend(peer.hash, name, peer.hops)
                                    Toast.makeText(context, "Saved $name to Friends", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onChatClicked = { onPeerMessageClicked(peer.hash) }
                        )
                    }
                }
            }
        } else {
            // Starred Friends Tab
            if (friends.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No saved friends yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Star nearby nodes or tap the + button to save contacts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(friends, key = { index, friend -> "${friend.destinationHash}_$index" }) { _, friend ->
                        val livePeer = peers.find { it.hash.equals(friend.destinationHash, ignoreCase = true) }
                        val hops = livePeer?.hops ?: friend.hops
                        val timestamp = livePeer?.timestamp ?: friend.lastSeenTimestamp

                        NetworkPeerCard(
                            displayName = friend.displayName,
                            hash = friend.destinationHash,
                            hops = hops,
                            timestamp = timestamp,
                            isFriend = true,
                            onToggleFriend = {
                                dialogDestHash = friend.destinationHash
                                dialogDisplayName = friend.displayName
                                dialogHops = friend.hops
                                isEditingExistingFriend = true
                                showFriendDialog = true
                            },
                            onChatClicked = { onPeerMessageClicked(friend.destinationHash) }
                        )
                    }
                }
            }
        }

        if (showTxPowerDialog) {
            var selectedPower by remember { mutableIntStateOf(txPower) }
            val presets = listOf(
                7 to "7 dBm (Low / Stealth)",
                14 to "14 dBm (Medium Power)",
                17 to "17 dBm (Default / Standard)",
                20 to "20 dBm (High Power)",
                22 to "22 dBm (Maximum / Long Range)"
            )
            AlertDialog(
                onDismissRequest = { showTxPowerDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("RNode Radio TX Power", style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Select LoRa radio transmit power for your Heltec / RNode hardware. Applied dynamically over KISS command 0x03 without restarting Reticulum.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        presets.forEach { (powerVal, label) ->
                            val isSelected = selectedPower == powerVal
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedPower = powerVal }
                                    .testTag("tx_power_preset_$powerVal"),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (powerVal == 17) {
                                            Text(
                                                text = "Default / standard preset",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedPower = powerVal }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onSetTxPower(selectedPower)
                            showTxPowerDialog = false
                            Toast.makeText(context, "TX power set to $selectedPower dBm", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("confirm_tx_power_button")
                    ) {
                        Text("Apply Live")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTxPowerDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showFriendDialog) {
            AlertDialog(
                onDismissRequest = { showFriendDialog = false },
                title = {
                    Text(
                        text = if (isEditingExistingFriend) "Edit Saved Contact" else "Save Contact / Friend",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = dialogDestHash,
                            onValueChange = { if (!isEditingExistingFriend) dialogDestHash = it },
                            label = { Text("Destination Hash") },
                            placeholder = { Text("16-byte hex hash...") },
                            singleLine = true,
                            readOnly = isEditingExistingFriend,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        )
                        OutlinedTextField(
                            value = dialogDisplayName,
                            onValueChange = { dialogDisplayName = it },
                            label = { Text("Contact Name / Callsign") },
                            placeholder = { Text("e.g. Alice, Base Station, Gateway 1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (dialogDestHash.isNotBlank()) {
                                onSaveFriend(dialogDestHash.trim(), dialogDisplayName.trim(), dialogHops)
                                showFriendDialog = false
                            }
                        },
                        enabled = dialogDestHash.isNotBlank()
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    if (isEditingExistingFriend) {
                        TextButton(
                            onClick = {
                                onDeleteFriend(dialogDestHash.trim())
                                showFriendDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete")
                        }
                    } else {
                        TextButton(onClick = { showFriendDialog = false }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }

    // Floating Action Button anchored at bottom-start (bottom-left, above bottom nav)
    ExtendedFloatingActionButton(
        onClick = {
            DiagnosticLogger.logUi("Announce triggered by user")
            Toast.makeText(context, "Broadcasting Announce on 915.0 MHz...", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                Log.d("AnnounceAction", "Announce FAB clicked, invoking mesh announce...")
                val success = meshManager.sendAnnounce()
                Log.d("AnnounceAction", "meshManager.sendAnnounce returned: $success")
                DiagnosticLogger.logUi("meshManager.sendAnnounce returned: $success")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Campaign,
                contentDescription = "Announce"
            )
        },
        text = {
            Text(
                text = "Announce",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = 16.dp)
            .testTag("announce_fab")
    )
}
}

data class ParsedMessageContent(
    val nick: String?,
    val displayBody: String,
    val isExplicitDm: Boolean
)

fun parseMessageContent(rawContent: String): ParsedMessageContent {
    var text = rawContent.trim()
    var isDm = false

    // Check and strip [DM] or [Private] tags
    if (text.startsWith("[DM]", ignoreCase = true)) {
        isDm = true
        text = text.substring(4).trim()
    } else if (text.startsWith("[Private]", ignoreCase = true)) {
        isDm = true
        text = text.substring(9).trim()
    }

    // Check for nick tag: <nick> or <nick>: at start of body
    var extractedNick: String? = null
    val nickRegex = Regex("""^<([^>]+)>\s*(?::\s*)?(.*)$""", RegexOption.DOT_MATCHES_ALL)
    val match = nickRegex.find(text)
    if (match != null) {
        extractedNick = match.groupValues[1].trim()
        text = match.groupValues[2].trim()
    }

    // Secondary check if [DM] or [Private] appeared after <nick>
    if (text.startsWith("[DM]", ignoreCase = true)) {
        isDm = true
        text = text.substring(4).trim()
    } else if (text.startsWith("[Private]", ignoreCase = true)) {
        isDm = true
        text = text.substring(9).trim()
    }

    return ParsedMessageContent(
        nick = extractedNick,
        displayBody = text.ifEmpty { rawContent },
        isExplicitDm = isDm
    )
}

@Composable
fun MessageBubble(
    msg: MeshMessage,
    friends: List<FriendEntity>,
    modifier: Modifier = Modifier
) {
    val isOut = !msg.isIncoming
    val parsed = remember(msg.content) { parseMessageContent(msg.content) }
    val senderFriend = remember(msg.peer, friends) {
        val clean = DirectMessageRepository.normalizeHash(msg.peer)
        friends.find { DirectMessageRepository.normalizeHash(it.destinationHash) == clean }
    }

    // Format sender display name cleanly
    val rawSenderName = when {
        senderFriend != null -> senderFriend.displayName
        !parsed.nick.isNullOrBlank() -> parsed.nick
        msg.peer.isNotBlank() -> msg.peer.take(8)
        else -> "Peer"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOut) 16.dp else 4.dp,
                bottomEnd = if (isOut) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isOut) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color(0xFF2A3447) // Distinct 1-on-1 DM container color
                }
            ),
            border = if (!isOut) {
                androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.5f))
            } else null,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (msg.isIncoming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = if (senderFriend != null) "★ $rawSenderName" else rawSenderName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = if (senderFriend != null) Color(0xFFFFB300) else Color(0xFFA855F7)
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Direct Message",
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "LXMF 1-1",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color(0xFFA855F7)
                            )
                        }
                    }
                }

                // Message Body with clean text
                Text(
                    text = if (isOut) msg.content else parsed.displayBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOut) MaterialTheme.colorScheme.onPrimaryContainer
                            else Color(0xFFF1F5F9)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom timestamp and status label
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isOut) MaterialTheme.colorScheme.outline else Color(0xFF94A3B8)
                    )
                    Text(
                        text = "[${msg.status}]",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = when {
                            isOut && msg.status == "Delivered" -> Color(0xFF00C853)
                            isOut && msg.status == "Sent" -> MaterialTheme.colorScheme.primary
                            !isOut -> Color(0xFFA855F7)
                            else -> Color(0xFFFFAB00)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MessagesTabScreen(
    peers: List<DiscoveredPeer>,
    messages: List<MeshMessage>,
    activePeerHash: String,
    onActivePeerChange: (String) -> Unit,
    friends: List<FriendEntity> = emptyList(),
    onSaveFriend: (destinationHash: String, displayName: String, hops: Int) -> Unit = { _, _, _ -> },
    isSending: Boolean = false,
    onSendMessage: (destHash: String, content: String) -> Unit,
    onAnnounce: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var destinationInput by remember { mutableStateOf(activePeerHash) }
    var isHashExpanded by remember { mutableStateOf(false) }
    var isNewPeerMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(activePeerHash) {
        destinationInput = activePeerHash
        if (activePeerHash.isNotBlank()) {
            val clean = DirectMessageRepository.normalizeHash(activePeerHash)
            val isKnown = friends.any { DirectMessageRepository.normalizeHash(it.destinationHash) == clean } ||
                    peers.any { DirectMessageRepository.normalizeHash(it.hash) == clean }
            isNewPeerMode = !isKnown
        }
    }

    val cleanInput = remember(destinationInput) { DirectMessageRepository.normalizeHash(destinationInput) }

    val activeFriend = remember(cleanInput, friends) {
        if (cleanInput.isNotBlank()) {
            friends.find { DirectMessageRepository.normalizeHash(it.destinationHash) == cleanInput }
        } else null
    }
    val activeKnownPeer = remember(cleanInput, peers) {
        if (cleanInput.isNotBlank()) {
            peers.find { DirectMessageRepository.normalizeHash(it.hash) == cleanInput }
        } else null
    }

    // Pure LXMF 1-on-1 messages for selected peer or all direct messages
    val filteredMessages = remember(messages, cleanInput) {
        if (cleanInput.isNotBlank()) {
            messages.filter { msg ->
                val cleanPeer = DirectMessageRepository.normalizeHash(msg.peer)
                cleanPeer.equals(cleanInput, ignoreCase = true)
            }
        } else {
            messages
        }
    }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            try {
                listState.animateScrollToItem(filteredMessages.size - 1)
            } catch (_: Exception) {}
        }
    }

    // Direct LXMF contact targets (Friends prioritized, then discovered peers)
    val contactPeers = remember(peers, friends) {
        val list = mutableListOf<DiscoveredPeer>()
        friends.forEach { f ->
            val cleanFHash = DirectMessageRepository.normalizeHash(f.destinationHash)
            val matchingPeer = peers.find { DirectMessageRepository.normalizeHash(it.hash) == cleanFHash || it.name.equals(f.displayName, ignoreCase = true) }
            val activeHash = matchingPeer?.hash ?: f.destinationHash
            list.add(
                DiscoveredPeer(
                    hash = activeHash,
                    name = f.displayName,
                    hops = matchingPeer?.hops ?: f.hops,
                    timestamp = matchingPeer?.timestamp ?: f.lastSeenTimestamp
                )
            )
        }
        peers.forEach { p ->
            val cleanPHash = DirectMessageRepository.normalizeHash(p.hash)
            if (cleanPHash.isNotBlank() && list.none { DirectMessageRepository.normalizeHash(it.hash) == cleanPHash }) {
                list.add(p)
            }
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Horizontal Carousel for Contact Targets & "New Peer" Option
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Carousel Item 0: "New Peer" option
            item(key = "new_peer_carousel_item") {
                val isSelected = isNewPeerMode || (cleanInput.isNotBlank() && activeFriend == null && activeKnownPeer == null)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isNewPeerMode = true
                        isHashExpanded = true
                        if (activeFriend != null || activeKnownPeer != null) {
                            destinationInput = ""
                            onActivePeerChange("")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Peer",
                            modifier = Modifier.size(16.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        )
                    },
                    label = {
                        Text(
                            "New Peer",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            // Carousel Items: Friends & Discovered Peers
            itemsIndexed(contactPeers, key = { index, p -> "${p.hash}_$index" }) { _, p ->
                val cleanPHash = DirectMessageRepository.normalizeHash(p.hash)
                val isSelected = !isNewPeerMode && cleanInput.isNotBlank() && cleanInput.equals(cleanPHash, ignoreCase = true)
                val isFriend = friends.any { DirectMessageRepository.normalizeHash(it.destinationHash) == cleanPHash }
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isNewPeerMode = false
                        destinationInput = p.hash
                        onActivePeerChange(p.hash)
                    },
                    leadingIcon = {
                        if (isFriend) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            text = p.name.ifBlank { p.hash.take(8) },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Active Contact Header with Expandable Hash / New Peer Input
        if (isNewPeerMode || (cleanInput.isNotBlank() && activeFriend == null && activeKnownPeer == null)) {
            // Offer to message new peer: Expandable hash entry card
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Message New Peer",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val pasted = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                                        if (pasted.isNotEmpty()) {
                                            destinationInput = pasted
                                            onActivePeerChange(pasted)
                                            Toast.makeText(context, "Pasted destination hash", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paste", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(
                                onClick = { isHashExpanded = !isHashExpanded },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isHashExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle Hash Input"
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isHashExpanded) {
                        Column(modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedTextField(
                                value = destinationInput,
                                onValueChange = {
                                    destinationInput = it
                                    onActivePeerChange(it)
                                },
                                label = { Text("Peer Destination Hash (32-char hex)") },
                                placeholder = { Text("e.g. 9b6e8a4d...") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (destinationInput.isNotEmpty()) {
                                        IconButton(onClick = {
                                            destinationInput = ""
                                            onActivePeerChange("")
                                        }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else if (cleanInput.isNotBlank()) {
            // On a Friend or Discovered Peer: Compact bar with expandable hash option
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Contact Info
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (activeFriend != null) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = activeFriend.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "(${activeKnownPeer?.hops ?: activeFriend.hops} hops)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val candidateName = activeKnownPeer?.name?.ifBlank { "Node ${cleanInput.take(8)}" } ?: "Node ${cleanInput.take(8)}"
                                Text(
                                    text = candidateName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "(${activeKnownPeer?.hops ?: 1} hops)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                TextButton(
                                    onClick = {
                                        onSaveFriend(cleanInput, candidateName, activeKnownPeer?.hops ?: 1)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Add", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Expandable Hash Option Button
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isHashExpanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { isHashExpanded = !isHashExpanded }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isHashExpanded) "Hide Hash" else "Hash",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isHashExpanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = if (isHashExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Expand Hash",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isHashExpanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Expandable Destination Hash details with Copy Action
                    AnimatedVisibility(visible = isHashExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cleanInput,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Peer Destination Hash", cleanInput)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Full hash copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy Hash",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Messages Bubble Feed
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp)
        ) {
            if (filteredMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val emptyNotice = when {
                            activeFriend != null -> "No messages yet with ${activeFriend.displayName}.\nSend an LXMF 1-on-1 packet below."
                            cleanInput.isNotBlank() -> "No messages yet with ${cleanInput.take(8)}.\nSend an LXMF 1-on-1 packet below."
                            else -> "1-on-1 LXMF Messenger\nSelect a peer from above or enter a destination hash to start chatting."
                        }
                        Text(
                            text = emptyNotice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filteredMessages, key = { index, msg -> "${msg.id}_${msg.timestamp}_$index" }) { _, msg ->
                        MessageBubble(msg = msg, friends = friends)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Direct LXMF Send Handler
        val handleSend: () -> Unit = {
            if (!isSending) {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    inputText = ""
                    if (text.startsWith("/announce")) {
                        onAnnounce()
                    } else if (cleanInput.isNotBlank()) {
                        onSendMessage(cleanInput, text)
                    }
                }
            }
        }

        // Bottom Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        if (activeFriend != null) "Message ${activeFriend.displayName}..."
                        else if (cleanInput.isNotBlank()) "Message ${cleanInput.take(8)}..."
                        else "Select a peer or enter hash to send..."
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { handleSend() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { handleSend() },
                enabled = inputText.isNotBlank() && (cleanInput.isNotBlank() || inputText.startsWith("/announce")) && !isSending,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

enum class StumpSubTab(val title: String) {
    CHAT("RRC Chat"),
    BILLBOARD("Billboard"),
    FILES("SD Files")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StumpTabScreen(
    context: Context,
    eventLog: List<String>,
    stumpClient: StumpHttpClient,
    stumpUrl: String,
    onStumpUrlChange: (String) -> Unit,
    stumpReachable: Boolean?,
    onStumpReachableChange: (Boolean?) -> Unit,
    isScanning: Boolean,
    onIsScanningChange: (Boolean) -> Unit,
    scanProgress: Int,
    onScanProgressChange: (Int) -> Unit,
    scanTotal: Int,
    onScanTotalChange: (Int) -> Unit,
    scanStatusText: String,
    onScanStatusTextChange: (String) -> Unit,
    rrcMessages: List<RrcMessage>,
    onRrcMessagesChange: (List<RrcMessage>) -> Unit,
    activeRoom: String,
    onActiveRoomChange: (String) -> Unit,
    availableRooms: List<String>,
    onAvailableRoomsChange: (List<String>) -> Unit,
    sinceId: Long,
    onSinceIdChange: (Long) -> Unit,
    discoveredPeers: List<MeshPeer> = emptyList(),
    activeChatPeerHash: String = "",
    activeUsers: List<String> = emptyList(),
    onActiveUsersChange: (List<String>) -> Unit = {},
    onSendLoraMessage: (String, String) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()

    var selectedSubTab by remember { mutableStateOf(StumpSubTab.CHAT) }
    var isChecking by remember { mutableStateOf(false) }
    var showNodeSettingsDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Ultra-clean compact top bar for Stump Node: Status + Refresh + Settings Gear
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Connection status dot and node address/label (clickable to open settings)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable { showNodeSettingsDialog = true }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (stumpReachable) {
                                    true -> Color(0xFF2E7D32)
                                    false -> Color(0xFFC62828)
                                    null -> Color.Gray
                                }
                            )
                    )
                    Text(
                        text = if (stumpUrl.isNotBlank()) stumpUrl.removePrefix("http://").removePrefix("https://") else "No Node IP",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (stumpReachable) {
                            true -> Color(0xFFE8F5E9)
                            false -> Color(0xFFFFEBEE)
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = when (stumpReachable) {
                                true -> "Connected"
                                false -> "Offline"
                                null -> "Unchecked"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = when (stumpReachable) {
                                true -> Color(0xFF2E7D32)
                                false -> Color(0xFFC62828)
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                // Actions: Ping/Refresh + Settings Gear Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            isChecking = true
                            coroutineScope.launch {
                                val reachable = stumpClient.checkReachability(stumpUrl)
                                onStumpReachableChange(reachable)
                                isChecking = false
                            }
                        },
                        enabled = !isChecking && !isScanning,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Check Connection",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showNodeSettingsDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Stump Node Settings",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Sub-tabs Navigation
        TabRow(
            selectedTabIndex = selectedSubTab.ordinal
        ) {
            StumpSubTab.entries.forEach { tab ->
                Tab(
                    selected = selectedSubTab == tab,
                    onClick = { selectedSubTab = tab },
                    text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = {
                        when (tab) {
                            StumpSubTab.CHAT -> Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            StumpSubTab.BILLBOARD -> Icon(Icons.Default.Announcement, contentDescription = null, modifier = Modifier.size(18.dp))
                            StumpSubTab.FILES -> Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                )
            }
        }

        // Sub-tab screens
        Box(modifier = Modifier.weight(1f)) {
            when (selectedSubTab) {
                StumpSubTab.CHAT -> RrcChatSubScreen(
                    context = context,
                    stumpClient = stumpClient,
                    stumpUrl = stumpUrl,
                    stumpReachable = stumpReachable,
                    rrcMessages = rrcMessages,
                    onRrcMessagesChange = onRrcMessagesChange,
                    activeRoom = activeRoom,
                    onActiveRoomChange = onActiveRoomChange,
                    availableRooms = availableRooms,
                    onAvailableRoomsChange = onAvailableRoomsChange,
                    sinceId = sinceId,
                    onSinceIdChange = onSinceIdChange,
                    onReachabilityChanged = { onStumpReachableChange(it) },
                    discoveredPeers = discoveredPeers,
                    activeChatPeerHash = activeChatPeerHash,
                    activeUsers = activeUsers,
                    onActiveUsersChange = onActiveUsersChange,
                    onSendLoraMessage = onSendLoraMessage
                )
                StumpSubTab.BILLBOARD -> BillboardSubScreen(
                    context = context,
                    stumpClient = stumpClient
                )
                StumpSubTab.FILES -> FservFilesSubScreen(
                    context = context,
                    stumpClient = stumpClient
                )
            }
        }
    }

    // Stump Node Settings Dialog (IP URL and Auto-Detect feature transported under gear icon)
    if (showNodeSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showNodeSettingsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Stump Node Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Configure the HTTP connection to your local Stump mesh node or auto-detect it on your WiFi subnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Node IP / URL input
                    OutlinedTextField(
                        value = stumpUrl,
                        onValueChange = onStumpUrlChange,
                        label = { Text("Node Address / IP URL") },
                        placeholder = { Text("http://192.168.4.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        leadingIcon = {
                            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (stumpUrl.isNotEmpty()) {
                                IconButton(onClick = { onStumpUrlChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    // Auto-Detect Feature Button with Progress
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                onIsScanningChange(true)
                                onScanProgressChange(0)
                                onScanTotalChange(254)
                                onScanStatusTextChange("Probing subnet...")
                                coroutineScope.launch {
                                    try {
                                        val discoveredIp = stumpClient.autoDiscover(context) { scanned, total ->
                                            onScanProgressChange(scanned)
                                            onScanTotalChange(total)
                                            onScanStatusTextChange("Scanning $scanned/$total")
                                        }
                                        if (discoveredIp != null) {
                                            val discoveredUrl = "http://$discoveredIp"
                                            onStumpUrlChange(discoveredUrl)
                                            onStumpReachableChange(true)
                                            Toast.makeText(context, "Found Stump Node: $discoveredIp", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "No node found on subnet", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        onIsScanningChange(false)
                                        onScanStatusTextChange("")
                                    }
                                }
                            },
                            enabled = !isScanning && !isChecking,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val pct = if (scanTotal > 0) "${scanProgress * 100 / scanTotal}%" else "..."
                                Text("Auto-Detecting ($pct)")
                            } else {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Auto-Detect on Subnet")
                            }
                        }

                        if (isScanning) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (scanTotal > 0) (scanProgress.toFloat() / scanTotal).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (scanStatusText.isNotBlank()) {
                                Text(
                                    text = scanStatusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // Test reachability button
                    OutlinedButton(
                        onClick = {
                            isChecking = true
                            coroutineScope.launch {
                                val reachable = stumpClient.checkReachability(stumpUrl)
                                onStumpReachableChange(reachable)
                                isChecking = false
                                if (reachable) {
                                    Toast.makeText(context, "Connection verified!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not reach node at $stumpUrl", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isChecking && !isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing...")
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Connection")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNodeSettingsDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
fun RrcChatSubScreen(
    context: Context,
    stumpClient: StumpHttpClient,
    stumpUrl: String,
    stumpReachable: Boolean? = null,
    rrcMessages: List<RrcMessage>,
    onRrcMessagesChange: (List<RrcMessage>) -> Unit,
    activeRoom: String,
    onActiveRoomChange: (String) -> Unit,
    availableRooms: List<String>,
    onAvailableRoomsChange: (List<String>) -> Unit,
    sinceId: Long,
    onSinceIdChange: (Long) -> Unit,
    onReachabilityChanged: (Boolean) -> Unit,
    discoveredPeers: List<MeshPeer> = emptyList(),
    activeChatPeerHash: String = "",
    activeUsers: List<String> = emptyList(),
    onActiveUsersChange: (List<String>) -> Unit = {},
    onSendLoraMessage: (String, String) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()
    var currentNick by remember { mutableStateOf("") }
    var currentTopic by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinRoomInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Polling loop in a coroutine: 2000ms base interval, doubling on failure up to 30s with 0–30% random jitter, resetting to 2000ms on success
    LaunchedEffect(stumpUrl, activeRoom) {
        stumpClient.baseUrl = stumpUrl
        val cleanRoom = activeRoom.trim().removePrefix("#")
        var currentInterval = 2000L
        while (isActive) {
            try {
                val res = stumpClient.pollRrc(cleanRoom, sinceId)
                if (res.isSuccess) {
                    val poll = res.getOrThrow()
                    onReachabilityChanged(true)
                    if (poll.nick.isNotBlank()) currentNick = poll.nick
                    currentTopic = poll.topic
                    if (poll.rooms.isNotEmpty()) {
                        val cleanedRooms = poll.rooms.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                        val union = (availableRooms + cleanedRooms).distinct()
                        if (union != availableRooms) onAvailableRoomsChange(union)
                    }
                    onActiveUsersChange(poll.users)

                    val maxId = (poll.messages + poll.dms).map { it.id }.maxOrNull() ?: 0L
                    if (maxId > sinceId) onSinceIdChange(maxId)

                    // All RRC messages (room messages and whispers/dms) stay right inside RrcChatSubScreen
                    val allRrcIncoming = poll.messages + poll.dms
                    if (allRrcIncoming.isNotEmpty()) {
                        val existingIds = rrcMessages.map { it.id }.filter { it > 0 }.toSet()
                        val fresh = allRrcIncoming.filter { it.id == 0L || it.id !in existingIds }
                        if (fresh.isNotEmpty()) {
                            val merged = (rrcMessages + fresh).distinctBy { if (it.id > 0) it.id else "${it.ts}_${it.nick}_${it.body}" }
                            onRrcMessagesChange(merged.sortedBy { if (it.id > 0) it.id.toDouble() else it.ts })
                        }
                    }
                    currentInterval = 2000L
                    delay(currentInterval)
                } else {
                    onReachabilityChanged(false)
                    val jitterMs = (Math.random() * 0.3 * currentInterval).toLong()
                    delay(currentInterval + jitterMs)
                    currentInterval = minOf(30000L, currentInterval * 2)
                }
            } catch (e: Exception) {
                Log.e("RrcChat", "RRC polling error: ${e.message}", e)
                onReachabilityChanged(false)
                val jitterMs = (Math.random() * 0.3 * currentInterval).toLong()
                delay(currentInterval + jitterMs)
                currentInterval = minOf(30000L, currentInterval * 2)
            }
        }
    }

    LaunchedEffect(rrcMessages.size) {
        if (rrcMessages.isNotEmpty()) {
            try {
                listState.animateScrollToItem(rrcMessages.size - 1)
            } catch (_: Exception) {}
        }
    }

    // Function to switch rooms cleanly:
    // 1. Immediately send POST /rrc/send with raw command line /join <clean_room_name>
    // 2. Wait for response, update the current room, and immediately trigger pollRrc(clean_room_name, sinceId = 0)
    fun switchRoom(targetRoom: String) {
        val cleanRoom = targetRoom.trim().removePrefix("#")
        if (cleanRoom.isEmpty()) return
        if (isSending) return
        isSending = true
        coroutineScope.launch {
            try {
                val sendRes = stumpClient.sendRrc("/join $cleanRoom")
                if (sendRes.isSuccess) {
                    val resp = sendRes.getOrThrow()
                    val finalRoom = (resp.room?.trim()?.removePrefix("#")) ?: cleanRoom
                    onActiveRoomChange(finalRoom)
                    if (!availableRooms.contains(finalRoom)) {
                        onAvailableRoomsChange((availableRooms + finalRoom).distinct())
                    }
                    onSinceIdChange(0L)
                    onRrcMessagesChange(emptyList())

                    // Immediately poll the new room with sinceId = 0
                    val pollRes = stumpClient.pollRrc(finalRoom, 0L)
                    if (pollRes.isSuccess) {
                        val poll = pollRes.getOrThrow()
                        onReachabilityChanged(true)
                        if (poll.nick.isNotBlank()) currentNick = poll.nick
                        currentTopic = poll.topic
                        if (poll.rooms.isNotEmpty()) {
                            val cleanedRooms = poll.rooms.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                            val union = (availableRooms + cleanedRooms).distinct()
                            if (union != availableRooms) onAvailableRoomsChange(union)
                        }
                        onActiveUsersChange(poll.users)

                        val incoming = poll.messages + poll.dms
                        if (incoming.isNotEmpty()) {
                            val maxId = incoming.maxOf { it.id }
                            onSinceIdChange(maxId)
                            val sorted = incoming.distinctBy { if (it.id > 0) it.id else "${it.ts}_${it.nick}_${it.body}" }
                                .sortedBy { if (it.id > 0) it.id.toDouble() else it.ts }
                            onRrcMessagesChange(sorted)
                        }
                    }
                } else {
                    val err = sendRes.exceptionOrNull()?.message ?: "Failed to join room"
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Room switch error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSending = false
            }
        }
    }

    fun submitSend() {
        try {
            val text = chatInput.trim()
            if (text.isEmpty() || isSending) return

            // Intercept /j or /join typed by the user to use switchRoom
            if (text.startsWith("/join ", ignoreCase = true) || text.startsWith("/j ", ignoreCase = true)) {
                val parts = text.split("\\s+".toRegex(), limit = 2)
                if (parts.size > 1) {
                    val target = parts[1].trim().removePrefix("#")
                    chatInput = ""
                    switchRoom(target)
                    return
                }
            }

            chatInput = ""

            // Local echo so the message renders cleanly in the Stump chat feed immediately
            val localEcho = RrcMessage(
                id = System.currentTimeMillis(),
                ts = System.currentTimeMillis() / 1000.0,
                nick = if (currentNick.isNotBlank()) currentNick else "You",
                body = text,
                kind = "msg"
            )
            onRrcMessagesChange((rrcMessages + localEcho).distinctBy { if (it.id > 0) it.id else "${it.ts}_${it.nick}_${it.body}" })

            if (stumpReachable == true) {
                // WiFi mode: dispatch cleanly via stumpClient.sendRrc(text)
                isSending = true
                coroutineScope.launch {
                    try {
                        val res = stumpClient.sendRrc(text)
                        if (res.isSuccess) {
                            val resp = res.getOrThrow()
                            val cleanRespRoom = resp.room?.trim()?.removePrefix("#")
                            if (!cleanRespRoom.isNullOrBlank() && cleanRespRoom != activeRoom) {
                                onActiveRoomChange(cleanRespRoom)
                                if (!availableRooms.contains(cleanRespRoom)) {
                                    onAvailableRoomsChange((availableRooms + cleanRespRoom).distinct())
                                }
                                onSinceIdChange(0L)
                                onRrcMessagesChange(emptyList())
                            }
                            if (resp.replies.isNotEmpty()) {
                                val replyMsgs = resp.replies.map { rep ->
                                    RrcMessage(
                                        id = 0L,
                                        ts = System.currentTimeMillis() / 1000.0,
                                        nick = "server",
                                        body = rep,
                                        kind = "system"
                                    )
                                }
                                onRrcMessagesChange((rrcMessages + replyMsgs).distinctBy { if (it.id > 0) it.id else "${it.ts}_${it.nick}_${it.body}" })
                            }
                        } else {
                            val err = res.exceptionOrNull()?.message ?: "Failed to send"
                            Log.e("RrcChat", "HTTP send error: $err")
                        }
                    } catch (e: Exception) {
                        Log.e("RrcChat", "WiFi send error: ${e.message}")
                    } finally {
                        isSending = false
                    }
                }
            } else {
                // LoRa mode
                val fullStumpHash = discoveredPeers.firstOrNull {
                    DirectMessageRepository.isStumpHash(it.hash)
                }?.hash ?: MeshService.resolveLiveStumpHash() ?: (if (activeChatPeerHash.length == 32 && DirectMessageRepository.isStumpHash(activeChatPeerHash)) activeChatPeerHash else "")

                if (fullStumpHash.length == 32) {
                    try {
                        onSendLoraMessage(fullStumpHash, text)
                    } catch (e: Exception) {
                        Log.e("RrcChat", "LoRa send error: ${e.message}")
                        Toast.makeText(context, "Send error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Waiting for Stump node announcement over LoRa...", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("RrcChat", "Error in submitSend: ${e.message}", e)
            Toast.makeText(context, "Send error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Channel Header labeled "stump-public (#lxmf)"
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "stump-lxmf",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Room #${activeRoom.trim().removePrefix("#")} • LoRa Mesh Default",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val currentStumpPrefix = (MeshService.resolveLiveStumpHash() ?: activeChatPeerHash).take(8).ifEmpty { "stump" }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Node: $currentStumpPrefix",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Room switcher chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(availableRooms) { room ->
                val cleanRoom = room.trim().removePrefix("#")
                FilterChip(
                    selected = activeRoom.trim().removePrefix("#") == cleanRoom,
                    onClick = {
                        if (activeRoom.trim().removePrefix("#") != cleanRoom) {
                            switchRoom(cleanRoom)
                        }
                    },
                    label = { Text("#$cleanRoom") },
                    leadingIcon = if (activeRoom.trim().removePrefix("#") == cleanRoom) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    } else null
                )
            }

            item {
                AssistChip(
                    onClick = {
                        joinRoomInput = ""
                        showJoinDialog = true
                    },
                    label = { Text("+ Join Room") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
            }
        }

        // Channel topic & Users Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (currentTopic.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = currentTopic,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Active Users Chips
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Users (${activeUsers.size}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(activeUsers) { user ->
                            SuggestionChip(
                                onClick = {
                                    chatInput = "/m $user "
                                },
                                label = { Text(user, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(26.dp)
                            )
                        }
                    }
                }
            }
        }

        // Live Chat Feed
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (rrcMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(36.dp), tint = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No messages yet in #$activeRoom",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Connected to Stump RRC server. Say hello!",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            itemsIndexed(rrcMessages, key = { index, msg -> "${if (msg.id > 0) msg.id else (msg.ts * 1000).toLong()}_${msg.nick}_$index" }) { _, msg ->
                when (msg.kind) {
                    "action" -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DirectionsRun,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "* ${msg.nick} ${msg.body}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        }
                    }
                    "system" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "* ${msg.body}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    "dm" -> {
                        Surface(
                            color = Color(0xFF2A3447),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "DM",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "★ ${msg.nick} • 🔒 Direct Message",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF38BDF8)
                                        )
                                        if (msg.ts > 0) {
                                            Text(
                                                text = formatTime(msg.ts),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = msg.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFF1F5F9)
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        // Standard chat message
                        val isSelf = currentNick.isNotBlank() && msg.nick.equals(currentNick, ignoreCase = true)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelf) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = msg.nick,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSelf) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                        )
                                        if (msg.ts > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = formatTime(msg.ts),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = msg.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelf) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Slash Command Suggestions
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val quickCommands = listOf("/nick ", "/j ", "/m ", "/topic ", "/help")
            items(quickCommands) { cmd ->
                SuggestionChip(
                    onClick = {
                        chatInput = cmd
                    },
                    label = { Text(cmd.trim(), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        // Input row
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    placeholder = { Text("Message or /command...", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { submitSend() },
                    enabled = chatInput.isNotBlank() && !isSending,
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join Room") },
            text = {
                Column {
                    Text("Enter room name to join:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = joinRoomInput,
                        onValueChange = { joinRoomInput = it },
                        singleLine = true,
                        placeholder = { Text("e.g. meshops") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = joinRoomInput.trim().removePrefix("#")
                        if (trimmed.isNotEmpty()) {
                            switchRoom(trimmed)
                            showJoinDialog = false
                        }
                    }
                ) {
                    Text("Join")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BillboardSubScreen(
    context: Context,
    stumpClient: StumpHttpClient
) {
    val coroutineScope = rememberCoroutineScope()
    var billboardPosts by remember { mutableStateOf(listOf<BillboardPost>()) }
    var isLoading by remember { mutableStateOf(false) }
    var showNewPostDialog by remember { mutableStateOf(false) }
    var newPostContent by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }

    fun refreshPosts() {
        isLoading = true
        coroutineScope.launch {
            val res = stumpClient.fetchBillboardPosts()
            if (res.isSuccess) {
                billboardPosts = res.getOrDefault(emptyList())
                Toast.makeText(context, "Billboard updated (${billboardPosts.size} posts)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Billboard fetch error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshPosts()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Stump Billboard",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${billboardPosts.size} notices posted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = {
                            newPostContent = ""
                            showNewPostDialog = true
                        }
                    ) {
                        Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Post", style = MaterialTheme.typography.labelSmall)
                    }

                    IconButton(
                        onClick = { refreshPosts() },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            }
        }

        if (billboardPosts.isEmpty() && !isLoading) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Announcement, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No Billboard Notices Found", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Connect to the Stump Access Point and tap 'New Post' to publish a notice to all mesh users.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(billboardPosts) { post ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Notice",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Billboard Post", "${post.text}\n— ${post.signature}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied notice to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = post.text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )

                    if (post.signature.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "— ${post.signature}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewPostDialog) {
        AlertDialog(
            onDismissRequest = { if (!isPosting) showNewPostDialog = false },
            title = { Text("Publish Billboard Notice") },
            text = {
                Column {
                    Text(
                        "Notices are broadcast publicly on the Stump node and visible to all connected users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPostContent,
                        onValueChange = { newPostContent = it },
                        label = { Text("Notice Content") },
                        placeholder = { Text("Enter public announcement...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = newPostContent.trim()
                        if (text.isEmpty()) return@Button
                        isPosting = true
                        coroutineScope.launch {
                            val res = stumpClient.postBillboardEntry(text)
                            if (res.isSuccess) {
                                Toast.makeText(context, "Notice posted to Billboard!", Toast.LENGTH_SHORT).show()
                                showNewPostDialog = false
                                newPostContent = ""
                                refreshPosts()
                            } else {
                                Toast.makeText(context, "Post failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                            isPosting = false
                        }
                    },
                    enabled = newPostContent.isNotBlank() && !isPosting
                ) {
                    if (isPosting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Publish")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewPostDialog = false },
                    enabled = !isPosting
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FservFilesSubScreen(
    context: Context,
    stumpClient: StumpHttpClient
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var downloadingFileName by remember { mutableStateOf<String?>(null) }

    var uploadStatusText by remember { mutableStateOf("") }
    var uploadStatusIsError by remember { mutableStateOf(false) }
    var uploadStatusIs507 by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }

    fun openDownloadedFile(targetFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
            val ext = targetFile.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshFilesList() {
        isLoadingFiles = true
        coroutineScope.launch {
            val res = stumpClient.fetchFileList()
            if (res.isSuccess) {
                files = res.getOrDefault(emptyList())
            } else {
                Toast.makeText(context, "Could not load files: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
            isLoadingFiles = false
        }
    }

    LaunchedEffect(Unit) {
        refreshFilesList()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isUploading = true
            uploadStatusText = "Preparing file..."
            uploadStatusIsError = false
            uploadStatusIs507 = false
            coroutineScope.launch {
                try {
                    var displayName = "file_${System.currentTimeMillis()}"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIdx)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                    }
                    val tempFile = File(context.cacheDir, displayName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    uploadStatusText = "Uploading $displayName (${tempFile.length()} bytes)..."
                    val res = stumpClient.uploadFile(tempFile, displayName)
                    if (res.isSuccess) {
                        val body = res.getOrDefault("Upload successful")
                        uploadStatusText = "Success: $displayName uploaded. ($body)"
                        uploadStatusIsError = false
                        uploadStatusIs507 = false
                        Toast.makeText(context, "Upload complete!", Toast.LENGTH_SHORT).show()
                        refreshFilesList()
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "Upload failed"
                        if (err.contains("507") || err.contains("Storage Full") || err.contains("limit reached")) {
                            uploadStatusText = "Storage Full: 75% limit reached"
                            uploadStatusIs507 = true
                            uploadStatusIsError = true
                        } else {
                            uploadStatusText = "Upload error: $err"
                            uploadStatusIs507 = false
                            uploadStatusIsError = true
                        }
                        Toast.makeText(context, uploadStatusText, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    uploadStatusText = "Upload error: ${e.message}"
                    uploadStatusIsError = true
                    uploadStatusIs507 = false
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isUploading = false
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // SD Storage Info Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SdStorage,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Stump SD Storage (fserv)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Upload raw files to the node's micro-SD card or download available files directly over local HTTP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Upload Card with Clean Status Chip
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Upload to Node Storage",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Select any local file. Filenames are sanitized automatically according to node specs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = !isUploading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Uploading...")
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose File to Upload")
                        }
                    }

                    if (uploadStatusText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Status chip for upload responses
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                uploadStatusIs507 -> Color(0xFFFFF3E0)
                                uploadStatusIsError -> MaterialTheme.colorScheme.errorContainer
                                else -> Color(0xFFE8F5E9)
                            },
                            contentColor = when {
                                uploadStatusIs507 -> Color(0xFFE65100)
                                uploadStatusIsError -> MaterialTheme.colorScheme.onErrorContainer
                                else -> Color(0xFF2E7D32)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                when {
                                    uploadStatusIs507 -> Color(0xFFFFB74D)
                                    uploadStatusIsError -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                    else -> Color(0xFFA5D6A7)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        uploadStatusIs507 -> Icons.Default.Warning
                                        uploadStatusIsError -> Icons.Default.ErrorOutline
                                        else -> Icons.Default.CheckCircle
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = uploadStatusText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Available Files Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Files on SD Card",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${files.size} file${if (files.size != 1) "s" else ""} available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { refreshFilesList() },
                    enabled = !isLoadingFiles
                ) {
                    if (isLoadingFiles) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Files")
                    }
                }
            }
        }

        // Available Files List or Empty State
        if (isLoadingFiles && files.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Reading files from SD card...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (files.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No Files Found on SD Card", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap Refresh or upload a file above to store it on the node.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(files) { filename ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = filename,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                if (downloadingFileName != null) return@FilledTonalButton
                                downloadingFileName = filename
                                coroutineScope.launch {
                                    val res = stumpClient.downloadFile(filename, context)
                                    if (res.isSuccess) {
                                        val destFile = res.getOrThrow()
                                        val successMsg = "Saved to Downloads/${destFile.name}"
                                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                                        val snackResult = snackbarHostState.showSnackbar(
                                            message = successMsg,
                                            actionLabel = "Open",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (snackResult == SnackbarResult.ActionPerformed) {
                                            openDownloadedFile(destFile)
                                        }
                                    } else {
                                        val err = res.exceptionOrNull()?.message ?: "Download failed"
                                        val errMsg = "Download error: $err"
                                        Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                                        snackbarHostState.showSnackbar(
                                            message = errMsg,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    downloadingFileName = null
                                }
                            },
                            enabled = downloadingFileName == null,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (downloadingFileName == filename) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Downloading...", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

private fun formatRelativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "Active"
    val diff = System.currentTimeMillis() - (epochSeconds * 1000L)
    if (diff < 60_000) return "Just now"
    val mins = diff / 60_000
    if (mins < 60) return "${mins}m ago"
    val hours = mins / 60
    if (hours < 24) return "${hours}h ago"
    return "${hours / 24}d ago"
}

private fun formatRelativeTime(epochSeconds: Double): String {
    return formatRelativeTime(epochSeconds.toLong())
}

private fun formatTime(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val date = Date((epochSeconds * 1000).toLong())
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(date)
}
