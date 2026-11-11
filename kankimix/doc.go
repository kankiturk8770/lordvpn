// Package kankimix implements an encrypted, deadline-aware, multipath UDP
// session core for latency-critical traffic (real-time games, voice, control
// streams).
//
// Main properties:
//
//   - 1-RTT PSK handshake with fresh per-session, per-path, per-direction
//     AES-256-GCM keys (no nonce reuse across restarts, no cross-session replay).
//   - Header protection: every datagram byte is pseudorandom on the wire; no
//     cleartext connection ID, path ID, counters, version or magic.
//   - Systematic sliding-window RLC-FEC over GF(256) with an on-the-fly
//     Gauss-Jordan decoder that jointly solves overlapping windows, so bursts of
//     consecutive losses are recovered.
//   - RFC 9002-style loss detection with adaptive reordering tolerance, windowed
//     min-RTT and queue-delay aware FEC so jitter is not mistaken for loss and
//     redundancy never inflates latency.
//   - Path validation with retry/backoff, keepalives for NAT bindings, liveness
//     detection with failover, NAT rebinding, and server-side address learning.
//   - Receiver-local deadlines (immune to clock skew between peers).
//   - Preallocated, allocation-free datapath.
//
// A Core represents one session between two peers. The embedding application
// provisions the pre-shared secret and ConnectionID, creates UDP sockets and
// applies platform network binding (e.g. Android VpnService.protect).
package kankimix
