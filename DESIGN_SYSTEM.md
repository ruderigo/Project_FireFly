# Project Firefly — UI/UX Design System & "CSS-to-Compose" Specification

> **Engineering & Design Spec Sheet**  
> *Target Audience:* UX/UI Product Designers, Front-End Engineers, and Android System Engineers transitioning between Web CSS and Jetpack Compose.

---

## 1. Executive Summary & Design Philosophy

Project Firefly is an off-grid, hardware-bridged mesh communication client built natively for Android using **Jetpack Compose** and **Material Design 3 (M3)**. 

Unlike traditional HTML/CSS web applications where visual presentation is detached from DOM markup via stylesheets and selectors, Jetpack Compose is **declarative and tokenized in Kotlin**. Styles, layouts, and constraints are applied using **Modifiers** (akin to atomic/utility CSS classes) and **Theme Tokens** (akin to CSS Custom Properties / Design Tokens).

---

## 2. The CSS $\leftrightarrow$ Jetpack Compose Rosetta Stone (Glossary)

This index maps standard Web / CSS concepts directly to their Jetpack Compose equivalents.

### 2.1. Box Model & Dimensions

| Web / CSS Property | Jetpack Compose Equivalent | Notes & Code Example |
|---|---|---|
| `width: 100%` | `Modifier.fillMaxWidth()` | Expands to available parent width. |
| `height: 100%` | `Modifier.fillMaxHeight()` | Expands to available parent height. |
| `width: 100vw; height: 100vh` | `Modifier.fillMaxSize()` | Full viewport bounds. |
| `width: 48px; height: 48px` | `Modifier.size(48.dp)` | Density-independent pixels (`dp`). |
| `min-width: 48px; min-height: 48px` | `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` | Ensures accessibility touch targets. |
| `max-width: 600px` | `Modifier.widthIn(max = 600.dp)` | Container constraint for tablet/foldables. |
| `padding: 12px 16px` | `Modifier.padding(horizontal = 16.dp, vertical = 12.dp)` | Internal content offset. |
| `margin: 8px` | `Modifier.padding(8.dp)` (placed before background) | Compose has no distinct `margin`; modifier order determines inner vs outer padding. |
| `box-sizing: border-box` | Default behavior in Compose | Layout measurements inherently account for padding. |

### 2.2. Flexbox & Grid Layouts

| Web / CSS (Flexbox) | Jetpack Compose Equivalent | Notes & Code Example |
|---|---|---|
| `display: flex; flex-direction: row;` | `Row(...) { }` | Horizontal linear container. |
| `display: flex; flex-direction: column;` | `Column(...) { }` | Vertical linear container. |
| `justify-content: space-between;` | `horizontalArrangement = Arrangement.SpaceBetween` | Spaces children to opposing edges. |
| `justify-content: center;` | `horizontalArrangement = Arrangement.Center` | Centers children horizontally. |
| `align-items: center;` | `verticalAlignment = Alignment.CenterVertically` | Centers items along cross-axis in a `Row`. |
| `gap: 8px;` | `Arrangement.spacedBy(8.dp)` | Spacing between sibling children. |
| `flex: 1; min-width: 0;` | `Modifier.weight(1f)` | Consumes remaining space; prevents overflowing siblings. |
| `flex-grow: 0; flex-shrink: 1;` | `Modifier.weight(1f, fill = false)` | Expands only up to content width, won't push siblings off-screen. |
| `position: relative;` + `position: absolute;` | `Box(...) { }` with `Modifier.align(...)` | Z-stacking overlay container. |
| `overflow-y: auto;` | `LazyColumn { }` | Virtualized scroll container (like `react-window`). |
| `overflow-x: auto;` | `LazyRow { }` | Virtualized horizontal carousel. |

### 2.3. Typography & Text

| Web / CSS Property | Jetpack Compose Equivalent | Notes & Code Example |
|---|---|---|
| `font-family: monospace;` | `fontFamily = FontFamily.Monospace` | Used for hashes, frequencies, KISS telemetry. |
| `font-size: 14px;` | `fontSize = 14.sp` | Scale-independent pixels (`sp`) obeying system accessibility. |
| `font-weight: 700;` | `fontWeight = FontWeight.Bold` | Medium (500), SemiBold (600), Bold (700). |
| `letter-spacing: 0.15px;` | `letterSpacing = 0.15.sp` | Tracking token. |
| `text-overflow: ellipsis; white-space: nowrap;` | `maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis` | Prevents ugly mid-word wrapping in compact rows. |
| `text-align: center;` | `textAlign = TextAlign.Center` | Alignment inside text block. |
| `line-height: 20px;` | `lineHeight = 20.sp` | Vertical leading between lines. |

### 2.4. Colors, Borders & Shapes

| Web / CSS Property | Jetpack Compose Equivalent | Notes & Code Example |
|---|---|---|
| `background-color: #212121;` | `Modifier.background(MaterialTheme.colorScheme.surface)` | Semantic theming token. |
| `border-radius: 12px;` | `shape = RoundedCornerShape(12.dp)` | Corner radius clipping. |
| `border-radius: 50%;` | `shape = CircleShape` | Circular avatars and status pips. |
| `border: 1px solid rgba(255,255,255,0.12);` | `border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)` | Explicit stroke container. |
| `box-shadow: 0 2px 4px rgba(0,0,0,0.2);` | `tonalElevation = 2.dp` or `Modifier.shadow(4.dp)` | Material 3 tonal elevation and shadow. |
| `opacity: 0.45;` | `color.copy(alpha = 0.45f)` | Alpha channel compositing. |

### 2.5. Interactivity, States & Cursors

| Web / CSS State | Jetpack Compose Equivalent | Notes & Code Example |
|---|---|---|
| `cursor: pointer; :hover, :active` | `Modifier.clickable { ... }` | Emits interactive ripple; handles keyboard/pointer navigation. |
| `oncontextmenu` / long-press | `Modifier.combinedClickable(onClick = { }, onLongClick = { })` | Long-press gesture with haptic feedback. |
| `data-testid="peer_card"` | `Modifier.testTag("peer_card")` | Strict test selector for UI automation and QA testing. |
| `:disabled` | `enabled = false` | Standard prop on Buttons, Fields, Chips. |

---

## 3. Project Firefly Design Tokens

These tokens correspond to CSS Custom Properties (`var(--token)`).

### 3.1. Color Palette Tokens (`MaterialTheme.colorScheme`)

```kotlin
// Material 3 Semantic Color System
MaterialTheme.colorScheme.primary             // #D0BCFF (Dark) / #6750A4 (Light) - Main CTA, active states
MaterialTheme.colorScheme.onPrimary           // #381E72 - Contrast text on primary buttons
MaterialTheme.colorScheme.primaryContainer    // #4F378B - Outgoing chat bubbles, active TX chip
MaterialTheme.colorScheme.onPrimaryContainer  // #EADDFF - High-contrast text on primary containers
MaterialTheme.colorScheme.secondaryContainer  // #4A4458 - Hop count badges, incoming chat bubbles
MaterialTheme.colorScheme.onSecondaryContainer// #E8DEF8 - Text on secondary container
MaterialTheme.colorScheme.surface             // #141218 - Background surfaces
MaterialTheme.colorScheme.surfaceVariant      // #49454F - Cards, bottom sheet, text input backgrounds
MaterialTheme.colorScheme.outlineVariant      // #49454F (alpha 0.5) - Hairline borders (0.5dp to 1dp)

// Dedicated Radio & Domain Semantic Accents
val ColorOnlineGreen  = Color(0xFF00E676)     // LoRa Radio ONLINE / continuous RX lock
val ColorConnectingAmber = Color(0xFFFFB300)  // USB/BLE ATTACHED or CONNECTING
val ColorOfflineRed    = Color(0xFFEF5350)    // Radio DISCONNECTED / PLL Lock Error
val ColorFriendStar    = Color(0xFFFFB300)    // Starred Contact / Favorited Node
```

### 3.2. Spacing Scale (8dp Canonical Grid)

```kotlin
object Spacing {
    val micro      = 2.dp   // Internal hash pill vertical padding
    val extraSmall = 4.dp   // Icon-to-text spacing, status pip margin
    val small      = 8.dp   // Telemetry chip gaps, list item spacing
    val medium     = 12.dp  // Card inner padding, list item horizontal inset
    val large      = 16.dp  // Screen horizontal margins, dialog internal padding
    val extraLarge = 24.dp  // Section division padding
}
```

### 3.3. Typography Scale (`MaterialTheme.typography`)

| Style Name | Font / Weight | Size | Line Height | CSS Match | Intended Use |
|---|---|---|---|---|---|
| `titleLarge` | Sans / Medium (500) | 22sp | 28sp | `font-size: 22px; font-weight: 500;` | Screen headers, dialog titles |
| `titleMedium` | Sans / Bold (700) | 16sp | 24sp | `font-size: 16px; font-weight: 700;` | Peer display name, card titles |
| `bodyMedium` | Sans / Regular (400) | 14sp | 20sp | `font-size: 14px; font-weight: 400;` | LXMF chat bubbles, dialog descriptions |
| `labelMedium` | Sans / SemiBold (600) | 12sp | 16sp | `font-size: 12px; font-weight: 600;` | "Chat" action button label, chip text |
| `labelSmall` | Sans / Medium (500) | 11sp | 16sp | `font-size: 11px; font-weight: 500;` | Hops badge, relative time ago |
| `MonoHash` | Monospace / Regular | 11sp | 14sp | `font-family: monospace; font-size: 11px;` | Truncated destination hash pills |

### 3.4. Shape Hierarchy

```kotlin
val ShapeBadge  = RoundedCornerShape(4.dp)   // Hop badge, inline tag
val ShapePill   = RoundedCornerShape(6.dp)   // Telemetry chips, hash pill
val ShapeButton = RoundedCornerShape(8.dp)   // FilledTonalButton, dialog actions
val ShapeCard   = RoundedCornerShape(12.dp)  // DiscoveredPeerCard, Status Card
val ShapeDialog = RoundedCornerShape(16.dp)  // TX Power sheet, Contact dialog
val ShapeAvatar = CircleShape                // 40dp-42dp friend star & avatar
```

---

## 4. Component Engineering Spec Sheets

### 4.1. Discovered Peer Card (`DiscoveredPeerCard`)

Redesigned to eliminate mid-string clipping and prevent vertical letter-by-letter wrapping on narrow 360dp screens.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ [★]  LaBuche • Friend                             [ 301c3454...4170 📋 ] [💬 Chat] │
│      [ 1 hop ]  4m ago                                                         │
└────────────────────────────────────────────────────────────────────────────────┘
```

#### Blueprint & Constraints:
1. **Container (`Surface` / `Card`)**:
   - Background: `MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)`
   - Border: Optional `outlineVariant` (0.5dp)
   - Border Radius: `12.dp`
   - Padding: Horizontal `10.dp`, Vertical `10.dp`
2. **Left Element (Star Toggle)**:
   - Size: `40.dp x 40.dp` circular touch target ($\ge 48\text{dp}$ touch padding)
   - Icon: `Icons.Filled.Star` (tint `#FFB300`) if friend; `Icons.Outlined.StarBorder` (tint `outline`) if unstarred.
3. **Middle Column (`Modifier.weight(1f)`)**:
   - **Row 1 (Identity)**:
     - Name: `Text(displayName, style = titleMedium.copy(fontWeight = Bold))` with `maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)`.
     - Friend Badge: `Text("• Friend", color = Color(0xFFFFB300), maxLines = 1, softWrap = false)` rendered only if starred.
   - **Row 2 (Hash Chip)**:
     - Container: `Surface(color = surface.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp), border = 0.5.dp outlineVariant)`.
     - Clickable: Copies full 16-byte hex hash to Android clipboard and toasts confirmation.
     - Text: Monospace `11.sp`, format `${hash.take(8)}...${hash.takeLast(4)}`.
   - **Row 3 (Metadata)**:
     - Hop Badge: `Surface(color = secondaryContainer, shape = RoundedCornerShape(4.dp))` with text `"$hops hop"`.
     - Time Ago: `Text(formatRelativeTime(timestamp), style = labelSmall)`.
4. **Right Element ("Chat" Action Button)**:
   - Component: `FilledTonalButton`
   - Padding: Horizontal `10.dp`, Vertical `6.dp`
   - Content: `Icon(Icons.Default.ChatBubble, size = 15.dp)` + `Text("Chat", style = labelMedium)`.
   - Interaction: Immediately opens 1-on-1 direct LXMF messaging thread.

---

### 4.2. RNode Status & TX Power Telemetry Card

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ 🟢 ONLINE (BLE 915.0 MHz)                  Heltec WiFi LoRa 32 V3 (0.9.x)      │
│ [915.0 MHz]  [BW 125]  [SF8]  [CR 4/5]  [⚙ TX 17 dBm]                          │
└────────────────────────────────────────────────────────────────────────────────┘
```

#### Blueprint:
1. **Interactive TX Power Chip**:
   - Background: `primaryContainer.copy(alpha = 0.7f)`
   - Border: `1.dp primary.copy(alpha = 0.5f)`
   - Text: `TX $txPower dBm` (Monospace, Bold)
   - Action: Opens the **TX Power Control Dialog**.
2. **TX Power Control Dialog**:
   - Presets: `7 dBm`, `14 dBm`, `17 dBm` (Default), `20 dBm`, `22 dBm`.
   - Dynamic Dispatch: Triggers KISS command `0x03` via `set_radio_tx_power` without node restart.
   - NVRAM / Prefs Persistence: Automatically re-loaded on subsequent cold starts.

---

## 5. Accessibility & Responsive Viewport Rules

1. **Touch Target Size**: All interactive icons (`IconButton`, `FilterChip`, Buttons) have a minimum dimension of `48.dp x 48.dp` via Compose's `minimumInteractiveComponentSize`.
2. **Text Scaling Support**: All textual dimensions use `sp` units to scale fluidly with system accessibility fonts (100% to 200%).
3. **Fluid Weights vs Fixed Bounds**:
   - Dynamic text (node names, hashes) must always be wrapped in `Modifier.weight(1f, fill = false)` or `Modifier.weight(1f)` to guarantee trailing action buttons remain visible on narrow devices (320dp – 360dp width).
4. **Haptic Feedback**: Long-press and confirmation actions execute `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`.
