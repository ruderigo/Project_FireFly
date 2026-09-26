# RNS Node — Android Reticulum Mesh & Heltec RNode Bridge 
# [APK FILE](https://drive.google.com/file/d/1a9jSQB5GynEuFO6uS-s5BE02JWgiKKlV/view?usp=sharing)

[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20(M3)-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Python](https://img.shields.io/badge/Python-3.13%20via%20Chaquopy-3776AB?logo=python&logoColor=white)](https://chaquo.com/chaquopy/)
[![RNS](https://img.shields.io/badge/Reticulum-0.9.x-black)](https://reticulum.network/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**RNS Node** is a full-featured, off-grid decentralized communications client for Android. It embeds the official [Reticulum Network Stack](https://reticulum.network/) (`rns`) and the [Lightweight Extensible Mesh Framework](https://github.com/markqvist/LXMF) (`lxmf`) directly on-device using Chaquopy, paired with an integrated low-latency USB and Bluetooth Low Energy (BLE) bridge for hardware LoRa transceivers (such as the Heltec WiFi LoRa 32 V3).

Turn any modern Android device into an autonomous mesh communication station with zero external dependencies, root requirements, or internet connectivity.

---

## Installation & Field Deployment

### 1. Download the APK
1. On your Android device, navigate to the **Releases** section of this GitHub repository.
2. Under the latest release, download the `FireFly1.apk` asset directly to your device (or locate the pre-compiled APK in this repository under `.build-outputs/app-debug.apk`).

---

### 2. Enable Unknown App Installation (Sideloading)
Because this application is distributed directly via GitHub rather than the Google Play Store, Android requires explicit permission to install sideloaded packages:

1. Open your browser's download manager or your device's **Files** app and tap `FireFly1.apk`.
2. If prompted with *“For your security, your phone is not allowed to install unknown apps from this source”*:
   * Tap **Settings** on the prompt.
   * Toggle **Allow from this source** to **ON**.
3. Tap **Install** to complete the package installation.

> **Android 13+ Note:** If permission prompts are restricted, navigate to **Settings** → **Apps** → **Special app access** → **Install unknown apps**, select the browser or file manager used to download the file, and toggle **Allow from this source**.

---

### 3. First-Launch Hardware & Radio Permissions
To interface with the Heltec WiFi LoRa 32 (V3) transceiver, grant the following permissions when prompted on first launch:

* **Nearby Devices / Bluetooth:** Required for BLE GATT discovery and communication over Nordic UART Service (NUS).
* **Location (Precise):** Required by Android for BLE scanning and beacon discovery.
* **Notifications:** Allows the background radio bridge service and incoming LXMF message alerts to stay active.

---

### 4. Connecting Your Heltec V3 Node

#### Option A: Bluetooth Low Energy (BLE)
1. Power on your Heltec V3 running RNode firmware.
2. In the app, open the top-right **Settings & Diagnostics** menu.
3. Tap **Scan for Radios** and select your node (e.g., `RNode XXXX`).
4. Once connected, the app will negotiate an MTU of 247, latch the SX1262 PLL into continuous RX mode, and report `RNode Bridge: ONLINE (BLE 915.0 MHz)`.

#### Option B: USB OTG
1. Connect the Heltec V3 to your Android device using a USB-C OTG cable.
2. When the system pop-up appears (*“Allow this app to access the USB device?”*), check **Always allow** and tap **OK**.
3. The bridge will bind CDC-ACM serial parameters (115200 baud, 8N1, DTR active) and latch the radio into RX mode.

---

## ⚡ Recent Developments & Key Features

### 1. Dual Hardware Transports (USB-OTG & Bluetooth LE)
- **Hot-Swappable Radio Modes**: Seamlessly switch between wired USB-OTG serial and wireless Bluetooth Low Energy (BLE) from the app's top bar.
- **BLE Nordic UART Service (NUS)**: Full GATT client implementation supporting bidirectional LoRa packet streaming over UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (TX: `0x0003`, RX: `0x0002`).
- **Autonomous BLE Discovery & Reconnect**:
  - Live scanning for nearby Heltec / RNode nodes with live RSSI meters.
  - One-tap bonding and automatic reconnection to the last paired node on startup.
- **USB-OTG Serial**:
  - High-throughput serial driver (`usb-serial-for-android`) for ESP32-S3 CDC-ACM (`VID 0x303A / PID 0x1001`).
  - Hardware line discipline configured specifically for ESP32-S3 (`DTR = true`, `RTS = false`) to maintain CDC data flow without resetting into download mode.

### 2. Bulletproof KISS Protocol Engine & Byte Escaping
- **Strict KISS Escaping (`KissFrameBuffer.kt`)**:
  - Payload bytes matching `0xC0` (`FEND`) and `0xDB` (`FESC`) are strictly escaped (`0xC0` $\rightarrow$ `0xDB, 0xDC`; `0xDB` $\rightarrow$ `0xDB, 0xDD`) prior to framing.
  - Ensures commands such as the 915 MHz frequency payload (`0x3689CAC0`) are transmitted as `C0 01 36 89 CA DB DC C0` (8 bytes), preventing premature frame termination or frequency truncation.
- **Streaming Frame Reassembly**: Incoming chunks from BLE notifications or USB endpoints are routed through a continuous reassembly buffer to prevent fragmented packet delivery to Reticulum.

### 3. Strict Hardware Handshake & Dynamic TX Power Control
- **Verified Hardware Wakeup**:
  - Configures frequency (`915.000 MHz`), bandwidth (`125 kHz`), spreading factor (`SF8`), coding rate (`4/5`), TX power (`17 dBm` default), and promiscuous RX mode (`CMD_RADIO_STATE = 0x06, 0x01`).
- **Dynamic KISS TX Power Switching (CMD 0x03)**:
  - LoRa radio transmit power now defaults to **17 dBm** across Python Reticulum config generation, Kotlin KISS framing, and hardware NVRAM initialization (replacing the previous 7 dBm baseline).
  - Runtime dynamic power switching: Users can tap the interactive `TX` power badge in the top RNode status card to switch between preset power tiers (`7 dBm`, `14 dBm`, `17 dBm`, `20 dBm`, `22 dBm`).
  - Commands are dispatched via KISS command `0x03` directly over BLE GATT or USB CDC-ACM without needing to reboot the node or interrupt Reticulum routing.
  - Selected TX power level is persisted locally across app launches.
- **Hardware ACK Enforcement**:
  - Strictly validates hardware responses to `CMD_RADIO_STATE`.
  - Response `0x06 0x01`: Verified PLL lock in RX mode; declares the radio link ready.
  - Response `0x06 0x00`: Configuration refused; logs error and re-transmits configuration once.
  - Rejects false "Link READY" status if the transceiver times out or refuses configuration.
- **Preserved RX Buffer**: Eliminated recurring radio reset loops/keepalives to guarantee that incoming RF packets in the SX1262 receiver are not aborted mid-reception.

### 4. Lifecycle Concurrency & Singleton Architecture
- **Mutex Connection Guard**: `AtomicBoolean` guards (`isConnectingGuard`) prevent overlapping connection attempts and prevent duplicate auto-reconnect triggers from tearing down the local TCP socket.
- **Singleton Reticulum Manager (`MeshService.kt`)**: Guards the native Python RNS and LXMF runtime against duplicate initialization, redundant socket creation, or duplicate TX interfaces.
- **Robust TCP Loopback Bridge (`127.0.0.1:4243`)**: Single-instance `ServerSocket` with `SO_REUSEADDR = true` connecting native radio bytes to Reticulum's `TCPClientInterface` using native KISS framing.
- **TX Announce Throttling**: Prevents channel saturation and duplicate announce broadcasts.

### 5. Decentralized LXMF Messaging & Mesh Discovery
- **Peer Discovery**: Global `UniversalAnnounceHandler` tracks peer identity hashes, callsigns, SNR, and hop counts.
- **End-to-End Encryption**: 1-to-1 encrypted LXMF messages with verified delivery receipts (`Sent`, `Delivered`).
- **Slash Commands**: Quick terminal commands (`/announce`, `/msg <hash> <text>`).
- **Background Routing**: Foreground service (`RnsNodeService`) with partial WakeLock keeps the node routing in the background.

### 6. Built-in Diagnostics & Project Stump Companion Hub
- **Diagnostics Bottom Sheet (`SettingsDiagnosticsSheet.kt`)**: Real-time log inspector with category filters for BLE, USB, Python RNS, LXMF, and raw KISS byte-level traffic.
- **Project Stump Portal Integration (`StumpHttpClient.kt`)**: Native HTTP client interface communicating with companion off-grid portals (e.g. BarKeep / Project Stump at `http://192.168.4.1`).

### 7. Local Contacts & Friends Persistence (Room Database)
- **Offline Contact Store (`FriendEntity` / `FriendDao`)**:
  - Saved nodes and peer contacts persist across reboots using SQLite via Android Jetpack Room.
  - Retains 16-byte Reticulum destination hash, custom callsign/display name, last-seen timestamps, and hop count.
- **One-Tap Contact Starring**: Star nearby discovered nodes directly from the Discovered tab to bookmark them into the Friends list.
- **Integrated Thread Prioritization**:
  - Dedicated "Friends" filter chip in the Discovered Nodes screen.
  - Priority Contact chips in the LXMF Messaging view with instant starred identification badges.

### 8. SELinux & Android Sandbox Hardening
- Native user-space monkey-patching in `rns_core.py` bypasses `untrusted_app` SELinux audit rate-limiting (`E/audit: rate limit exceeded`).
- Intercepts restricted filesystem probes (`/etc/reticulum`, `/proc/net`, `/sys`), restricts invalid socket families (`AF_NETLINK`, `AF_UNIX`), and intercepts unauthorized `setsockopt` calls.

---

## 🎨 UI/UX Design System Specification (Compose Design Tokens)

For designers and engineers referencing layout properties, visual tokens, and Material 3 equivalents to web CSS:

### 1. Color Palette Tokens (`MaterialTheme.colorScheme`)
| Compose Token | Web / CSS Equivalent | Purpose |
|---|---|---|
| `colorScheme.primary` | `var(--md-sys-color-primary)` | Primary brand color, action headers, active icons |
| `colorScheme.onPrimary` | `var(--md-sys-color-on-primary)` | Text/icons on primary surfaces (e.g., FAB text) |
| `colorScheme.primaryContainer` | `var(--md-sys-color-primary-container)` | Outgoing message bubbles, avatar badges |
| `colorScheme.onPrimaryContainer` | `var(--md-sys-color-on-primary-container)` | Text inside outgoing message bubbles |
| `colorScheme.secondaryContainer` | `var(--md-sys-color-secondary-container)` | Incoming message bubbles, hop pills |
| `colorScheme.surfaceVariant` | `var(--md-sys-color-surface-variant)` | Card background container surfaces |
| `colorScheme.outlineVariant` | `var(--md-sys-color-outline-variant)` | Subtle borders (0.5dp), hash pill borders |
| `Color(0xFFFFB300)` | `var(--amber-star)` | Starred contact badge and icon tint |
| `Color(0xFF2E7D32)` | `var(--status-online-green)` | Live online mesh peer indicator badge |

### 2. Typography Hierarchy (`MaterialTheme.typography`)
| Compose Style | Font Family | Size / Line Height | Tracking | Usage |
|---|---|---|---|---|
| `titleMedium` | Default Sans / Roboto | 16sp / 24sp | 0.15sp (Bold) | Node callsign, contact name, card title |
| `bodyMedium` | Default Sans / Roboto | 14sp / 20sp | 0.25sp | LXMF chat message content, empty state text |
| `bodySmall` | Default Sans / Roboto | 12sp / 16sp | 0.4sp | Secondary metadata, helper text |
| `labelMedium` | Default Sans / Roboto | 12sp / 16sp | 0.5sp (Medium) | Chip labels, button labels |
| `labelSmall` | Default Sans / Roboto | 11sp / 16sp | 0.5sp | Hop badge, relative timestamp |
| `FontFamily.Monospace` | Monospace (Roboto Mono) | 11sp – 13sp | Fixed width | Truncated destination hash pills (`peer.hash.take(8)...`) |

### 3. Spacing Grid (8dp Standard)
| Token | Dimension | Use Case |
|---|---|---|
| `Spacing.extraSmall` | `4.dp` | Inner icon-to-text spacing, status row gaps |
| `Spacing.small` | `8.dp` | Chip row spacing, card vertical padding, message feed spacing |
| `Spacing.medium` | `12.dp` | Card internal content padding, avatar margins |
| `Spacing.large` | `16.dp` | Outer screen margins, dialog padding |
| `Spacing.extraLarge`| `24.dp` | Section separators |

### 4. Component Shapes (`RoundedCornerShape`)
- **Pills & Chips (`FilterChip`, Hash Badge)**: `RoundedCornerShape(6.dp)`
- **Action Buttons (`FilledTonalButton`, Dialog Buttons)**: `RoundedCornerShape(8.dp)`
- **Cards (`DiscoveredNodeCard`, `TelemetryCard`)**: `RoundedCornerShape(12.dp)`
- **Chat Bubbles (Outgoing)**: `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)`
- **Chat Bubbles (Incoming)**: `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)`
- **Avatars**: `CircleShape` (`size = 42.dp`)

---

## 🏛 Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                      Jetpack Compose M3 UI                              │
│   [Transport Switcher]  [Discovered Peers]  [LXMF Chat]  [Diagnostics]  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ StateFlow / Coroutines
┌────────────────────────────────────┴────────────────────────────────────┐
│                             MeshService                                 │
│             (Lifecycle Guard, Announce Throttle, Context)               │
└──────────────────┬──────────────────────────────────────┬───────────────┘
                   │                                      │
                   ▼                                      ▼
┌─────────────────────────────────────┐  TCP:4243  ┌──────────────────────┐
│     BleRadioTransport (GATT NUS)    │◄──────────►│     rns_core.py      │
│                  OR                 │   (KISS)   │(Embedded Python RNS) │
│      UsbRNodeBridge (CDC-ACM)       │            └──────────┬───────────┘
└──────────────────┬──────────────────┘                       │
                   │ Raw KISS (FEND Escaped)                  ▼
                   ▼                               ┌──────────────────────┐
┌─────────────────────────────────────┐            │     LXMF Router      │
│          Heltec LoRa 32 V3          │            │(Ratchets & Delivery) │
│      (RNode Firmware / SX1262)      │            └──────────────────────┘
└─────────────────────────────────────┘
```

---

## 🛠 Hardware Setup

### Recommended Transceivers:
- **Heltec WiFi LoRa 32 V3** (ESP32-S3 + SX1262)
- **Heltec Wireless Stick Lite V3**
- Other ESP32 / ESP32-S3 hardware flashed with RNode Firmware.

### Flashing RNode Firmware:
Install `rnodeconf` via Python pip and flash your device:
```bash
pip install rnodeconf
rnodeconf --autoinstall
```
Select the appropriate port and Heltec V3 target. The firmware provides both USB serial CDC-ACM and BLE Nordic UART service automatically.

### Default Radio Parameters:
| Parameter | Value |
|---|---|
| **Frequency** | 915.000 MHz (US915 / NOAM) |
| **Bandwidth** | 125 kHz |
| **Spreading Factor** | SF8 |
| **Coding Rate** | 4/5 (CR 5) |
| **TX Power** | 17 dBm (Default, runtime-switchable 7–22 dBm) |

---

## 🔨 Building from Source

### Prerequisites:
- Android Studio Ladybug or newer
- JDK 17+
- Android SDK 36 (Android 16)
- Chaquopy 17.0.0+ automatically manages Python 3.13 and Python packages (`rns`, `lxmf`).

### Gradle Commands:
```bash
# Build debug APK
gradle assembleDebug

# Run Robolectric & JVM unit tests
gradle :app:testDebugUnitTest

# Output location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 Storage & Key Isolation

All cryptographic identities, routing databases, and message ratchets reside in the app's sandboxed private storage:
- `<app_internal>/files/rns_config/`: Reticulum interface definitions, transport identities, and routing caches.
- `<app_internal>/files/lxmf_storage/`: LXMF ratchets, message queues, and announce cache.

---

## 📜 Permissions

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`: BLE node discovery and Nordic UART communication.
- `ACCESS_FINE_LOCATION`: Required by Android for BLE scanning on API $\le$ 30.
- `USB_PERMISSION`: USB-OTG serial connection to the hardware transceiver.
- `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`: Continuous background mesh routing and delivery alerts.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE). Reticulum Network Stack and LXMF are copyright Mark Qvist and contributors.
