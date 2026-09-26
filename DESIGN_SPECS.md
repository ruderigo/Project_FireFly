# Design System & UI Specification Sheet
### *The UX Designer's "CSS" Index & Glossary for Jetpack Compose (Material 3)*

This document is the engineering design specification and translation glossary for UI/UX designers working on the **RNS Node** Android codebase. In modern Android, user interfaces are declared with **Jetpack Compose** using the **Material Design 3 (M3)** design token system rather than HTML and CSS.

---

## 1. Quick Rosetta Stone: Compose Modifiers vs. CSS Properties

Every visual property you normally declare in a CSS rule is expressed as a **`Modifier`** chain or component attribute in Compose.

| CSS Property | Jetpack Compose Equivalent | Code Example |
|---|---|---|
| `display: flex; flex-direction: row;` | `Row { ... }` | `Row(verticalAlignment = Alignment.CenterVertically) { ... }` |
| `display: flex; flex-direction: column;` | `Column { ... }` | `Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ... }` |
| `display: grid; place-items: center;` | `Box(contentAlignment = Alignment.Center)` | `Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ... }` |
| `gap: 12px;` | `Arrangement.spacedBy(12.dp)` | `Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { ... }` |
| `padding: 16px;` | `Modifier.padding(16.dp)` | `Modifier.padding(horizontal = 16.dp, vertical = 8.dp)` |
| `margin: 8px;` | `Modifier.padding(8.dp)` *(applied before background/border)* | `Modifier.padding(8.dp).background(...)` |
| `width: 100%;` | `Modifier.fillMaxWidth()` | `Modifier.fillMaxWidth()` |
| `height: 100%;` | `Modifier.fillMaxHeight()` | `Modifier.fillMaxHeight()` |
| `width: 48px; height: 48px;` | `Modifier.size(48.dp)` | `Modifier.size(48.dp)` |
| `min-width: 48px; min-height: 48px;` | `Modifier.defaultMinSize(48.dp, 48.dp)` | `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` |
| `flex-grow: 1; flex-basis: 0;` | `Modifier.weight(1f)` | `Text(..., modifier = Modifier.weight(1f))` |
| `background-color: #ffffff;` | `Modifier.background(Color.White)` | `Modifier.background(MaterialTheme.colorScheme.surface)` |
| `border-radius: 12px; overflow: hidden;` | `Modifier.clip(RoundedCornerShape(12.dp))` | `Modifier.clip(RoundedCornerShape(12.dp))` |
| `border-radius: 9999px;` (Pill / Circle) | `Modifier.clip(CircleShape)` | `Modifier.clip(CircleShape)` |
| `border: 1px solid #e0e0e0;` | `Modifier.border(1.dp, color, shape)` | `Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))` |
| `box-shadow: 0 4px 6px rgba(0,0,0,0.1);` | `Modifier.shadow(elevation = 4.dp, shape = ...)` | `Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp))` |
| `opacity: 0.6;` | `Modifier.alpha(0.6f)` | `Modifier.alpha(0.6f)` |
| `cursor: pointer;` | `Modifier.clickable { /* action */ }` | `Modifier.clickable { openDetails() }` |
| `overflow-y: auto;` (Virtualized) | `LazyColumn { ... }` | `LazyColumn(contentPadding = PaddingValues(16.dp)) { items(...) { ... } }` |
| `font-family: monospace;` | `fontFamily = FontFamily.Monospace` | `Text(hash, fontFamily = FontFamily.Monospace)` |
| `text-align: center;` | `textAlign = TextAlign.Center` | `Text("Status", textAlign = TextAlign.Center)` |

---

## 2. Design Tokens: Color Palette (CSS Variables Equivalent)

All UI elements derive their colors dynamically from the **Material 3 Color Scheme** (`MaterialTheme.colorScheme`), supporting both Light Mode and Dark Mode automatically.

### M3 Semantic Color Tokens
```css
/* Web CSS Variable Equivalents */
:root {
  --md-sys-color-primary: #6650a4;            /* Primary brand actions & active states */
  --md-sys-color-on-primary: #ffffff;         /* Text/icon on primary buttons */
  --md-sys-color-primary-container: #eaddff;   /* Active chip & identity badge backgrounds */
  --md-sys-color-on-primary-container: #21005d;/* Text on identity badge */
  
  --md-sys-color-surface: #fef7ff;             /* Main background canvas & top app bar */
  --md-sys-color-on-surface: #1d1b20;          /* High-emphasis text & icons */
  --md-sys-color-surface-variant: #e7e0ec;     /* Inactive chips, cards, secondary surfaces */
  --md-sys-color-on-surface-variant: #49454f;  /* Medium-emphasis labels, secondary text */
  
  --md-sys-color-outline: #79747e;             /* Subtle borders & divider strokes */
  --md-sys-color-outline-variant: #cac4d0;     /* Light hairline dividers */
  
  --md-sys-color-error: #b3261e;               /* Destructive actions & error notices */
  --md-sys-color-error-container: #f9dedc;     /* Error banner background */
  --md-sys-color-on-error-container: #410e0b;  /* Error banner text */
}
```

### Domain-Specific Radio & Hardware State Colors
These tokens are applied to status badges, connection indicator pips, signal bars, and packet telemetry:

| Token Name | Hex Code | Visual Meaning | CSS Equivalent |
|---|---|---|---|
| **`StatusOnline`** | `#4CAF50` (Green) | SX1262 PLL locked in RX mode, bridge ready | `--color-status-online: #4caf50;` |
| **`StatusTransmitting`** | `#2196F3` (Blue) | Active LoRa RF packet transmission | `--color-status-tx: #2196f3;` |
| **`StatusAttached`** | `#FF9800` (Orange) | GATT connected or USB open; configuring | `--color-status-attached: #ff9800;` |
| **`StatusDisconnected`**| `#9E9E9E` (Slate Gray) | No hardware transceiver connected | `--color-status-disconnected: #9e9e9e;` |
| **`StatusError`** | `#F44336` (Red) | Handshake refused (`0x06 0x00`) or timeout | `--color-status-error: #f44336;` |

### Signal Strength (RSSI Meter)
- **Strong ($\ge -70\text{ dBm}$)**: `#4CAF50` (Green)
- **Fair ($-71\text{ to } -85\text{ dBm}$)**: `#FF9800` (Orange)
- **Poor ($< -85\text{ dBm}$)**: `#F44336` (Red)

---

## 3. Typography Scale (CSS Font & Text Rules)

Typography is governed by `MaterialTheme.typography` in `ui/theme/Type.kt`. Font sizes use `sp` (scale-independent pixels), which respect Android user accessibility font scaling settings.

| Typography Token | Font Size / Line Height | Font Weight | Letter Spacing | UX Usage |
|---|---|---|---|---|
| **`headlineMedium`** | `28.sp / 36.sp` | SemiBold (`600`) | `0.sp` | Main screen titles & sheet headers |
| **`titleMedium`** | `16.sp / 24.sp` | Medium (`500`) | `0.15.sp` | Peer callsign names, card headings |
| **`bodyLarge`** | `16.sp / 24.sp` | Normal (`400`) | `0.5.sp` | LXMF chat message bubbles, primary reading text |
| **`bodyMedium`** | `14.sp / 20.sp` | Normal (`400`) | `0.25.sp` | Secondary descriptions, configuration notes |
| **`bodySmall`** | `12.sp / 16.sp` | Normal (`400`) | `0.4.sp` | Timestamps, SNR & hop telemetry sub-text |
| **`labelLarge`** | `14.sp / 20.sp` | SemiBold (`600`) | `0.1.sp` | Action buttons, tab labels |
| **`labelMedium`** | `12.sp / 16.sp` | Bold (`700`) | `0.5.sp` | Connection status badges, mode pills |
| **`labelSmall`** | `11.sp / 16.sp` | Medium (`500`) | `0.5.sp` | RSSI values, packet byte counters |
| **`Monospace`** *(Variant)* | `12.sp - 14.sp` | Bold / Medium | `0.sp` | Cryptographic hashes (`take(8)`), hex telemetry |

---

## 4. Spacing System & Layout Grid (The 8.dp Rule)

All margins, paddings, and component heights align to the standard **8.dp spatial grid** (with a 4.dp micro sub-grid).

| Token | Dimension | CSS Equivalent | Common Use Cases |
|---|---|---|---|
| **`Spacing.micro`** | `2.dp - 4.dp` | `2px - 4px` | Internal space between icon and text inside a badge |
| **`Spacing.extraSmall`**| `6.dp - 8.dp` | `6px - 8px` | Badge internal padding, spacing between status dots |
| **`Spacing.small`** | `12.dp` | `12px` | Space between cards in a list, input field internal padding |
| **`Spacing.medium`** | `16.dp` | `16px` | Screen left/right margins, modal bottom sheet edge padding |
| **`Spacing.large`** | `24.dp` | `24px` | Section dividers, spacing above action button groups |
| **`TouchTarget.min`** | `48.dp` | `min-height: 48px;` | Minimum accessible touch target for all buttons and icons |

---

## 5. Shape & Corner Radii System (CSS `border-radius`)

| Shape Token | Radius | CSS Equivalent | Components |
|---|---|---|---|
| **`Shape.Small`** | `8.dp` | `border-radius: 8px;` | Hash identity chips, input text boxes, tooltip bubbles |
| **`Shape.Medium`** | `12.dp` | `border-radius: 12px;` | Peer list item cards, chat bubbles, dialog containers |
| **`Shape.Large`** | `16.dp` | `border-radius: 16px;` | Status indicator capsules, transport toggle pills |
| **`Shape.ExtraLarge`**| `28.dp` | `border-radius: 28px;` | Modal bottom sheet top corners, Floating Action Buttons |
| **`Shape.Full`** | `50%` (`CircleShape`) | `border-radius: 9999px;` | Connection status pips, icon button backdrops, user avatars |

---

## 6. Key Component Specifications

### A. Radio Status Badge (Top App Bar)
```kotlin
// Capsule pill displaying connection state & frequency
Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "RNode: ONLINE (915.0 MHz)", style = MaterialTheme.typography.labelMedium)
    }
}
```
*CSS Equivalent:*
```css
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 16px;
  background-color: var(--md-sys-color-surface-variant);
  gap: 6px;
}
.status-badge .pip {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--color-status-online);
}
```

### B. Identity Hash Pill (Click-to-Copy)
```kotlin
Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.clickable { copyToClipboard(hash) }
) {
    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text = hash.take(8), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Icon(Icons.Default.ContentCopy, modifier = Modifier.size(12.dp))
    }
}
```

### C. Chat Message Bubbles
- **Outgoing Message (Sent by User)**:
  - Background: `MaterialTheme.colorScheme.primaryContainer`
  - Alignment: Right (`Alignment.End`)
  - Shape: `RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)` (small corner at bottom right)
- **Incoming Message (Received from Mesh)**:
  - Background: `MaterialTheme.colorScheme.surfaceVariant`
  - Alignment: Left (`Alignment.Start`)
  - Shape: `RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)` (small corner at bottom left)
- **Delivery Receipts**:
  - `Delivered`: `Icon(Icons.Default.DoneAll, tint = primary)`
  - `Sent`: `Icon(Icons.Default.Done, tint = outline)`

---

## 7. How to Request UI Changes to the Engineering Team

When providing specs to developers, reference tokens using this standard convention:

1. **Colors**: `"Use MaterialTheme.colorScheme.primaryContainer for active filter chips"` or `"Set status pip to StatusOnline (#4CAF50)"`.
2. **Typography**: `"Apply typography.titleMedium (16sp/SemiBold) to peer card titles"`.
3. **Spacing**: `"Add Spacing.medium (16dp) padding between the peer list and the input bar"`.
4. **Shapes**: `"Use Shape.Medium (12dp radius) on all diagnostic cards"`.
