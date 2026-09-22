package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDiagnosticsSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    context: Context,
    activeTransportType: TransportType,
    bridgeState: BridgeState,
    connectedDeviceName: String?,
    connectedMacAddress: String?,
    lastSavedMacAddress: String?,
    lastSavedDeviceName: String?,
    onForceDisconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onManualReinitRadio: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val allLogs by DiagnosticLogger.logs.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(allLogs, selectedFilter) {
        when (selectedFilter) {
            "BLE" -> allLogs.filter { it.contains("[BLE", ignoreCase = true) }
            "SOCKET" -> allLogs.filter { it.contains("[SOCKET", ignoreCase = true) }
            "PYTHON" -> allLogs.filter { it.contains("[PYTHON", ignoreCase = true) || it.contains("[RNS", ignoreCase = true) }
            else -> allLogs
        }
    }

    val listState = rememberLazyListState()

    // Smooth autoscroll to the latest log line
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier
            .fillMaxHeight(0.92f)
            .testTag("settings_diagnostics_sheet"),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Sheet Header with Title & Close Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Deck Settings & Diagnostics",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.testTag("close_settings_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Settings")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ==========================================
            // SECTION A: Radio & Connection Controls
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "RADIO & CONNECTION CONTROLS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Active Transport Mode Banner
                    val isOnline = bridgeState == BridgeState.ONLINE || bridgeState == BridgeState.RF_TRANSMITTING
                    val modeText = when {
                        isOnline && activeTransportType == TransportType.USB -> "USB OTG"
                        isOnline && activeTransportType == TransportType.BLE -> "BLE GATT"
                        activeTransportType == TransportType.BLE -> "BLE (CONNECTING)"
                        activeTransportType == TransportType.USB -> "USB (CONNECTING)"
                        else -> "DISCONNECTED"
                    }
                    val modeBg = when {
                        isOnline && activeTransportType == TransportType.USB -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                        isOnline && activeTransportType == TransportType.BLE -> Color(0xFF0D47A1).copy(alpha = 0.2f)
                        activeTransportType != TransportType.NONE -> Color(0xFFE65100).copy(alpha = 0.2f)
                        else -> Color(0xFFB71C1C).copy(alpha = 0.15f)
                    }
                    val modeFg = when {
                        isOnline && activeTransportType == TransportType.USB -> Color(0xFF2E7D32)
                        isOnline && activeTransportType == TransportType.BLE -> Color(0xFF1565C0)
                        activeTransportType != TransportType.NONE -> Color(0xFFEF6C00)
                        else -> Color(0xFFC62828)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(modeBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTransportType == TransportType.USB) Icons.Default.Usb else Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = modeFg,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Active Transport Mode:",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                        Text(
                            text = modeText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = modeFg
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bluetooth Controls & Device Metadata
                    val devName = connectedDeviceName ?: lastSavedDeviceName ?: "Heltec V3 (BLE)"
                    val devMac = connectedMacAddress ?: lastSavedMacAddress ?: "None saved"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Bluetooth Node: $devName",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "MAC: $devMac",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (connectedMacAddress != null || lastSavedMacAddress != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = if (connectedMacAddress != null) "LINKED" else "SAVED",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Control Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Force Disconnect Button
                        OutlinedButton(
                            onClick = {
                                onForceDisconnect()
                                Toast.makeText(context, "Force disconnect executed", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("force_disconnect_button"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Force Disconnect", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }

                        // Forget Device Button
                        FilledTonalButton(
                            onClick = {
                                onForgetDevice()
                                Toast.makeText(context, "Cleared saved BLE node", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("forget_device_button"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Forget Device", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Manual Re-init Radio Button (Fires 6 RNode wake/config KISS frames)
                    FilledTonalButton(
                        onClick = {
                            onManualReinitRadio()
                            Toast.makeText(context, "Re-initialized RNode KISS radio states", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_reinit_button"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Manual Re-init Radio (KISS 6-Frame Wake)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // SECTION B: Live Diagnostic & Bridge Log Console
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "LIVE DIAGNOSTIC CONSOLE",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${filteredLogs.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy Logs Button
                    OutlinedButton(
                        onClick = {
                            val text = DiagnosticLogger.getAllLogsText()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Deck Diagnostic Logs", text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied ${allLogs.size} logs to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("copy_logs_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }

                    // Clear Logs Button
                    OutlinedButton(
                        onClick = {
                            DiagnosticLogger.clear()
                            Toast.makeText(context, "Diagnostic console cleared", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("ALL", "BLE", "SOCKET", "PYTHON").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Monospace Dark Terminal Console Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A)) // Dark slate terminal background
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No diagnostic events yet.\nLogs stream live during radio and Reticulum operations.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B)
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_logs_list"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(filteredLogs) { index, line ->
                            val textColor = when {
                                line.contains("[BLE RAW]", ignoreCase = true) -> Color(0xFF38BDF8) // Cyan
                                line.contains("[SOCKET BRIDGE]", ignoreCase = true) -> Color(0xFF4ADE80) // Light Green
                                line.contains("[PYTHON RNS]", ignoreCase = true) -> Color(0xFFFBBF24) // Amber
                                line.contains("[UI]", ignoreCase = true) -> Color(0xFFC084FC) // Purple
                                line.contains("ERROR", ignoreCase = true) || line.contains("fail", ignoreCase = true) -> Color(0xFFF87171) // Red
                                else -> Color(0xFFE2E8F0) // Light slate
                            }

                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = textColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
