# Lavalink Adaptive Buffer + Gapless (NEXT SLOT) Documentation

This document describes the custom changes applied to this Lavalink fork and how to use them.

## Contents
- Overview
- Config (application.yml)
- Adaptive Opus Buffer
- Underrun Recovery
- Gapless / NEXT SLOT
- Track Preload Event
- REST: /next
- Diagnostics
- Limits and Idle Cleanup
- GC Logging
- Client Integration Checklist
- Example Client Flow

---

## Overview
This fork adds:
- Adaptive Opus buffering with pre-roll and dynamic target buffer.
- Underrun recovery (repeat last frame or comfort-noise).
- Gapless-ready "NEXT SLOT" on the server (1-track buffer per player).
- Track preload hints emitted as protocol events.
- Optional diagnostics logging.
- Resource limits and idle cleanup.

Key goals:
- Stable playback under jitter.
- No silence gaps between tracks when the client preloads and swaps.
- Clear operational controls for VPS safety.

---

## Config (application.yml)
Add these under `lavalink.server`:

```
lavalink:
  server:
    # Existing settings...

    # Adaptive Opus buffer
    minPrerollMs: 300
    dynamicTargetBufferMs: 600  # 300-1500

    # Diagnostics
    audioDiagnostics: false
    diagnosticsIntervalSec: 10

    # Limits
    maxPlayers: 100
    maxBufferPerPlayerMs: 1500
    maxGlobalBufferedMs: 12000
    idleTimeoutSec: 0
```

Notes:
- `minPrerollMs`: minimum buffered Opus before playback starts.
- `dynamicTargetBufferMs`: adaptive target buffer within 300-1500ms.
- `maxBufferPerPlayerMs`: per-player hard cap for buffer target.
- `maxGlobalBufferedMs`: global cap across all players.
- `idleTimeoutSec`: if >0, destroy players that are idle longer than this (seconds).

---

## Adaptive Opus Buffer
Location:
- `LavalinkServer/src/main/java/lavalink/server/player/LavalinkPlayer.kt`

Behavior:
- Buffers Opus frames and starts playback only when `bufferedMs >= minPrerollMs`.
- Target buffer grows when:
  - underrun occurs
  - track stuck event occurs
  - repeated fill starvation (3 consecutive fill attempts with 0 frames while below preroll)
- Target buffer shrinks after 60s without underruns.
- Hard caps applied using `maxBufferPerPlayerMs` and `maxGlobalBufferedMs`.

---

## Underrun Recovery
Location:
- `LavalinkServer/src/main/java/lavalink/server/player/recovery/*`

Strategies:
- `repeat` (default): repeat last valid Opus frame.
- `noise`: comfort-noise frame (F8 FF FE).
- `off`: no recovery.

Config:
```
audio:
  recovery:
    strategy: repeat  # repeat | noise | off
```

Behavior:
- On buffer underrun, recovery frame can be emitted for 3 frames (~60ms).

---

## Gapless / NEXT SLOT
Location:
- `LavalinkServer/src/main/java/lavalink/server/player/LavalinkPlayer.kt`

Server maintains a single "next" slot per player:
- `nextTrackEncoded: String?`
- `nextTrack: AudioTrack?`
- `nextQueued: Boolean` (slot filled)
- `preloadRequested: Boolean` (preload requested once per track)

Swap logic:
- `trySwapNextSoon()` swaps to the next track immediately if present.
- Marker handler swaps instead of stopping when possible.

Marker handling:
- `TrackEndMarkerHandler` calls `trySwapNextSoon()`.
- If no next track, it performs normal stop with `endMarkerHit` set.

---

## Track Preload Event
A new emitted event informs the client to pre-load the next track:

Protocol:
- `TrackPreloadEvent` under `op = "event"` and `type = "TrackPreloadEvent"`.

Location:
- `protocol/src/commonMain/kotlin/dev/arbjerg/lavalink/protocol/v4/messages.kt`

Event payload:
```
{
  "op": "event",
  "type": "TrackPreloadEvent",
  "guildId": "..."
}
```

Server behavior:
- When remaining time <= `preloadAtMs` (default 12000ms) and `preloadRequested` is false,
  the server emits `TrackPreloadEvent` once per track.

---

## REST: /next
Endpoint to fill the server-side NEXT SLOT:

```
POST /v4/sessions/{sessionId}/players/{guildId}/next
```

Body:
```
{
  "encodedTrack": "...",
  "userData": { ... },
  "position": 0,
  "endTime": null
}
```

Behavior:
- Decodes the track and stores it in `nextTrack`.
- Does not start playback immediately.

Location:
- `LavalinkServer/src/main/java/lavalink/server/player/PlayerRestHandler.kt`

---

## Diagnostics
Config:
- `audioDiagnostics: true/false`
- `diagnosticsIntervalSec: 10`

Logs include:
- bufferedMs
- targetBufferMs
- minPrerollMs
- queue size
- underruns
- last fill duration
- recovery frames left

---

## Limits and Idle Cleanup
Limits:
- `maxPlayers`: caps total players per session.
- `maxBufferPerPlayerMs`: caps target buffer per player.
- `maxGlobalBufferedMs`: caps total buffer across players.

Idle cleanup:
- If `idleTimeoutSec > 0`, idle players are destroyed.
- Cleanup is disabled while session is paused (resumable session).

---

## GC Logging
Location:
- `LavalinkServer/src/main/java/lavalink/server/io/SocketContext.kt`

Every 10s:
- logs GC time delta by collector name.
- uses `userId` as a context key in logs.

Format:
```
[gc u=<userId>] <CollectorName> +<delta>ms (count=<count>)
```

---

## Client Integration Checklist
1) Listen for `TrackPreloadEvent`.
2) On `TrackPreloadEvent`, select next track from your queue.
3) POST `/v4/sessions/{sessionId}/players/{guildId}/next` with encoded track.
4) Optionally set an end marker on the current track at `duration - 200..500ms`
   to trigger marker-based swap for smoother gapless.

---

## Example Client Flow
1) Play track A with normal PATCH.
2) Receive `TrackPreloadEvent` ~12s before end.
3) POST `/next` with encoded track B.
4) Set `endTime = duration - 300` via PATCH to trigger marker-based swap.
5) Server swaps to B via `trySwapNextSoon()`.

---

## Code Locations Summary
- Adaptive buffer + recovery + swap logic:
  - `LavalinkServer/src/main/java/lavalink/server/player/LavalinkPlayer.kt`
- Marker swap behavior:
  - `LavalinkServer/src/main/java/lavalink/server/player/TrackEndMarkerHandler.kt`
- Preload event emission:
  - `LavalinkServer/src/main/java/lavalink/server/player/EventEmitter.kt`
- REST endpoint /next:
  - `LavalinkServer/src/main/java/lavalink/server/player/PlayerRestHandler.kt`
- Protocol event definition:
  - `protocol/src/commonMain/kotlin/dev/arbjerg/lavalink/protocol/v4/messages.kt`

---

## Notes
- The NEXT SLOT is intentionally minimal: only one preloaded track per player.
- Full queue logic remains in the client.
- `preloadRequested` is per-track to prevent spam.
