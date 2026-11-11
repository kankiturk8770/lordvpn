# KankiMix v2 core

Encrypted, deadline-aware, multipath UDP session core for latency-critical
traffic (mobile gaming, voice, control streams). Pure Go standard library.

## What changed from v1

| Area | v1 problem | v2 |
|---|---|---|
| FEC | Only repairs with an identical `base` were combined; repairs of consecutive windows overwrote each other in the cache, so 2+ consecutive losses were unrecoverable | On-the-fly Gauss-Jordan decoder over **all** stored equations regardless of base/size; known symbols are substituted immediately; repairs with no unknowns are dropped in O(window) |
| FEC rate | 1-3 repairs per packet on any loss sign | Fractional, loss-adaptive repair budget (credit) + burst-tail repair; never above `MaxRepairs` |
| Bufferbloat | No congestion awareness | Windowed min-RTT, queue delay = sRTT − minRTT; FEC cut/stopped when queue delay grows |
| Loss estimate | Every ACK-bitmap hole counted as loss; reordering = loss | RFC 9002 packet + time thresholds, adaptive reordering window learned from spurious losses |
| Path setup | One PATH_CHALLENGE, never retried | Probes with backoff until validated; keepalive for NAT; liveness detection, failover and re-probing |
| NAT | Server had to know client IP:port; packets from other ports dropped; rebinding killed the path | Server learns address from authenticated packets; rebinding followed and re-validated (with revert) |
| Clock skew | Absolute sender deadlines → peer clock skew dropped everything | Relative TTL on the wire, receiver-local deadlines |
| Crypto | Static keys; restart reused AES-GCM nonces; cross-session replay | 1-RTT PSK handshake, fresh per-session / per-path / per-direction keys, replay-safe INIT/ACCEPT |
| DPI | Cleartext CID, PathID, 64-bit counter | Header protection (AES mask from the tag); every byte pseudorandom; optional length padding |
| Overhead | 74 B/packet | 28 B/packet (5 header + 7 frame + 16 tag) |
| MTU | 1400 B payload → IP fragmentation | Default 1232 B datagrams (fits IPv6 min MTU), configurable to 1472 |
| Multipath | Repairs on the same path as data | Repairs routed over a second comparable path; adaptive duplication; hysteresis |

## Wire format

```
short:  [form|pn32]masked  AES-256-GCM( kind | frame | payload [| pad | padlen] )  tag16
long:   [form|nonce96]masked AES-256-GCM( INIT / ACCEPT )                         tag16

DATA    kind|class  symbol32  ttl_ms16            payload
REPAIR  kind        base32    size8  index8       parity(len16|class|ttl16|payload, padded)
ACK     kind        largest32 delay16 bitmap64
PROBE   kind        token64          (challenge / response)
```

## Build and test

Go 1.23+.

```sh
go vet ./...
go test -race ./...
go test -run '^$' -bench . -benchmem
go test -run '^$' -fuzz FuzzDecoder -fuzztime 60s
go test -run '^$' -fuzz FuzzDatagram -fuzztime 60s
```

## Usage

```go
server, _ := kankimix.NewCore(kankimix.Config{ConnectionID: cid, Role: kankimix.RoleServer, Secret: psk, OnData: onData})
_ = server.AddPath(1, serverUDP, netip.AddrPort{}) // learn client address (CGNAT friendly)

client, _ := kankimix.NewCore(kankimix.Config{ConnectionID: cid, Role: kankimix.RoleClient, Secret: psk, OnData: onData})
_ = client.AddPath(1, wifiUDP, serverAddr)
_ = client.AddPath(2, cellularUDP, serverAddr) // optional second path

_ = client.Submit(packet, kankimix.ClassRealtime, 120*time.Millisecond)
```

`OnData` runs synchronously on a receive goroutine: consume or copy the data
and return quickly; do not call `Close` or `AddPath` from it.

## Scope

This is the transport core. One `Core` = one session; a server serving many
clients uses one Core (and socket) per client. TUN/VpnService integration, key
provisioning and Android socket protection belong to the embedding app. No
transport can guarantee zero loss or undetectability; traffic timing and
volume remain observable.
