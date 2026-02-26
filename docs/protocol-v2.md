# HyQuery V2 Protocol Specification

This document describes the HyQuery V2 binary protocol for querying Hytale server status over UDP.

## Overview

- **Transport**: UDP
- **Port**: Same as game server (default 5520)
- **Byte Order**: Little-endian
- **String Encoding**: UTF-8, length-prefixed (2-byte length + bytes)
- **Max Packet Size**: 1400 bytes (MTU-safe)

## Protocol Families

HyQuery V2 supports two magic byte families. Both use the same wire format — the only difference is the magic bytes used in requests and responses.

| Family | Request Magic | Response Magic |
|--------|--------------|----------------|
| HyQuery | `HYQUERY2` | `HYREPLY2` |
| OneQuery | `ONEQUERY` | `ONEREPLY` |

The server echoes the family back — a `HYQUERY2` request receives a `HYREPLY2` response, and a `ONEQUERY` request receives an `ONEREPLY` response.

## Connection Flow

```
Client                          Server
  |                               |
  |-- Challenge Request --------->|
  |<-- Challenge Response --------|
  |                               |
  |-- Query Request ------------->|
  |<-- Query Response ------------|
```

All queries (except challenge) require a valid challenge token to prevent amplification attacks. The token is tied to the client's IP address and expires after a short period.

## Request Format

### Magic Bytes

All requests start with an 8-byte magic string (`HYQUERY2` or `ONEQUERY`).

### Challenge Request

Request a challenge token before making queries.

```
Offset  Size  Field
0       8     Magic (HYQUERY2 or ONEQUERY)
8       1     Type: 0x00 (CHALLENGE)
```

**Total size**: 9 bytes

### Query Request

```
Offset  Size  Field
0       8     Magic (HYQUERY2 or ONEQUERY)
8       1     Type: 0x01 (BASIC) or 0x02 (PLAYERS)
9       32    Challenge Token (from challenge response)
41      4     Request ID (uint32, echoed in response)
45      2     Flags (see Request Flags)
47      4     Offset (uint32, for pagination, used with PLAYERS)
51      ...   Optional: Auth Token (if FLAG_HAS_AUTH_TOKEN set)
```

**Request Flags**:

| Flag | Value | Description |
|------|-------|-------------|
| `FLAG_HAS_AUTH_TOKEN` | `0x0001` | Request includes an auth token |

**Auth Token Format** (when flag is set):

```
Offset  Size  Field
51      2     Token Length (uint16)
53      N     Token Bytes (UTF-8)
```

## Response Format

### Header

All responses start with this header:

```
Offset  Size  Field
0       8     Magic (HYREPLY2 or ONEREPLY)
8       1     Protocol Version (0x01)
9       2     Flags (see Response Flags)
11      4     Request ID (echoed from request)
15      2     Payload Length
17      ...   Payload (TLV-encoded)
```

**Response Flags**:

| Flag | Value | Description |
|------|-------|-------------|
| `FLAG_HAS_MORE_PLAYERS` | `0x0001` | More players available (pagination) |
| `FLAG_AUTH_REQUIRED` | `0x0002` | Authentication required for this endpoint |
| `FLAG_IS_NETWORK` | `0x0010` | Response contains aggregated network data |
| `FLAG_HAS_ADDRESS` | `0x0020` | Response includes host/port |

### Challenge Response

```
Offset  Size  Field
0       8     Magic (HYREPLY2 or ONEREPLY)
8       1     Type: 0x00 (CHALLENGE)
9       32    Challenge Token
41      7     Reserved (zeros)
```

**Total size**: 48 bytes

## TLV Payload Format

Response payloads use Type-Length-Value (TLV) encoding:

```
Offset  Size  Field
0       2     Type (uint16)
2       2     Length (uint16, N)
4       N     Value
```

### TLV Types

| Type | Value | Description |
|------|-------|-------------|
| `SERVER_INFO` | `0x0001` | Server information |
| `PLAYER_LIST` | `0x0002` | Player list |

### Server Info (Type 0x0001)

Returned for BASIC queries.

```
Offset  Size     Field
0       2+N      Server Name (string)
...     2+N      MOTD (string)
...     4        Player Count (int32)
...     4        Max Players (int32)
...     2+N      Version (string)
...     4        Protocol Version (int32)
...     2+N      Protocol Hash (string)
...     2+N      Host (string) - only if FLAG_HAS_ADDRESS
...     2        Port (uint16) - only if FLAG_HAS_ADDRESS
```

### Player List (Type 0x0002)

Returned for PLAYERS queries.

```
Offset  Size     Field
0       4        Total Player Count (across all pages)
4       4        Players in this Response
8       4        Offset (starting index)
12      ...      Player Entries
```

**Player Entry**:

```
Offset  Size     Field
0       2+N      Username (string)
...     8        UUID Most Significant Bits
...     8        UUID Least Significant Bits
```

## Data Types

### String

Length-prefixed UTF-8 string:

```
Offset  Size  Field
0       2     Length (uint16, little-endian)
2       N     UTF-8 Bytes
```

### Integer (int32)

4 bytes, little-endian, signed.

### Short (uint16)

2 bytes, little-endian, unsigned.

### UUID

16 bytes total:
- 8 bytes: Most Significant Bits
- 8 bytes: Least Significant Bits

## Pagination

The PLAYERS endpoint supports pagination for servers with many players.

1. Send a PLAYERS request with `offset = 0`
2. Check `FLAG_HAS_MORE_PLAYERS` in response flags
3. If set, send another request with `offset = previous_offset + players_received`
4. Repeat until `FLAG_HAS_MORE_PLAYERS` is not set

## Authentication

HyQuery supports per-endpoint access control. When `FLAG_AUTH_REQUIRED` is set in the response, the client must retry the request with an auth token by setting `FLAG_HAS_AUTH_TOKEN` in the request flags and appending the token.

Public access permissions are configured server-side. By default, `basic` is public and `players` requires authentication.

## Network Mode

When `FLAG_IS_NETWORK` is set in the response:
- Player counts are aggregated across all servers in the network
- Player list includes players from all servers
- Individual server info reflects the network as a whole

## Error Handling

- **Invalid/expired challenge token**: Request is silently dropped (no response)
- **AUTH_REQUIRED**: Response includes the flag with an empty or minimal payload — retry with an auth token
- **Rate limited**: Request is silently dropped

## Example: Basic Query

```python
import socket
import struct

MAGIC = b"HYQUERY2"
TIMEOUT = 5

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.settimeout(TIMEOUT)

# 1. Request challenge
sock.sendto(MAGIC + b'\x00', (host, port))
data, _ = sock.recvfrom(1400)
token = data[9:41]

# 2. Send basic query
request = MAGIC
request += b'\x01'                        # BASIC type
request += token                          # 32-byte challenge token
request += struct.pack('<I', 1)           # request ID
request += struct.pack('<H', 0)           # flags
request += struct.pack('<I', 0)           # offset
sock.sendto(request, (host, port))
data, _ = sock.recvfrom(1400)

# 3. Parse response header
version = data[8]
flags = struct.unpack_from('<H', data, 9)[0]
request_id = struct.unpack_from('<I', data, 11)[0]
payload_len = struct.unpack_from('<H', data, 15)[0]
payload = data[17:17 + payload_len]

# 4. Parse TLV
tlv_type = struct.unpack_from('<H', payload, 0)[0]
tlv_len = struct.unpack_from('<H', payload, 2)[0]
tlv_data = payload[4:4 + tlv_len]

# 5. Read server info fields from tlv_data
# server_name = read_string(...)
# motd = read_string(...)
# player_count = read_int32(...)
# ...
```

## Version History

| Version | Changes |
|---------|---------|
| 0x01 | Initial V2 release |
