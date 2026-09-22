# RNS Node — Android Reticulum Mesh & Heltec RNode Bridge

**RNS Node** is a decentralized, off-grid communications client for Android. It embeds the official [Reticulum Network Stack](https://reticulum.network/) (`rns`) and the [Lightweight Extensible Mesh Framework](https://github.com/markqvist/LXMF) (`lxmf`) directly on-device using Chaquopy, paired with an integrated low-latency USB bridge for hardware LoRa transceivers (such as the Heltec WiFi LoRa 32 V3).

---

## Key Features

- **Embedded Reticulum Network Stack (RNS)**:
  - Runs full Python Reticulum natively via Chaquopy on Python 3.13 without requiring root access.
  - Zero-configuration cryptographic transport, automatic path discovery, and unforgeable delivery receipts.
- **Decentralized LXMF Messaging**:
  - Peer discovery with callsign resolution and hop tracking via a global `UniversalAnnounceHandler`.
  - Direct 1-to-1 encrypted LXMF messaging with delivery confirmations (`Sent`, `Delivered`).
  - Quick slash-command support (`/announce`, `/msg <hash> <text>`).
- **Hardware USB LoRa Bridge (`UsbRNodeBridge.kt`)**:
  - Direct USB-OTG connection via `usb-serial-for-android` (CDC-ACM driver, VID `0x303A` / PID `0x1001`).
  - Hardware line discipline configured specifically for ESP32-S3 boards (`DTR = true`, `RTS = false`) to maintain CDC data flow without resetting into download mode.
  - Automatic KISS modem negotiation into **NOAM US915**:
    - **Frequency**: 915.000 MHz (`0x3689CAC0`)
    - **Bandwidth**: 125 kHz
    - **Spreading Factor**: SF8
    - **Coding Rate**: 4/5 (CR 5)
    - **TX Power**: 7 dBm
  - Local loopback bridge (`127.0.0.1:4243`) connecting native serial bytes to Reticulum's `TCPClientInterface` using native KISS framing (`kiss_framing = True`).
- **Project Stump Wi-Fi Integration (`StumpHttpClient.kt`)**:
  - Dedicated hub management tab for companion off-grid portals (e.g. Project Stump / BarKeep at `http://192.168.4.1`).
  - Reachability health check, local `/billboard` reader, and bidirectional payload upload/download testing.
- **SELinux & Android Sandbox Hardening**:
  - Proactive user-space monkey-patching in `rns_core.py` to bypass `untrusted_app` SELinux audit rate-limiting (`E/audit: rate limit exceeded`).
  - Intercepts restricted filesystem probes (`/etc/reticulum`, `/proc/net`, `/sys`), restricts invalid socket families (`AF_NETLINK`, `AF_UNIX`), and intercepts unauthorized `setsockopt` calls.
- **Foreground Node Service**:
  - `RnsNodeService` runs as a foreground service with a partial WakeLock to keep the mesh routing and listening in the background.

---

## Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           Jetpack Compose UI                            │
│        [Discovered Peers]   [LXMF Messaging Feed]   [Stump Node Hub]    │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ StateFlow / Coroutines
┌────────────────────────────────────┴────────────────────────────────────┐
│                             RnsNodeService                              │
│             (Android Foreground Service & Keep-Alive Lock)              │
└──────────────────┬──────────────────────────────────────┬───────────────┘
                   │                                      │
                   ▼                                      ▼
┌─────────────────────────────────────┐  TCP:4243  ┌──────────────────────┐
│           UsbRNodeBridge            │◄──────────►│     rns_core.py      │
│      (usb-serial-for-android)       │   (KISS)   │(Embedded Python RNS) │
└──────────────────┬──────────────────┘            └──────────┬───────────┘
                   │ USB OTG (115200 CDC-ACM)                 │
                   ▼                                          ▼
┌─────────────────────────────────────┐            ┌──────────────────────┐
│          Heltec LoRa 32 V3          │            │     LXMF Router      │
│      (RNode Firmware / SX1262)      │            │(Ratchets & Delivery) │
└─────────────────────────────────────┘            └──────────────────────┘
```

---

## Hardware Requirements

- **Android Device**: Android 7.0 (API 24) through Android 16 (API 36) with USB-OTG support.
- **LoRa Transceiver**:
  - Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262) flashed with [RNode Firmware CE](https://github.com/markqvist/RNode_Firmware).
  - USB-C to USB-C OTG cable or adapter.

---

## Building and Installation

### 1. Prerequisites
- Android Studio Ladybug (or newer) / Android SDK 36.
- JDK 17 or higher.
- Chaquopy 17.0.0+ automatically manages the standalone Python 3.13 toolchain.

### 2. Build via Gradle
```bash
# Compile and build the debug APK
gradle assembleDebug

# Run unit tests
gradle :app:testDebugUnitTest
```

The compiled APK will be output to:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Configuration & Storage

Internal configuration and cryptographic keys are isolated within the app's sandboxed private storage:
- `<app_data>/files/rns_config/`: Reticulum interface declarations, transport identities, and routing configuration.
- `<app_data>/files/lxmf_storage/`: LXMF ratchets, message queues, and announcement caches.

---

## License

This project is licensed under the MIT License. Embedded libraries (`RNS`, `LXMF`, `usb-serial-for-android`, and `Chaquopy`) are subject to their respective open-source licenses.
