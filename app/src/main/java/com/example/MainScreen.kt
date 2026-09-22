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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
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
    val timestamp: Double
)

enum class AppTab(val title: String) {
    DISCOVERED("Discovered"),
    MESSAGES("Messages"),
    STUMP("Stump Node")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    usbBridge: UsbRNodeBridge,
    context: Context
) {
    val coroutineScope = rememberCoroutineScope()
    val bridgeState by usbBridge.bridgeState.collectAsState()
    val activeTransportType by usbBridge.activeTransportType.collectAsState()
    val lastError by usbBridge.lastError.collectAsState()
    val isUsbPluggedIn = usbBridge.isUsbDevicePluggedIn()

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

    // Auto-reconnect to last BLE node if USB is not present (5-second timeout)
    LaunchedEffect(Unit) {
        if (!usbBridge.isUsbDevicePluggedIn()) {
            val hasAll = bluetoothPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (hasAll) {
                usbBridge.autoReconnectBleIfAvailable(timeoutMs = 5000L)
            }
        }
    }

    // Auto-dismiss BLE sheet when connected
    LaunchedEffect(bridgeState) {
        if (bridgeState == BridgeState.ONLINE && showBleScannerSheet) {
            showBleScannerSheet = false
            val nodeName = usbBridge.bleTransport.connectedDeviceName ?: "Heltec RNode"
            Toast.makeText(context, "Connected to $nodeName", Toast.LENGTH_SHORT).show()
        }
    }

    var selectedTab by remember { mutableStateOf(AppTab.DISCOVERED) }
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
    var rrcActiveRoom by remember { mutableStateOf("main") }
    var rrcAvailableRooms by remember { mutableStateOf(listOf("main", "offtopic")) }
    var rrcSinceId by remember { mutableLongStateOf(0L) }

    suspend fun startRns() {
        if (isRnsStarting) return
        isRnsStarting = true
        withContext(Dispatchers.IO) {
            try {
                val hashStr = MeshService.startReticulum(context, "Android Node")
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

    // Auto-boot Reticulum when radio confirmed ONLINE
    LaunchedEffect(bridgeState) {
        if (bridgeState == BridgeState.ONLINE || bridgeState == BridgeState.RF_TRANSMITTING) {
            if (rnsHash.isEmpty() && !isRnsStarting) {
                startRns()
            }
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
                                }
                            } catch (e: Exception) {
                                // Log error or ignore transient parsing exception
                            }

                            // 3. Poll message history JSON
                            try {
                                val msgJson = rnsCore.callAttr("get_messages_json")?.toString()
                                if (!msgJson.isNullOrEmpty()) {
                                    val jsonArr = JSONArray(msgJson)
                                    val parsed = mutableListOf<MeshMessage>()
                                    for (i in 0 until jsonArr.length()) {
                                        val obj = jsonArr.getJSONObject(i)
                                        parsed.add(
                                            MeshMessage(
                                                id = obj.optString("id", i.toString()),
                                                peer = obj.optString("peer", ""),
                                                content = obj.optString("content", ""),
                                                isIncoming = obj.optBoolean("incoming", false),
                                                status = obj.optString("status", "Sent"),
                                                timestamp = obj.optDouble("time", 0.0)
                                            )
                                        )
                                    }
                                    withContext(Dispatchers.Main) {
                                        meshMessages = parsed
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
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                .clickable {
                                    if (bridgeState == BridgeState.DISCONNECTED) {
                                        if (usbBridge.isUsbDevicePluggedIn()) {
                                            usbBridge.startServerAndConnectUsb()
                                        } else {
                                            openBleScanner()
                                        }
                                    }
                                }
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
                            // Local Identity Hash (Click to copy)
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
                                            text = rnsHash.take(8),
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
                                    onClick = { coroutineScope.launch { startRns() } },
                                    enabled = !isRnsStarting
                                ) {
                                    Text(if (isRnsStarting) "Starting..." else "Start Mesh")
                                }
                            }

                            // Settings & Diagnostics Gear Icon
                            IconButton(
                                onClick = { showSettingsSheet = true },
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == AppTab.DISCOVERED,
                    onClick = { selectedTab = AppTab.DISCOVERED },
                    icon = {
                        BadgedBox(badge = {
                            if (discoveredPeers.isNotEmpty()) {
                                Badge { Text("${discoveredPeers.size}") }
                            }
                        }) {
                            Icon(Icons.Default.Radar, contentDescription = "Discovered")
                        }
                    },
                    label = { Text("Discovered") }
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
                AppTab.DISCOVERED -> DiscoveredTabScreen(
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

                AppTab.MESSAGES -> MessagesTabScreen(
                    peers = discoveredPeers,
                    messages = meshMessages,
                    activePeerHash = activeChatPeerHash,
                    onActivePeerChange = { activeChatPeerHash = it },
                    onSendMessage = { destHash, content ->
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                Python.getInstance().getModule("rns_core").callAttr("send_message", destHash, content)
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Send error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    onSinceIdChange = { rrcSinceId = it }
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
    onScanBluetoothClicked: () -> Unit = {},
    onDisconnectRadioClicked: () -> Unit = {},
    onForgetDeviceClicked: () -> Unit = {},
    onPeerMessageClicked: (String) -> Unit,
    onSendAnnounce: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val meshManager = remember(context) { MeshManager(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
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

                // Bottom metrics row with chips: 915.0 MHz | BW 125 | SF8 | CR 4/5 | TX 7 dBm
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val telemetryChips = listOf(
                        "915.0 MHz",
                        "BW 125",
                        "SF8",
                        "CR 4/5",
                        "TX 7 dBm"
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

        // Discovered Peers List Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Nearby Nodes (${peers.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Auto-updated via LoRa",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No nearby nodes discovered yet",
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
                items(peers, key = { it.hash }) { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Peer Info
                            Column(modifier = Modifier.weight(1f)) {
                                val displayName = if (peer.name.isNotBlank()) peer.name else "Node ${peer.hash.take(8)}"

                                // Callsign + Truncated Copyable Hash Chip (Clean Single-Line Alignment)
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

                                    // Single truncated, copyable hash chip
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Peer Destination Hash", peer.hash)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Copied hash: ${peer.hash.take(8)}...", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = "${peer.hash.take(8)}...",
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Hash",
                                                modifier = Modifier.size(10.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Aligned neatly under callsign: "1 hop" badge and timestamp
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = "${peer.hops} hop${if (peer.hops != 1) "s" else ""}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    val timeStr = formatRelativeTime(peer.timestamp)
                                    Text(
                                        text = timeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Vertically centered Message Action Button
                            FilledTonalButton(
                                onClick = { onPeerMessageClicked(peer.hash) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Message", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
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

@Composable
fun MessagesTabScreen(
    peers: List<DiscoveredPeer>,
    messages: List<MeshMessage>,
    activePeerHash: String,
    onActivePeerChange: (String) -> Unit,
    onSendMessage: (destHash: String, content: String) -> Unit,
    onAnnounce: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var destinationInput by remember { mutableStateOf(activePeerHash) }
    val listState = rememberLazyListState()

    // Sync active peer with destinationInput
    LaunchedEffect(activePeerHash) {
        if (activePeerHash.isNotEmpty()) {
            destinationInput = activePeerHash
        }
    }

    // Filter messages for active destination if selected, or show all
    val filteredMessages = remember(messages, destinationInput) {
        if (destinationInput.isBlank()) {
            messages
        } else {
            messages.filter { it.peer.contains(destinationInput.trim(), ignoreCase = true) }
        }
    }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Destination Target Selector Bar
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = destinationInput,
                        onValueChange = {
                            destinationInput = it
                            onActivePeerChange(it)
                        },
                        label = { Text("Target Destination Hash") },
                        placeholder = { Text("16-byte hex hash...") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (destinationInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    destinationInput = ""
                                    onActivePeerChange("")
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }

                // Quick Peer Chips
                if (peers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Select Peer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(peers) { p ->
                            val isSelected = destinationInput.trim().equals(p.hash.trim(), ignoreCase = true)
                            val label = if (p.name.isNotBlank()) p.name else p.hash.take(8)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    destinationInput = p.hash
                                    onActivePeerChange(p.hash)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
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
                        Text(
                            text = if (destinationInput.isBlank()) "No messages yet.\nSelect a peer or enter a hash to chat."
                                   else "No messages with ${destinationInput.take(8)}.\nSend an LXMF packet below.",
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
                    items(filteredMessages, key = { it.id }) { msg ->
                        val isOut = !msg.isIncoming
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    containerColor = if (isOut) MaterialTheme.colorScheme.primaryContainer
                                                     else MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    if (msg.isIncoming) {
                                        Text(
                                            text = msg.peer.take(8),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isOut) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.align(Alignment.End),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTime(msg.timestamp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        if (isOut) {
                                            Text(
                                                text = "[${msg.status}]",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = when (msg.status) {
                                                    "Delivered" -> Color(0xFF00C853)
                                                    "Sent" -> MaterialTheme.colorScheme.primary
                                                    else -> Color(0xFFFFAB00)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom Input Bar with Slash-Command Support
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Message or /announce, /msg <hash>...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = inputText.trim()
                    if (text.isEmpty()) return@IconButton

                    if (text.startsWith("/announce")) {
                        onAnnounce()
                        inputText = ""
                    } else if (text.startsWith("/msg ")) {
                        val parts = text.removePrefix("/msg ").trim().split(" ", limit = 2)
                        if (parts.size >= 2) {
                            val hash = parts[0].trim()
                            val msg = parts[1].trim()
                            destinationInput = hash
                            onActivePeerChange(hash)
                            onSendMessage(hash, msg)
                            inputText = ""
                        }
                    } else {
                        if (destinationInput.isNotBlank()) {
                            onSendMessage(destinationInput.trim(), text)
                            inputText = ""
                        }
                    }
                },
                enabled = inputText.isNotBlank() && (destinationInput.isNotBlank() || inputText.startsWith("/")),
                colors = IconButtonDefaults.filledIconButtonColors()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
    onSinceIdChange: (Long) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var selectedSubTab by remember { mutableStateOf(StumpSubTab.CHAT) }
    var isChecking by remember { mutableStateOf(false) }
    var showEventLog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sleek Single-Row Connection Ribbon (Reclaims >50% Vertical Real Estate)
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Inline compact surface pill showing status dot + IP/URL
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Embedded status indicator dot next to URL
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (stumpReachable) {
                                            true -> Color(0xFF2E7D32)
                                            false -> Color(0xFFC62828)
                                            null -> Color.Gray
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = stumpUrl,
                                onValueChange = onStumpUrlChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Inline "Auto-Detect" pill button (with miniature progress spinner while scanning)
                    FilledTonalButton(
                        onClick = {
                            onIsScanningChange(true)
                            onScanProgressChange(0)
                            onScanTotalChange(254)
                            onScanStatusTextChange("Probing...")
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
                                        Toast.makeText(context, "Found: $discoveredIp", Toast.LENGTH_SHORT).show()
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
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val pct = if (scanTotal > 0) "${scanProgress * 100 / scanTotal}%" else "..."
                            Text(pct, style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Auto-Detect", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Detect", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Compact ping/refresh icon button
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Check Connection", modifier = Modifier.size(17.dp))
                        }
                    }
                }

                // If scanning, ultra-thin 2dp indicator pinned flush at bottom of ribbon
                if (isScanning) {
                    LinearProgressIndicator(
                        progress = { if (scanTotal > 0) (scanProgress.toFloat() / scanTotal).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
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
                    rrcMessages = rrcMessages,
                    onRrcMessagesChange = onRrcMessagesChange,
                    activeRoom = activeRoom,
                    onActiveRoomChange = onActiveRoomChange,
                    availableRooms = availableRooms,
                    onAvailableRoomsChange = onAvailableRoomsChange,
                    sinceId = sinceId,
                    onSinceIdChange = onSinceIdChange,
                    onReachabilityChanged = { onStumpReachableChange(it) }
                )
                StumpSubTab.BILLBOARD -> BillboardSubScreen(
                    context = context,
                    stumpClient = stumpClient
                )
                StumpSubTab.FILES -> FservFilesSubScreen(
                    context = context,
                    stumpClient = stumpClient,
                    eventLog = eventLog,
                    showEventLog = showEventLog,
                    onToggleEventLog = { showEventLog = !showEventLog }
                )
            }
        }
    }
}

@Composable
fun RrcChatSubScreen(
    context: Context,
    stumpClient: StumpHttpClient,
    stumpUrl: String,
    rrcMessages: List<RrcMessage>,
    onRrcMessagesChange: (List<RrcMessage>) -> Unit,
    activeRoom: String,
    onActiveRoomChange: (String) -> Unit,
    availableRooms: List<String>,
    onAvailableRoomsChange: (List<String>) -> Unit,
    sinceId: Long,
    onSinceIdChange: (Long) -> Unit,
    onReachabilityChanged: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentNick by remember { mutableStateOf("") }
    var currentTopic by remember { mutableStateOf("") }
    var activeUsers by remember { mutableStateOf(listOf<String>()) }
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
                activeUsers = poll.users

                val incoming = poll.messages + poll.dms
                if (incoming.isNotEmpty()) {
                    val maxId = incoming.maxOf { it.id }
                    if (maxId > sinceId) onSinceIdChange(maxId)

                    val existingIds = rrcMessages.map { it.id }.filter { it > 0 }.toSet()
                    val fresh = incoming.filter { it.id == 0L || it.id !in existingIds }
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
        }
    }

    LaunchedEffect(rrcMessages.size) {
        if (rrcMessages.isNotEmpty()) {
            listState.animateScrollToItem(rrcMessages.size - 1)
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
                        activeUsers = poll.users

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

        isSending = true
        coroutineScope.launch {
            try {
                val res = stumpClient.sendRrc(text)
                if (res.isSuccess) {
                    val resp = res.getOrThrow()
                    chatInput = ""
                    val cleanRespRoom = resp.room?.trim()?.removePrefix("#")
                    if (!cleanRespRoom.isNullOrBlank() && cleanRespRoom != activeRoom) {
                        onActiveRoomChange(cleanRespRoom)
                        if (!availableRooms.contains(cleanRespRoom)) {
                            onAvailableRoomsChange(availableRooms + cleanRespRoom)
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
                        onRrcMessagesChange(rrcMessages + replyMsgs)
                    }
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Send failed"
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSending = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

            items(rrcMessages) { msg ->
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
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
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
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "[Direct Message] ${msg.nick}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        if (msg.ts > 0) {
                                            Text(
                                                text = formatTime(msg.ts),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(text = msg.body, style = MaterialTheme.typography.bodyMedium)
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
    stumpClient: StumpHttpClient,
    eventLog: List<String>,
    showEventLog: Boolean,
    onToggleEventLog: () -> Unit
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

        // Live Mesh Diagnostic Feed Expander
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleEventLog() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Live Mesh Diagnostic Feed", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("RNS Logs", eventLog.joinToString("\n"))
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Logs",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Icon(
                                imageVector = if (showEventLog) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }

                    AnimatedVisibility(visible = showEventLog) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black)
                                    .padding(8.dp)
                            ) {
                                LazyColumn {
                                    items(eventLog) { event ->
                                        val isPeer = event.contains("Discovered Peer:")
                                        val isLoRa = event.contains("LoRa RX")
                                        Text(
                                            text = event,
                                            color = when {
                                                isPeer -> Color(0xFF64FFDA)
                                                isLoRa -> Color(0xFFFFD54F)
                                                else -> Color(0xFF00E676)
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
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
