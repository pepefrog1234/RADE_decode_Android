# FreeDV Reporter Map — Display Logic & Rules

## 1. Data Source

All data comes from `qso.freedv.org` via Socket.IO v4 over WebSocket (`wss://`).

### Events Handled

| Event | Source | Data |
|-------|--------|------|
| `bulk_update` | Initial + periodic | Array of `[event_type, data]` tuples, replayed through individual handlers |
| `new_connection` | Station joins | `sid`, `callsign`, `grid_square`, `version`, `rx_only`, `os` |
| `remove_connection` | Station leaves | `sid` |
| `tx_report` | TX state change | `sid`, `callsign`, `grid_square`, `mode`, `transmitting` (bool), `last_tx` |
| `rx_report` | RX activity | `sid`, `receiver_callsign`, `receiver_grid_square`, `callsign` (decoded TX), `snr`, `mode` |
| `freq_change` | Frequency update | `sid`, `freq` (Hz) |
| `connection_successful` | Auth confirmed | (no payload) |

### Auth Packet

```
40{"protocol_version":2,"role":"report","callsign":"BX4ACP","grid_square":"PL04IE","version":"RADE_Android/1.0","os":"Android","rx_only":true}
```

Field name note: frequency field is `freq` (not `frequency`) in outgoing events and some incoming events.

---

## 2. Station State Model

```kotlin
data class ReporterStation(
    val connectionId: String,     // sid from server
    val callsign: String,         // station's own callsign
    val gridSquare: String,       // Maidenhead grid (2-10 chars)
    val frequency: Long,          // Hz (e.g., 14236000)
    val mode: String,             // e.g., "RADEV1"
    val version: String,          // software version
    val rxOnly: Boolean,          // RX-only station flag

    // TX state (from tx_report events)
    val transmitting: Boolean,    // currently transmitting
    val transmittingAt: Long,     // timestamp of last tx_report (>0 = came from tx_report)

    // RX state (from rx_report events)
    val receiving: Boolean,       // currently receiving signal (any signal)
    val receivingAt: Long,        // timestamp of last rx_report
    val receivedCallsign: String, // decoded callsign of TX station (from EOO)
    val snr: Int,                 // signal-to-noise ratio (dB)
    val receivedAt: Long,         // when receivedCallsign was last set non-empty

    val lastUpdate: Long          // most recent update timestamp
)
```

---

## 3. State Update Rules

### 3.1 Merge Logic (`mergeStation`)

When a station already exists and receives an update:

| Field | Rule |
|-------|------|
| `callsign` | Non-empty wins (keeps existing if update is empty) |
| `gridSquare` | Non-empty wins |
| `frequency` | Non-zero wins |
| `mode`, `version` | Non-empty wins |
| `transmitting` | **Only `tx_report` can change it** (identified by `transmittingAt > 0`); `rx_report` preserves existing value |
| `receiving` | **Only `rx_report` can set it** (identified by `receivingAt > 0`); other events preserve existing |
| `receivedCallsign` | Non-empty wins (sticky); cleared only by timeout |
| `snr` | Non-zero wins |

### 3.2 `tx_report` Handling

- `transmitting: true` → station turns red, `transmittingAt = now`
- `transmitting: false` → station immediately clears red, `transmittingAt = now` (marks as explicit tx_report)
- `transmittingAt` is always set to `now` regardless of true/false, so `mergeStation` knows this came from `tx_report`
- **No timeout** — only explicit `tx_report` with `transmitting: false` clears TX state

### 3.3 `rx_report` Handling

- Sets `receiving = true`, `receivingAt = now`
- If `callsign` field is non-empty → sets `receivedCallsign` (decoded TX callsign)
- If `callsign` field is empty → `receivedCallsign` stays unchanged (sticky)
- Updates `snr` if non-zero
- Does NOT affect `transmitting` state

### 3.4 Timeout Cleanup (runs every 1 second)

| State | Timeout | Action |
|-------|---------|--------|
| `receivedCallsign` | 5 seconds since `receivedAt` | Clear to empty |
| `receiving` | 5 seconds since `receivingAt` | Clear to false |
| `transmitting` | **No timeout** | Only cleared by explicit `tx_report` |

---

## 4. Map Display

### 4.1 Station Colors

```
Priority: TX > RX > Idle

TX (red):   station.transmitting == true
RX (green): (station.receiving || station.receivedCallsign.isNotEmpty()) && !transmitting
Idle (gray): everything else
```

Additionally, TX status is **inferred from RX relationships**: if any station has `receivedCallsign == "X"`, station X is displayed as TX (red) even without an explicit `tx_report`.

### 4.2 Station Markers

| State | Dot Size | Glow | Label Color |
|-------|----------|------|-------------|
| TX | 11px radius, ring+fill | Red glow (36px + 52px) | Red |
| RX | 8px radius, ring+fill | Green glow (28px) | Green |
| Idle | 6px radius, ring+fill | None | Gray |

### 4.3 Station Labels (two lines)

**Line 1 (top):** Callsign in UPPERCASE, main color, 28px (TX) / 24px (RX/Idle)

**Line 2 (bottom):** Frequency + SNR, semi-transparent, 18px
- Format: `14.236 15dB` (frequency in MHz with 3 decimals + SNR)
- Frequency shown if > 0
- SNR shown if RX state and snr != 0
- Line hidden if both empty

### 4.4 Signal Paths

Two types of signal paths:

#### Confirmed Paths (callsign decoded via EOO)
- **Condition:** `rx.receivedCallsign` is non-empty AND matching TX station exists
- **Line:** Red→Green gradient, dashed, 2.5px stroke
- **Arrow:** Red, at TX end pointing toward RX
- **SNR Badge:** Color-coded (green ≥10dB, yellow ≥3dB, red <3dB), white text

#### Inferred Paths (frequency matching)
- **Condition:** TX station is transmitting, RX station is receiving (no callsign decoded), same frequency ±5kHz
- **Line:** Light blue (#AAAAFF), dashed, 1.5px stroke, 40% opacity
- **Arrow:** Light blue, 53% opacity
- **SNR Badge:** Gray-purple background, lighter border
- **Purpose:** Show likely reception before EOO callsign is decoded

### 4.5 Arrow Direction

Arrows point FROM the TX station TOWARD the RX station (signal propagation direction). Placed 22px from the TX dot.

---

## 5. Station List (Stations Tab)

### 5.1 Filtering

- Anonymous stations (empty callsign) are hidden
- Band filter chips: All / 160m / 80m / 40m / 20m / 15m / 10m / VHF+
- Station count shows filtered (named) stations only

### 5.2 Card Display

- Callsign (monospace, bold, 16sp)
- RX-only badge icon
- Grid square + mode
- Frequency in MHz (right-aligned, cyan)
- Version string
- **Red background + border** when `transmitting == true`

---

## 6. Connection Management

### 6.1 WebSocket Protocol

```
Engine.IO v4: OPEN(0) CLOSE(1) PING(2) PONG(3) MESSAGE(4)
Socket.IO v4: CONNECT(0) DISCONNECT(1) EVENT(2)

Packet format: [EIO_type][SIO_type][payload]
Example event: 42["event_name",{data}]
```

### 6.2 Reconnection

- **Exponential backoff:** 5s → 10s → 20s → ... → max 300s (5 min)
- **Reset on success:** delay resets to 5s after successful SIO CONNECT
- **Automatic:** reconnects when `config.enabled == true`

### 6.3 Thread Safety

- `stationMap` protected by `synchronized(stationLock)`
- `stationsDirty` flag with `@Volatile`
- `flushStations()` publishes snapshot to `StateFlow` only when dirty
- Called after each WebSocket message + every 1 second by cleanup loop

---

## 7. Coordinate Conversion

### Maidenhead → Lat/Lon (`fromMaidenhead`)

Supports 2, 4, or 6 character grids. Returns center of the grid cell.

```
"PM95ur" → (lat: 35.52, lon: 139.58)
"JO31"   → (lat: 51.5, lon: 3.0)
"QF"     → (lat: -22.5, lon: 143.0)
```

### Lat/Lon → Maidenhead (`toMaidenhead`)

Always returns 6 characters.

```
(35.6762, 139.6503) → "PM95ur"
```
