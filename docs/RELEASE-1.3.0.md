# HyQuery v1.3.0 - Hub Clustering

**JAR Location:** `target/hyquery-plugin-1.3.0-SNAPSHOT.jar`

**Branch:** `feature/hub-clustering`

## New Features

Workers can now send status updates to **multiple primary servers** simultaneously. Each primary independently aggregates network data, so any hub can answer queries with complete network information.

### Architecture

```
     ┌─────────────┐     ┌─────────────┐
     │  Primary A  │     │  Primary B  │  <-- Both hubs have
     │   (US Hub)  │     │  (EU Hub)   │      complete network data
     └──────▲──────┘     └──────▲──────┘
            │                   │
            └─────────┬─────────┘
                      │
            ┌─────────┼─────────┐
            │         │         │
     ┌──────┴───┐ ┌───┴───┐ ┌───┴──────┐
     │ Worker 1 │ │ Wkr 2 │ │ Worker 3 │  <-- Workers push to
     └──────────┘ └───────┘ └──────────┘      ALL primaries
```

## Files Changed

| File | Change |
|------|--------|
| `HyQueryPrimaryTarget.java` | **NEW** - Record for primary server targets |
| `HyQueryNetworkConfig.java` | Added `primaries` list, `getPrimaryTargets()` helper |
| `HyQueryNetworkManager.java` | Sends updates to all configured primaries |
| `pom.xml` | Version 1.3.0-SNAPSHOT |
| `manifest.json` | Version 1.3.0 |
| `README.md` | Full network mode and hub clustering documentation |

## Configuration Examples

### Worker Config (Hub Clustering - Multiple Primaries)

```json
{
  "enabled": true,
  "showPlayerList": true,
  "showPlugins": false,
  "useCustomMotd": false,
  "customMotd": "",
  "rateLimitEnabled": true,
  "rateLimitPerSecond": 10,
  "rateLimitBurst": 20,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "network": {
    "enabled": true,
    "role": "worker",
    "id": "game-1",
    "primaries": [
      { "host": "us-hub.example.com", "port": 5520 },
      { "host": "eu-hub.example.com", "port": 5520 }
    ],
    "key": "your-secret-key-here",
    "updateIntervalSeconds": 5,
    "logStatusUpdates": false
  }
}
```

### Worker Config (Single Primary - Legacy/Simple)

```json
{
  "enabled": true,
  "showPlayerList": true,
  "showPlugins": false,
  "useCustomMotd": false,
  "customMotd": "",
  "rateLimitEnabled": true,
  "rateLimitPerSecond": 10,
  "rateLimitBurst": 20,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "network": {
    "enabled": true,
    "role": "worker",
    "id": "game-1",
    "primaryHost": "hub.example.com",
    "primaryPort": 5520,
    "key": "your-secret-key-here",
    "updateIntervalSeconds": 5,
    "logStatusUpdates": false
  }
}
```

### Primary Config (Hub Server)

```json
{
  "enabled": true,
  "showPlayerList": true,
  "showPlugins": false,
  "useCustomMotd": false,
  "customMotd": "",
  "rateLimitEnabled": true,
  "rateLimitPerSecond": 10,
  "rateLimitBurst": 20,
  "cacheEnabled": true,
  "cacheTtlSeconds": 5,
  "network": {
    "enabled": true,
    "role": "primary",
    "workerTimeoutSeconds": 30,
    "workers": [
      { "id": "game-1", "key": "your-secret-key-here" },
      { "id": "game-2", "key": "your-secret-key-here" },
      { "id": "minigame-*", "key": "shared-minigame-key" }
    ],
    "logStatusUpdates": false
  }
}
```

## Backwards Compatibility

- Single `primaryHost`/`primaryPort` config still works
- `primaries` list takes precedence if configured
- Existing worker configs continue to work unchanged
- Config auto-upgrades on first run with new fields

## Console Output Examples

### Hub Clustering Mode
```
Network mode: WORKER
  - Worker ID: game-1
  - Update interval: 5s
  - Hub clustering: sending to 2 primaries
    - us-hub.example.com:5520
    - eu-hub.example.com:5520
Worker status updates started
```

### Single Primary Mode
```
Network mode: WORKER
  - Worker ID: game-1
  - Update interval: 5s
  - Primary: hub.example.com:5520
Worker status updates started
```

### With Logging Enabled
```
Sent status update to 2/2 primaries (15/100 players)
```

## Testing Checklist

- [ ] Worker with single primary (legacy config) still works
- [ ] Worker with `primaries` list sends to all primaries
- [ ] Each primary correctly aggregates worker data
- [ ] Query any primary and get full network player count
- [ ] Partial failures (one primary down) don't break updates to others
- [ ] `logStatusUpdates: true` shows correct multi-primary logging
