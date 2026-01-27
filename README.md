# HyQuery

A lightweight UDP query protocol plugin for Hytale servers that enables external tools to query server information.

HyQuery uses the same port as your game server by intercepting UDP packets with magic bytes before the QUIC codec processes them, ensuring no additional port configuration is needed.

## Features

- **Zero Port Configuration** - Uses the same port as your game server (typically 5520)
- **Privacy Control** - Anonymous mode by default, optionally show player lists and plugins
- **Custom MOTD** - Support for Minecraft color codes in your MOTD
- **Binary Protocol** - Efficient binary format for fast queries
- **Easy Integration** - Simple UDP protocol for developers

## Installation

### For Server Administrators

1. **Download HyQuery**
   - Download the latest `hyquery-plugin-x.x.x.jar` from the [releases page](https://github.com/hyvote/hyquery/releases)

2. **Install the Plugin**
   ```bash
   # Place the jar file in your server's mods directory
   cp hyquery-plugin-1.0.0.jar /path/to/hytale/server/mods/
   ```

3. **Start Your Server**
   - The plugin will automatically create a configuration file at `mods/HyQuery/config.json`

4. **Configure (Optional)**
   - Edit `mods/HyQuery/config.json` to customize behavior (see Configuration section)

5. **Restart the Server**
   - Changes to the configuration require a server restart

## Configuration

Configuration file: `mods/HyQuery/config.json`

```json
{
  "enabled": true,
  "showPlayerList": false,
  "showPlugins": false,
  "useCustomMotd": false,
  "customMotd": "§aWelcome to §l§cHytale§r§a! §6Enjoy your stay!"
}
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable the query server |
| `showPlayerList` | boolean | `false` | Include online player names and UUIDs in responses |
| `showPlugins` | boolean | `false` | Include installed plugin list in responses |
| `useCustomMotd` | boolean | `false` | Use custom MOTD instead of server config MOTD |
| `customMotd` | string | `"§aWelcome..."` | Custom MOTD with Minecraft color code support |

### Security Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `rateLimitEnabled` | boolean | `true` | Enable per-IP rate limiting |
| `rateLimitPerSecond` | int | `10` | Maximum requests per second per IP |
| `rateLimitBurst` | int | `20` | Maximum burst requests allowed |
| `cacheEnabled` | boolean | `true` | Enable response caching |
| `cacheTtlSeconds` | int | `5` | Cache time-to-live in seconds |

## Network Mode

HyQuery supports multi-server network configurations with **primary** and **worker** roles. This allows server networks to aggregate player counts and information across multiple servers.

### Architecture

```
                    ┌─────────────┐
                    │   Primary   │ ◄── Query responses include
                    │   (Hub)     │     all network players
                    └──────▲──────┘
                           │
            ┌──────────────┼──────────────┐
            │              │              │
     ┌──────┴──────┐ ┌─────┴─────┐ ┌──────┴──────┐
     │   Worker    │ │  Worker   │ │   Worker    │
     │  (Game 1)   │ │ (Game 2)  │ │  (Game 3)   │
     └─────────────┘ └───────────┘ └─────────────┘
```

- **Primary**: Receives status updates from workers, aggregates data for query responses
- **Worker**: Sends periodic status updates to primary server(s)

### Primary Server Configuration

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

**Primary-specific options:**

| Option | Description |
|--------|-------------|
| `workerTimeoutSeconds` | Seconds before marking a worker as offline |
| `workers` | List of authorized workers with their IDs and HMAC keys |

Worker IDs support wildcard matching with `*` (e.g., `minigame-*` matches `minigame-1`, `minigame-lobby`, etc.)

### Worker Server Configuration (Single Primary)

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

**Worker-specific options:**

| Option | Description |
|--------|-------------|
| `id` | Unique identifier for this worker (must match primary's workers list) |
| `primaryHost` | Hostname or IP of the primary server |
| `primaryPort` | Port of the primary server |
| `key` | Shared HMAC secret (must match primary's key for this worker) |
| `updateIntervalSeconds` | How often to send status updates |

## Hub Clustering

For networks with multiple hub servers (load-balanced or regional), workers can send status updates to **all** primary servers. This ensures any hub can answer queries with complete network data.

### Hub Clustering Architecture

```
     ┌─────────────┐     ┌─────────────┐
     │  Primary A  │     │  Primary B  │  ◄── Both hubs have
     │   (US Hub)  │     │  (EU Hub)   │      complete network data
     └──────▲──────┘     └──────▲──────┘
            │                   │
            └─────────┬─────────┘
                      │
            ┌─────────┼─────────┐
            │         │         │
     ┌──────┴───┐ ┌───┴───┐ ┌───┴──────┐
     │ Worker 1 │ │ Wkr 2 │ │ Worker 3 │  ◄── Workers push to
     └──────────┘ └───────┘ └──────────┘      ALL primaries
```

### Worker Configuration (Hub Clustering)

Use the `primaries` array instead of single `primaryHost`/`primaryPort`:

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

**Notes:**
- The `primaries` list takes precedence over legacy `primaryHost`/`primaryPort`
- All primaries must have this worker authorized with the same key
- Status updates are sent to all primaries simultaneously
- If some primaries are unreachable, updates continue to the available ones

### Network Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `network.enabled` | boolean | `false` | Enable network mode |
| `network.role` | string | `"worker"` | Server role: `"primary"` or `"worker"` |
| `network.logStatusUpdates` | boolean | `false` | Log status update activity |

**Primary-only:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `network.workerTimeoutSeconds` | int | `30` | Seconds before worker marked offline |
| `network.workers` | array | `[]` | Authorized workers `[{id, key}, ...]` |

**Worker-only:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `network.id` | string | `"server-1"` | This worker's unique identifier |
| `network.primaryHost` | string | `"localhost"` | Legacy: single primary host |
| `network.primaryPort` | int | `5520` | Legacy: single primary port |
| `network.primaries` | array | `[]` | Hub clustering: `[{host, port}, ...]` |
| `network.key` | string | `"change-me"` | Shared HMAC secret |
| `network.updateIntervalSeconds` | int | `5` | Update interval in seconds |

### Minecraft Color Codes

When `useCustomMotd` is enabled, you can use Minecraft formatting codes in your MOTD.

**Note:** These color codes currently have no impact on the in-game display. They are preserved in the query response for external tools such as server lists to display formatted MOTDs.

**Colors:**
- `§0` Black, `§1` Dark Blue, `§2` Dark Green, `§3` Dark Aqua
- `§4` Dark Red, `§5` Dark Purple, `§6` Gold, `§7` Gray
- `§8` Dark Gray, `§9` Blue, `§a` Green, `§b` Aqua
- `§c` Red, `§d` Light Purple, `§e` Yellow, `§f` White

**Formatting:**
- `§l` Bold, `§m` Strikethrough, `§n` Underline, `§o` Italic, `§r` Reset

**Example:**
```json
"customMotd": "§aWelcome to §l§6MyServer§r§a! §bHave fun!"
```

## Developer Integration

### Protocol Specification

HyQuery uses a simple binary protocol over UDP on the game server port (default: 5520).

#### Request Format

```
┌──────────┬─────────────┐
│ Magic    │ Query Type  │
│ 8 bytes  │ 1 byte      │
└──────────┴─────────────┘
```

- **Magic Bytes**: `HYQUERY\0` (ASCII, null-terminated)
- **Query Type**:
  - `0x00` - Basic query (server info only)
  - `0x01` - Full query (includes player list and plugins if enabled)

#### Response Format

All multi-byte integers use **little-endian** byte order.

**Response Header:**
```
┌──────────┬─────────────┐
│ Magic    │ Type        │
│ 8 bytes  │ 1 byte      │
└──────────┴─────────────┘
```

- **Magic Bytes**: `HYREPLY\0` (ASCII, null-terminated)
- **Type**: `0x00` (basic) or `0x01` (full)

**Basic Response (Type 0x00):**

| Field | Type | Description |
|-------|------|-------------|
| Server Name | String | Length-prefixed UTF-8 string |
| MOTD | String | Length-prefixed UTF-8 string |
| Online Players | uint32 LE | Current player count |
| Max Players | uint32 LE | Maximum player capacity |
| Port | uint32 LE | Server port |
| Version | String | Length-prefixed UTF-8 string |

**Full Response (Type 0x01):**

Includes all basic fields plus:

| Field | Type | Description |
|-------|------|-------------|
| Player Count | uint32 LE | Number of player entries |
| Player Entries | Array | For each player: |
| - Username | String | Length-prefixed UTF-8 string |
| - UUID | 16 bytes | UUID (MSB 8 bytes + LSB 8 bytes) |
| Plugin Count | uint32 LE | Number of plugin entries |
| Plugin Entries | Array | For each plugin: |
| - Name | String | Length-prefixed UTF-8 string (format: "group:name") |

**String Format:**
```
┌──────────┬───────────┐
│ Length   │ Data      │
│ uint16LE │ UTF-8     │
└──────────┴───────────┘
```

### Example Implementations

#### Python

```python
import socket
import struct

REQUEST_MAGIC = b"HYQUERY\0"
RESPONSE_MAGIC = b"HYREPLY\0"
TYPE_BASIC = 0x00
TYPE_FULL = 0x01

def query_server(host, port, query_type=TYPE_BASIC):
    # Create request
    request = REQUEST_MAGIC + bytes([query_type])

    # Send query
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5.0)
    sock.sendto(request, (host, port))

    # Receive response
    data, _ = sock.recvfrom(65535)
    sock.close()

    return parse_response(data)

def parse_response(data):
    if not data.startswith(RESPONSE_MAGIC):
        raise ValueError("Invalid response magic bytes")

    offset = len(RESPONSE_MAGIC)
    response_type = data[offset]
    offset += 1

    # Read strings
    def read_string(offset):
        length = struct.unpack_from('<H', data, offset)[0]
        offset += 2
        string = data[offset:offset + length].decode('utf-8')
        return string, offset + length

    # Parse basic info
    result = {}
    result['serverName'], offset = read_string(offset)
    result['motd'], offset = read_string(offset)
    result['onlinePlayers'] = struct.unpack_from('<I', data, offset)[0]
    offset += 4
    result['maxPlayers'] = struct.unpack_from('<I', data, offset)[0]
    offset += 4
    result['port'] = struct.unpack_from('<I', data, offset)[0]
    offset += 4
    result['version'], offset = read_string(offset)

    # Parse full response data
    if response_type == TYPE_FULL:
        player_count = struct.unpack_from('<I', data, offset)[0]
        offset += 4

        players = []
        for _ in range(player_count):
            username, offset = read_string(offset)
            uuid_bytes = data[offset:offset + 16]
            offset += 16
            players.append({'username': username, 'uuid': uuid_bytes.hex()})
        result['players'] = players

        plugin_count = struct.unpack_from('<I', data, offset)[0]
        offset += 4

        plugins = []
        for _ in range(plugin_count):
            plugin_name, offset = read_string(offset)
            plugins.append(plugin_name)
        result['plugins'] = plugins

    return result

# Usage
info = query_server('localhost', 5520, TYPE_BASIC)
print(f"Server: {info['serverName']}")
print(f"Players: {info['onlinePlayers']}/{info['maxPlayers']}")
```

#### JavaScript (Node.js)

```javascript
const dgram = require('dgram');

const REQUEST_MAGIC = Buffer.from('HYQUERY\0', 'ascii');
const RESPONSE_MAGIC = Buffer.from('HYREPLY\0', 'ascii');
const TYPE_BASIC = 0x00;
const TYPE_FULL = 0x01;

function queryServer(host, port, queryType = TYPE_BASIC) {
    return new Promise((resolve, reject) => {
        const request = Buffer.concat([REQUEST_MAGIC, Buffer.from([queryType])]);
        const client = dgram.createSocket('udp4');

        client.on('message', (msg) => {
            client.close();
            try {
                resolve(parseResponse(msg));
            } catch (err) {
                reject(err);
            }
        });

        client.on('error', (err) => {
            client.close();
            reject(err);
        });

        setTimeout(() => {
            client.close();
            reject(new Error('Timeout'));
        }, 5000);

        client.send(request, port, host);
    });
}

function parseResponse(data) {
    if (!data.subarray(0, 8).equals(RESPONSE_MAGIC)) {
        throw new Error('Invalid response magic bytes');
    }

    let offset = 8;
    const responseType = data[offset++];

    const readString = () => {
        const length = data.readUInt16LE(offset);
        offset += 2;
        const str = data.subarray(offset, offset + length).toString('utf-8');
        offset += length;
        return str;
    };

    const result = {
        serverName: readString(),
        motd: readString(),
        onlinePlayers: data.readUInt32LE(offset),
        maxPlayers: data.readUInt32LE(offset + 4),
        port: data.readUInt32LE(offset + 8),
    };
    offset += 12;
    result.version = readString();

    if (responseType === TYPE_FULL) {
        const playerCount = data.readUInt32LE(offset);
        offset += 4;

        result.players = [];
        for (let i = 0; i < playerCount; i++) {
            const username = readString();
            const uuid = data.subarray(offset, offset + 16);
            offset += 16;
            result.players.push({ username, uuid: uuid.toString('hex') });
        }

        const pluginCount = data.readUInt32LE(offset);
        offset += 4;

        result.plugins = [];
        for (let i = 0; i < pluginCount; i++) {
            result.plugins.push(readString());
        }
    }

    return result;
}

// Usage
queryServer('localhost', 5520, TYPE_BASIC)
    .then(info => {
        console.log(`Server: ${info.serverName}`);
        console.log(`Players: ${info.onlinePlayers}/${info.maxPlayers}`);
    })
    .catch(err => console.error('Query failed:', err));
```

#### Java

```java
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class HyQueryClient {
    private static final byte[] REQUEST_MAGIC = "HYQUERY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE_MAGIC = "HYREPLY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte TYPE_BASIC = 0x00;
    private static final byte TYPE_FULL = 0x01;

    public static QueryResponse query(String host, int port, byte queryType) throws IOException {
        // Create request
        byte[] request = new byte[REQUEST_MAGIC.length + 1];
        System.arraycopy(REQUEST_MAGIC, 0, request, 0, REQUEST_MAGIC.length);
        request[REQUEST_MAGIC.length] = queryType;

        // Send query
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(5000);

        InetAddress address = InetAddress.getByName(host);
        DatagramPacket packet = new DatagramPacket(request, request.length, address, port);
        socket.send(packet);

        // Receive response
        byte[] buffer = new byte[65535];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);
        socket.close();

        return parseResponse(response.getData(), response.getLength());
    }

    private static QueryResponse parseResponse(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);

        // Check magic bytes
        byte[] magic = new byte[RESPONSE_MAGIC.length];
        buf.get(magic);

        byte responseType = buf.get();

        QueryResponse result = new QueryResponse();
        result.serverName = readString(buf);
        result.motd = readString(buf);
        result.onlinePlayers = buf.getInt();
        result.maxPlayers = buf.getInt();
        result.port = buf.getInt();
        result.version = readString(buf);

        // Parse full response if needed
        if (responseType == TYPE_FULL) {
            int playerCount = buf.getInt();
            result.players = new Player[playerCount];
            for (int i = 0; i < playerCount; i++) {
                String username = readString(buf);
                byte[] uuid = new byte[16];
                buf.get(uuid);
                result.players[i] = new Player(username, uuid);
            }

            int pluginCount = buf.getInt();
            result.plugins = new String[pluginCount];
            for (int i = 0; i < pluginCount; i++) {
                result.plugins[i] = readString(buf);
            }
        }

        return result;
    }

    private static String readString(ByteBuffer buf) {
        int length = buf.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        QueryResponse info = query("localhost", 5520, TYPE_BASIC);
        System.out.println("Server: " + info.serverName);
        System.out.println("Players: " + info.onlinePlayers + "/" + info.maxPlayers);
    }
}
```

## Building from Source

### Prerequisites

- Java 25 or higher
- Maven 3.6+
- HytaleServer.jar (place in project root)

### Build Steps

```bash
# Clone the repository
git clone https://github.com/hyvote/hyquery.git
cd hyquery

# Place HytaleServer.jar in the project root
cp /path/to/HytaleServer.jar .

# Build with Maven
mvn clean package

# The compiled jar will be in target/
ls target/hyquery-plugin-*.jar
```

## Troubleshooting

### Plugin not loading

- Check that the jar file is in the `mods/` directory
- Verify server logs for error messages
- Ensure you're running a compatible Hytale server version

### No response to queries

- Check that `enabled` is `true` in config.json
- Verify the server port (default: 5520)
- Check firewall rules allow UDP traffic
- Review server logs for HyQuery errors

### Player list / plugins not showing

- Set `showPlayerList` or `showPlugins` to `true` in config
- Use query type `0x01` (full query) instead of `0x00` (basic)
- Restart the server after config changes

## License

MIT License - See LICENSE file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues, questions, or feature requests, please open an issue on the [GitHub repository](https://github.com/hyvote/hyquery/issues).
