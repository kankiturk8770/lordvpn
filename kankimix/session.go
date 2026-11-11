package kankimix

import (
	"crypto/rand"
	"encoding/binary"
	"net/netip"
)

// Session establishment
//
// The pre-shared secret is never used directly as a traffic key. Each session
// runs a one-round-trip handshake over long-form packets sealed with a static
// handshake key and a random 96-bit nonce:
//
//	client -> server  INIT   { generation(8), clientRandom(16) } + random padding
//	server -> client  ACCEPT { generation(8), clientRandom(16), serverRandom(16) }
//
//	sessionKey = HMAC(psk, "session" | CID | clientRandom | serverRandom)
//
// Every session therefore has fresh per-path, per-direction AES-256-GCM keys,
// and packet numbers restart from 1 only under a new key: application restarts
// can no longer cause catastrophic AES-GCM nonce reuse, and packets captured
// from an old session cannot be replayed into a new one.
//
// Replay of INIT is rejected by a strictly increasing generation (unless the
// current session is idle/dead). Replay of ACCEPT is rejected because it must
// echo the client's current random. The server learns the client's address
// from the INIT, so clients behind NAT work without prior configuration. If a
// client loses all paths for long enough (e.g. the server restarted) it
// re-handshakes automatically while old keys keep working until replaced.

const (
	initBodyLen      = 1 + 8 + 16
	acceptBodyLen    = 1 + 8 + 16 + 16
	maxHandshakePad  = 48
	initialInitDelay = 200_000
	maxInitDelay     = 2_000_000
	staleSession     = 10_000_000
)

func randomPad(buf []byte, base int) int {
	var r [1]byte
	_, _ = rand.Read(r[:])
	n := int(r[0]) % (maxHandshakePad + 1)
	clear(buf[base : base+n])
	return base + n
}

// startHandshakeLocked begins (or restarts) the client handshake. sessMu held.
func (c *Core) startHandshakeLocked(now int64) {
	_, _ = rand.Read(c.clientRandom[:])
	generation := uint64(now)
	if generation <= c.hsGeneration {
		generation = c.hsGeneration + 1
	}
	c.hsGeneration = generation
	c.handshaking = true
	c.initInterval = initialInitDelay
	c.nextInitAt = 0
}

// maintainSession drives client handshake retransmission and re-handshake,
// returning the next wake-up time.
func (c *Core) maintainSession(now int64) int64 {
	next := now + 250_000
	if c.config.Role != RoleClient {
		return next
	}
	c.sessMu.Lock()
	defer c.sessMu.Unlock()
	if c.established.Load() && !c.handshaking && !c.anyUsable() {
		silent := now - c.lastAnyRecv()
		if silent > 2*c.config.PathTimeout.Microseconds() {
			c.startHandshakeLocked(now)
		}
	}
	if !c.handshaking {
		return next
	}
	if now >= c.nextInitAt {
		var buf [initBodyLen + maxHandshakePad]byte
		buf[0] = kindInit
		binary.BigEndian.PutUint64(buf[1:9], c.hsGeneration)
		copy(buf[9:25], c.clientRandom[:])
		length := randomPad(buf[:], initBodyLen)
		for _, p := range c.snapshot() {
			if !p.closed.Load() {
				_ = p.sendLong(buf[:length], p.remoteAddress())
			}
		}
		c.nextInitAt = now + c.initInterval
		c.initInterval = min(c.initInterval*2, maxInitDelay)
	}
	return min(next, c.nextInitAt)
}

// handleLong processes an unmasked long-form (handshake) datagram.
func (c *Core) handleLong(p *Path, wire []byte, source netip.AddrPort, now int64) {
	if len(wire) < longHeaderLen+1+tagLen {
		c.statsInvalidPackets.Add(1)
		return
	}
	copy(p.rxAAD[:], wire[:longHeaderLen])
	body, err := c.hsRecv.Open(p.decryptBuf[:0], wire[1:longHeaderLen], wire[longHeaderLen:], p.rxAAD[:])
	if err != nil {
		p.statsDecodeErr.Add(1)
		return
	}
	switch {
	case body[0] == kindInit && c.config.Role == RoleServer && len(body) >= initBodyLen:
		c.handleInit(p, body, source, now)
	case body[0] == kindAccept && c.config.Role == RoleClient && len(body) >= acceptBodyLen:
		c.handleAccept(p, body, now)
	default:
		c.statsInvalidPackets.Add(1)
	}
}

func (c *Core) handleInit(p *Path, body []byte, source netip.AddrPort, now int64) {
	generation := binary.BigEndian.Uint64(body[1:9])
	var clientRandom [16]byte
	copy(clientRandom[:], body[9:25])

	c.sessMu.Lock()
	defer c.sessMu.Unlock()
	if c.established.Load() && generation == c.hsGeneration && clientRandom == c.clientRandom {
		// Retransmitted INIT: our ACCEPT was lost. Re-send the identical ACCEPT.
		if !p.remoteAddress().IsValid() {
			p.setRemote(source)
		}
		_ = p.sendLong(c.acceptCache, source)
		return
	}
	fresh := !c.established.Load() || generation > c.hsGeneration || now-c.lastAnyRecv() > staleSession
	if !fresh {
		c.statsReplayDropped.Add(1)
		return
	}
	var serverRandom [16]byte
	if _, err := rand.Read(serverRandom[:]); err != nil {
		return
	}
	c.hsGeneration = generation
	c.clientRandom = clientRandom
	c.serverRandom = serverRandom
	key := kdf(c.psk[:], "session", u64bytes(c.config.ConnectionID), clientRandom[:], serverRandom[:])

	buf := make([]byte, acceptBodyLen+maxHandshakePad)
	buf[0] = kindAccept
	binary.BigEndian.PutUint64(buf[1:9], generation)
	copy(buf[9:25], clientRandom[:])
	copy(buf[25:41], serverRandom[:])
	c.acceptCache = buf[:randomPad(buf, acceptBodyLen)]

	if p.remoteAddress() != source {
		p.setRemote(source)
	}
	if !c.installSessionLocked(key, now, p) {
		return
	}
	_ = p.sendLong(c.acceptCache, source)
}

func (c *Core) handleAccept(p *Path, body []byte, now int64) {
	generation := binary.BigEndian.Uint64(body[1:9])
	c.sessMu.Lock()
	defer c.sessMu.Unlock()
	if !c.handshaking || generation != c.hsGeneration || [16]byte(body[9:25]) != c.clientRandom {
		c.statsReplayDropped.Add(1)
		return
	}
	copy(c.serverRandom[:], body[25:41])
	key := kdf(c.psk[:], "session", u64bytes(c.config.ConnectionID), c.clientRandom[:], c.serverRandom[:])
	if c.installSessionLocked(key, now, p) {
		c.handshaking = false
	}
}

// installSessionLocked switches every path to fresh keys for a new session
// generation. The path that carried the handshake is immediately valid (its
// peer address was just proven); other paths are revalidated by probes.
func (c *Core) installSessionLocked(key [32]byte, now int64, via *Path) bool {
	paths := c.snapshot()
	gen := c.sessionGen.Load() + 1
	derived := make([]*pathKeys, len(paths))
	for i, p := range paths {
		k, err := derivePathKeys(&key, c.config.Role, p.id, gen)
		if err != nil {
			return false
		}
		derived[i] = k
	}
	c.sessionKey = key
	c.sessionGen.Store(gen)
	c.rx.reset(gen)
	for i, p := range paths {
		p.keys.Store(derived[i])
		p.resetProbe()
		p.valid.Store(p == via)
	}
	via.lastRecv.Store(now)
	c.established.Store(true)
	c.statsHandshakes.Add(1)
	c.wakeMaintenance()
	return true
}
