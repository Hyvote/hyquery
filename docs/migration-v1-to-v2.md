# Migrating from HyQuery V1 to V2

V2 overhauls how HyQuery works under the hood. Servers now communicate through Redis rather than direct UDP — similar to how BungeeCord handles cross-server state in Minecraft. On top of that, V2 adds a challenge-token handshake to prevent UDP amplification attacks, and uses new protocol magic bytes (`HYQUERY2`/`HYREPLY2`).

V2 also adds support for `ONEQUERY`/`ONEREPLY` as an alternative query protocol. Our feedback received has been that many server owners prefer this over other implementations that automatically register your server on third-party lists. HyQuery does not register your server anywhere — it simply responds to queries.

## What's Changing

- **Coordinator**: Moves from direct UDP to Redis pub/sub
- **Security**: Challenge-token handshake prevents spoofed queries
- **Protocol magic**: Changes from `HYQUERY`/`HYREPLY` to `HYQUERY2`/`HYREPLY2`
- **OneQuery support**: `ONEQUERY`/`ONEREPLY` available as an alternative query protocol
- **Network state**: Server status is published to Redis, making it available to all nodes in the network

## Migration Steps

### 1. Install Redis

You'll need a Redis server accessible to all your game servers. This can run on the same machine as your primary server, or on a dedicated host. Install it using your OS package manager and secure it with a strong password.

If your servers are on different machines, make sure Redis is bound to an accessible interface and the port (default 6379) is open in your firewall.

### 2. Configure Each Server

In each server's `mods/HyQuery/config.json`, update the `network.redis` block with your Redis credentials:

```json
"redis": {
  "host": "127.0.0.1",
  "port": 6379,
  "username": "",
  "password": "your-redis-password",
  "database": 0,
  "tls": false,
  "connectTimeoutMillis": 1000,
  "readTimeoutMillis": 1000,
  "publishIntervalSeconds": 5,
  "requireAvailable": true
}
```

If Redis is running on the same machine, use `127.0.0.1`. For a remote Redis instance, use the appropriate hostname or IP.

### 3. Switch the Coordinator to Redis

In the same config file, change the coordinator from UDP to Redis:

```json
"coordinator": "redis"
```

This tells HyQuery to use Redis as the backbone for cross-server communication rather than direct UDP.

Restart your game servers for the changes to take effect.

### 4. Disable V1

V2 is **not** backwards compatible — the protocol magic bytes have changed, so V1 clients cannot talk to V2 and vice versa. Keep both enabled while server listing sites and any other services that query your server add V2 support. Once they have, disable V1:

```json
"v1Enabled": false
```

## Summary

| | V1 | V2 |
|---|---|---|
| Coordinator | UDP | Redis |
| Query magic | `HYQUERY`/`HYREPLY` | `HYQUERY2`/`HYREPLY2` (or `ONEQUERY`/`ONEREPLY`) |
| Anti-spoofing | None | Challenge token |
| Network state | Direct server-to-server | Centralised via Redis |

The migration is straightforward — install Redis, point your configs at it, switch the coordinator, and once you're happy, turn off V1. If you run into any issues, check that your Redis instance is reachable from all servers and that firewall rules are in place.
