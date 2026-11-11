package kankimix

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

const (
	sentRing = 1024
	recvRing = 1024

	stateNone     uint8 = 0
	stateInflight uint8 = 1
	stateAcked    uint8 = 2
	stateLost     uint8 = 3

	ackEvery          = 2
	maxAckDelayMicros = 20_000
	ackDelayUnit      = 16 // microseconds per wire unit
	maxReorderPackets = 16
	maxReorderExtra   = 250_000
	initialReorder    = 3
	initialTimeThresh = 9 // eighths of an RTT (RFC 9002)
	maxTimeThresh     = 24
	minRTTWindow      = 10_000_000
	lossGainShift     = 5 // EWMA gain 1/32
	ppmScale          = 1_000_000
	probeTokens       = 4
	minProbeInterval  = 100_000
	maxProbeInterval  = 2_000_000
	defaultProbeStart = 200_000
)

type sentPacket struct {
	pn       uint64
	at       int64
	state    uint8
	eligible bool
}

// Path is one UDP route belonging to a session. Each path has its own socket,
// keys, packet-number space, RTT/loss estimator, validation state and NAT
// binding; the session (symbol sequence, FEC state) spans all paths.
type Path struct {
	core *Core
	id   uint16
	conn *net.UDPConn

	remoteMu       sync.RWMutex
	remote         netip.AddrPort
	prevRemote     netip.AddrPort
	addrCheckUntil int64

	keys atomic.Pointer[pathKeys]

	// Send side, guarded by sendMu.
	sendMu           sync.Mutex
	sendKeys         *pathKeys
	nextPacket       uint64
	sent             [sentRing]sentPacket
	largestAcked     uint64
	lowestUnresolved uint64
	reorderThreshold uint64
	timeThreshold8   int64
	reorderExtra     int64
	srtt             int64
	rttvar           int64
	latestRTT        int64
	minRTT           int64
	minRTTAt         int64
	hasRTT           bool
	lossPPMv         int64
	rng              uint64
	firstUnanswered  int64
	wireBuf          []byte
	plainBuf         []byte
	sendAAD          [longHeaderLen]byte
	sendNonce        [12]byte
	sendMask         [16]byte

	// Receive side, guarded by rxMu (buffers are owned by the receive goroutine).
	rxMu          sync.Mutex
	rxKeys        *pathKeys
	maxReceived   uint64
	recvSeen      [recvRing]uint64
	largestRecvAt int64
	ackPending    int
	ackDeadline   int64
	rxBuf         []byte
	decryptBuf    []byte
	rxAAD         [longHeaderLen]byte
	rxNonce       [12]byte
	rxMask        [16]byte

	// Probing / validation / liveness, guarded by probeMu.
	probeMu          sync.Mutex
	tokens           [probeTokens]uint64
	tokenAt          [probeTokens]int64
	tokenNext        int
	probeOutstanding bool
	probeCount       int
	nextProbeAt      int64

	valid       atomic.Bool
	closed      atomic.Bool
	lastRecv    atomic.Int64
	lastSend    atomic.Int64 // last ack-eliciting packet
	lastSendAny atomic.Int64
	srttA       atomic.Int64
	minRTTA     atomic.Int64
	lossPPM     atomic.Uint32

	statsSent      atomic.Uint64
	statsReceived  atomic.Uint64
	statsSendErr   atomic.Uint64
	statsDecodeErr atomic.Uint64
	statsACKs      atomic.Uint64
	statsLost      atomic.Uint64
	statsSpurious  atomic.Uint64
}

func newPath(core *Core, id uint16, conn *net.UDPConn, remote netip.AddrPort) *Path {
	var seed [8]byte
	_, _ = rand.Read(seed[:])
	p := &Path{
		core:             core,
		id:               id,
		conn:             conn,
		remote:           remote,
		reorderThreshold: initialReorder,
		timeThreshold8:   initialTimeThresh,
		rng:              binary.LittleEndian.Uint64(seed[:]) | 1,
		wireBuf:          make([]byte, MaxDatagram+64),
		plainBuf:         make([]byte, MaxDatagram+64),
		rxBuf:            make([]byte, MaxDatagram+64),
		decryptBuf:       make([]byte, MaxDatagram+64),
	}
	return p
}

func (p *Path) remoteAddress() netip.AddrPort {
	p.remoteMu.RLock()
	address := p.remote
	p.remoteMu.RUnlock()
	return address
}

// setRemote is an explicit (application-driven) migration.
func (p *Path) setRemote(address netip.AddrPort) {
	p.remoteMu.Lock()
	p.remote = address
	p.prevRemote = netip.AddrPort{}
	p.addrCheckUntil = 0
	p.remoteMu.Unlock()
}

// usable reports whether the scheduler may send application traffic here.
func (p *Path) usable() bool {
	return !p.closed.Load() && p.keys.Load() != nil && p.valid.Load()
}

func (p *Path) queueDelay() int64 {
	srtt, minRTT := p.srttA.Load(), p.minRTTA.Load()
	if srtt <= 0 || minRTT <= 0 || srtt < minRTT {
		return 0
	}
	return srtt - minRTT
}

func (p *Path) oneWayDelay() int64 {
	return p.minRTTA.Load() / 2
}

// ---------------------------------------------------------------- sending

func (p *Path) nextRandom() uint64 {
	p.rng ^= p.rng << 13
	p.rng ^= p.rng >> 7
	p.rng ^= p.rng << 17
	return p.rng
}

func (p *Path) resetSendState(k *pathKeys) {
	p.sendKeys = k
	p.nextPacket = 0
	p.largestAcked = 0
	p.lowestUnresolved = 1
	p.firstUnanswered = 0
	for i := range p.sent {
		p.sent[i] = sentPacket{}
	}
}

// seal encrypts plainBuf[:bodyLength], applies header protection and sends the
// datagram. sendMu must be held. It performs no heap allocation.
func (p *Path) seal(bodyLength int, eligible bool, to netip.AddrPort) error {
	if p.closed.Load() || p.core.closed.Load() {
		return ErrClosed
	}
	k := p.keys.Load()
	if k == nil {
		return ErrNoSession
	}
	if !to.IsValid() {
		return ErrNoRemote
	}
	if k != p.sendKeys {
		p.resetSendState(k)
	}
	limit := p.core.config.MaxDatagram
	if padding := p.core.config.Padding; padding > 0 {
		room := limit - (shortHeaderLen + bodyLength + 1 + tagLen)
		if room >= 0 {
			n := int(p.nextRandom() % uint64(padding+1))
			if n > room {
				n = room
			}
			clear(p.plainBuf[bodyLength : bodyLength+n])
			p.plainBuf[bodyLength+n] = byte(n)
			p.plainBuf[0] |= flagPadded
			bodyLength += n + 1
		}
	}
	if shortHeaderLen+bodyLength+tagLen > limit {
		return ErrPayloadTooLarge
	}

	p.nextPacket++
	packetNumber := p.nextPacket
	p.wireBuf[0] = 0
	binary.BigEndian.PutUint32(p.wireBuf[1:5], uint32(packetNumber))
	copy(p.sendAAD[:shortHeaderLen], p.wireBuf[:shortHeaderLen])
	makeNonce(&p.sendNonce, &k.sendIV, packetNumber)
	wire := k.send.Seal(p.wireBuf[:shortHeaderLen], p.sendNonce[:], p.plainBuf[:bodyLength], p.sendAAD[:shortHeaderLen])
	maskHeader(p.core.hp, wire, &p.sendMask)

	now := time.Now().UnixMicro()
	entry := &p.sent[packetNumber%sentRing]
	*entry = sentPacket{pn: packetNumber, at: now, eligible: eligible}
	if eligible {
		entry.state = stateInflight
		if p.firstUnanswered <= p.lastRecv.Load() {
			p.firstUnanswered = now
		}
		p.lastSend.Store(now)
	}
	p.lastSendAny.Store(now)

	if _, err := p.conn.WriteToUDPAddrPort(wire, to); err != nil {
		p.statsSendErr.Add(1)
		if eligible {
			entry.state = stateNone // never left the host: not a network loss
		}
		return err
	}
	p.statsSent.Add(1)
	return nil
}

func (p *Path) sendData(symbolID uint64, class TrafficClass, ttlMillis uint16, payload []byte) error {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if dataBodyLen+len(payload) > len(p.plainBuf) {
		return ErrPayloadTooLarge
	}
	p.plainBuf[0] = kindData | byte(class)<<classShift
	binary.BigEndian.PutUint32(p.plainBuf[1:5], uint32(symbolID))
	binary.BigEndian.PutUint16(p.plainBuf[5:7], ttlMillis)
	copy(p.plainBuf[dataBodyLen:], payload)
	err := p.seal(dataBodyLen+len(payload), true, p.remoteAddress())
	if err == nil {
		p.core.statsSentData.Add(1)
	}
	return err
}

func (p *Path) sendRepair(base uint64, size, index uint8, parity []byte) error {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if repairBodyLen+len(parity) > len(p.plainBuf) {
		return ErrPayloadTooLarge
	}
	p.plainBuf[0] = kindRepair
	binary.BigEndian.PutUint32(p.plainBuf[1:5], uint32(base))
	p.plainBuf[5] = size
	p.plainBuf[6] = index
	copy(p.plainBuf[repairBodyLen:], parity)
	err := p.seal(repairBodyLen+len(parity), true, p.remoteAddress())
	if err == nil {
		p.core.statsSentRepairs.Add(1)
	}
	return err
}

func (p *Path) sendControl(kind uint8, token uint64, to netip.AddrPort) error {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	p.plainBuf[0] = kind
	binary.BigEndian.PutUint64(p.plainBuf[1:9], token)
	return p.seal(probeBodyLen, kind == kindChallenge, to)
}

// sendLong sends a handshake datagram sealed with the static handshake key and
// a fresh random nonce. Handshakes are rare; this path is not allocation-tuned.
func (p *Path) sendLong(plaintext []byte, to netip.AddrPort) error {
	if !to.IsValid() {
		return ErrNoRemote
	}
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if p.closed.Load() || p.core.closed.Load() {
		return ErrClosed
	}
	p.wireBuf[0] = formLong
	if _, err := rand.Read(p.wireBuf[1:longHeaderLen]); err != nil {
		return err
	}
	copy(p.sendAAD[:], p.wireBuf[:longHeaderLen])
	wire := p.core.hsSend.Seal(p.wireBuf[:longHeaderLen], p.wireBuf[1:longHeaderLen], plaintext, p.sendAAD[:])
	maskHeader(p.core.hp, wire, &p.sendMask)
	p.lastSendAny.Store(time.Now().UnixMicro())
	if _, err := p.conn.WriteToUDPAddrPort(wire, to); err != nil {
		p.statsSendErr.Add(1)
		return err
	}
	p.statsSent.Add(1)
	return nil
}

// ---------------------------------------------------------------- ACK / loss

// flushACK emits an ACK frame for everything received so far.
func (p *Path) flushACK(now int64) {
	p.rxMu.Lock()
	defer p.rxMu.Unlock()
	p.flushACKLocked(now)
}

func (p *Path) flushACKLocked(now int64) {
	if p.ackPending == 0 || p.maxReceived == 0 || p.rxKeys == nil || p.rxKeys != p.keys.Load() {
		p.ackPending = 0
		return
	}
	largest := p.maxReceived
	var bits uint64
	for i := uint64(0); i < 64 && largest > i+1; i++ {
		pn := largest - i - 1
		if p.recvSeen[pn%recvRing] == pn {
			bits |= 1 << i
		}
	}
	delay := (now - p.largestRecvAt) / ackDelayUnit
	if delay < 0 {
		delay = 0
	} else if delay > 0xffff {
		delay = 0xffff
	}
	p.ackPending = 0

	p.sendMu.Lock()
	p.plainBuf[0] = kindACK
	binary.BigEndian.PutUint32(p.plainBuf[1:5], uint32(largest))
	binary.BigEndian.PutUint16(p.plainBuf[5:7], uint16(delay))
	binary.BigEndian.PutUint64(p.plainBuf[7:15], bits)
	if p.seal(ackBodyLen, false, p.remoteAddress()) == nil {
		p.statsACKs.Add(1)
	}
	p.sendMu.Unlock()
}

func (p *Path) onACK(k *pathKeys, body []byte, now int64) {
	if len(body) != ackBodyLen {
		p.core.statsInvalidPackets.Add(1)
		return
	}
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if k != p.sendKeys {
		return
	}
	largest := decodePacketNumber(p.largestAcked, binary.BigEndian.Uint32(body[1:5]))
	delay := int64(binary.BigEndian.Uint16(body[5:7])) * ackDelayUnit
	bits := binary.BigEndian.Uint64(body[7:15])
	if largest == 0 || largest > p.nextPacket {
		p.core.statsInvalidPackets.Add(1)
		return
	}
	newLargest := largest > p.largestAcked
	p.markAcked(largest, now, newLargest, delay)
	for i := uint64(0); i < 64 && largest > i+1; i++ {
		if bits&(1<<i) != 0 {
			p.markAcked(largest-i-1, now, false, 0)
		}
	}
	if newLargest {
		p.largestAcked = largest
	}
	p.detectLosses(now)
}

func (p *Path) markAcked(pn uint64, now int64, rttSample bool, ackDelay int64) {
	entry := &p.sent[pn%sentRing]
	if entry.pn != pn || !entry.eligible {
		return
	}
	switch entry.state {
	case stateInflight:
		entry.state = stateAcked
		p.lossSample(false)
		if rttSample {
			p.updateRTT(now-entry.at, ackDelay, now)
		}
	case stateLost:
		// Declared lost but merely reordered: widen the reordering tolerance so
		// jitter is not misread as loss again (RACK-style adaptation).
		entry.state = stateAcked
		p.statsSpurious.Add(1)
		if late := now - entry.at - p.minRTT; late > p.reorderExtra {
			p.reorderExtra = min(late+late/4, maxReorderExtra)
		}
		if p.reorderThreshold < maxReorderPackets {
			p.reorderThreshold++
		}
		if p.timeThreshold8 < maxTimeThresh {
			p.timeThreshold8 += 2
		}
		p.lossSample(false)
		p.lossSample(false)
	}
}

// detectLosses applies packet- and time-threshold loss detection (RFC 9002).
// A gap in the ACK bitmap alone never counts as loss: the packet must also be
// reorderThreshold packets or ~1.125 RTT behind the largest acknowledgement.
func (p *Path) detectLosses(now int64) {
	if p.largestAcked == 0 {
		return
	}
	if p.lowestUnresolved == 0 {
		p.lowestUnresolved = 1
	}
	if p.largestAcked > sentRing && p.lowestUnresolved < p.largestAcked-sentRing+1 {
		p.lowestUnresolved = p.largestAcked - sentRing + 1
	}
	rtt := p.srtt
	if p.latestRTT > rtt {
		rtt = p.latestRTT
	}
	if rtt == 0 {
		rtt = defaultProbeStart
	}
	lossDelay := rtt*p.timeThreshold8/8 + p.reorderExtra
	p.reorderExtra -= p.reorderExtra >> 10 // forget learned reordering slowly
	if lossDelay < 1000 {
		lossDelay = 1000
	}
	advancing := true
	for pn := p.lowestUnresolved; pn < p.largestAcked; pn++ {
		entry := &p.sent[pn%sentRing]
		if entry.pn == pn && entry.eligible && entry.state == stateInflight {
			if p.largestAcked-pn >= p.reorderThreshold || now-entry.at >= lossDelay {
				entry.state = stateLost
				p.statsLost.Add(1)
				p.lossSample(true)
			} else {
				advancing = false
			}
		}
		if advancing {
			p.lowestUnresolved = pn + 1
		}
	}
}

func (p *Path) lossSample(lost bool) {
	target := int64(0)
	if lost {
		target = ppmScale
	}
	p.lossPPMv += (target - p.lossPPMv) >> lossGainShift
	if p.lossPPMv < 0 {
		p.lossPPMv = 0
	}
	p.lossPPM.Store(uint32(p.lossPPMv))
}

func (p *Path) updateRTT(sample, ackDelay, now int64) {
	if sample <= 0 {
		return
	}
	p.latestRTT = sample
	if !p.hasRTT {
		p.hasRTT = true
		p.srtt = sample
		p.rttvar = sample / 2
		p.minRTT = sample
		p.minRTTAt = now
	} else {
		if sample < p.minRTT {
			p.minRTT, p.minRTTAt = sample, now
		} else if now-p.minRTTAt > minRTTWindow {
			// Windowed minimum: let a path whose base delay grew re-anchor.
			p.minRTT, p.minRTTAt = min(sample, p.srtt), now
		}
		if ackDelay > maxAckDelayMicros {
			ackDelay = maxAckDelayMicros
		}
		adjusted := sample
		if sample-ackDelay >= p.minRTT {
			adjusted -= ackDelay
		}
		diff := p.srtt - adjusted
		if diff < 0 {
			diff = -diff
		}
		p.rttvar = (3*p.rttvar + diff) / 4
		p.srtt = (7*p.srtt + adjusted) / 8
	}
	p.srttA.Store(p.srtt)
	p.minRTTA.Store(p.minRTT)
}

// ---------------------------------------------------------------- probing

func (p *Path) probeIntervalLocked() int64 {
	base := int64(defaultProbeStart)
	if srtt := p.srttA.Load(); srtt > 0 {
		base = 3*srtt + 20_000
	}
	base = max(min(base, 1_000_000), minProbeInterval)
	shift := p.probeCount - 1
	if shift < 0 {
		shift = 0
	} else if shift > 4 {
		shift = 4
	}
	return min(base<<shift, maxProbeInterval)
}

// sendProbeLocked emits a PATH_CHALLENGE with a fresh token. probeMu held.
func (p *Path) sendProbeLocked(now int64) {
	var raw [8]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return
	}
	token := binary.BigEndian.Uint64(raw[:])
	p.tokens[p.tokenNext] = token
	p.tokenAt[p.tokenNext] = now
	p.tokenNext = (p.tokenNext + 1) % probeTokens
	p.probeOutstanding = true
	p.probeCount++
	p.nextProbeAt = now + p.probeIntervalLocked()
	_ = p.sendControl(kindChallenge, token, p.remoteAddress())
}

func (p *Path) probeNow(now int64) {
	p.probeMu.Lock()
	p.sendProbeLocked(now)
	p.probeMu.Unlock()
}

func (p *Path) resetProbe() {
	p.probeMu.Lock()
	p.probeOutstanding = false
	p.probeCount = 0
	p.nextProbeAt = 0
	p.probeMu.Unlock()
}

func (p *Path) onResponse(token uint64, now int64) {
	p.probeMu.Lock()
	match := -1
	for i, t := range p.tokens {
		if t != 0 && t == token {
			match = i
			break
		}
	}
	if match < 0 {
		p.probeMu.Unlock()
		p.core.statsInvalidPackets.Add(1)
		return
	}
	sample := now - p.tokenAt[match]
	p.tokens[match] = 0
	p.probeOutstanding = false
	p.probeCount = 0
	p.valid.Store(true)
	p.probeMu.Unlock()

	p.remoteMu.Lock()
	p.prevRemote = netip.AddrPort{}
	p.addrCheckUntil = 0
	p.remoteMu.Unlock()

	p.sendMu.Lock()
	p.updateRTT(sample, 0, now)
	p.sendMu.Unlock()
}

// onAddressChange follows a peer whose NAT binding or network changed. The new
// address is used immediately (the packet that revealed it was authenticated
// and the newest seen) and is confirmed with a challenge; if confirmation
// fails within PathTimeout the previous address is restored.
func (p *Path) onAddressChange(source netip.AddrPort, now int64) {
	p.remoteMu.Lock()
	if p.remote == source {
		p.remoteMu.Unlock()
		return
	}
	if p.remote.IsValid() && p.addrCheckUntil == 0 {
		p.prevRemote = p.remote
	}
	p.remote = source
	p.addrCheckUntil = now + p.core.config.PathTimeout.Microseconds()
	p.remoteMu.Unlock()
	p.core.statsAddressChanges.Add(1)
	p.probeNow(now)
}

// maintain runs timers for this path and returns the next wake-up time.
func (p *Path) maintain(now int64) int64 {
	next := now + 250_000
	if p.closed.Load() {
		return next
	}

	p.rxMu.Lock()
	if p.ackPending > 0 {
		if now >= p.ackDeadline {
			p.flushACKLocked(now)
		} else {
			next = min(next, p.ackDeadline)
		}
	}
	p.rxMu.Unlock()

	if p.keys.Load() == nil {
		return next
	}

	p.remoteMu.Lock()
	if p.addrCheckUntil != 0 && now >= p.addrCheckUntil {
		if p.prevRemote.IsValid() {
			p.remote = p.prevRemote
		}
		p.prevRemote = netip.AddrPort{}
		p.addrCheckUntil = 0
	}
	hasRemote := p.remote.IsValid()
	p.remoteMu.Unlock()
	if !hasRemote {
		return next
	}

	p.sendMu.Lock()
	p.detectLosses(now)
	firstUnanswered := p.firstUnanswered
	p.sendMu.Unlock()

	config := &p.core.config
	pathTimeout := config.PathTimeout.Microseconds()
	lastRecv := p.lastRecv.Load()
	waiting := int64(0)
	if firstUnanswered > lastRecv {
		waiting = now - firstUnanswered
	}

	p.probeMu.Lock()
	defer p.probeMu.Unlock()
	if !p.valid.Load() {
		if now >= p.nextProbeAt {
			p.sendProbeLocked(now)
		}
		return min(next, p.nextProbeAt)
	}

	suspect := int64(250_000)
	if srtt := p.srttA.Load(); srtt > 0 {
		suspect = max(3*srtt+50_000, 150_000)
	}
	suspect = min(suspect, pathTimeout/2)

	switch {
	case waiting > pathTimeout && p.probeCount >= 2:
		p.valid.Store(false)
		p.core.statsPathFailures.Add(1)
		p.core.notePathFailure()
		if now >= p.nextProbeAt {
			p.sendProbeLocked(now)
		}
	case waiting > suspect && (!p.probeOutstanding || now >= p.nextProbeAt):
		p.sendProbeLocked(now)
	case now-p.lastSendAny.Load() >= config.KeepAlive.Microseconds() && !p.probeOutstanding:
		p.sendProbeLocked(now)
	}
	if p.probeOutstanding {
		next = min(next, p.nextProbeAt)
	}
	if waiting > 0 && waiting <= suspect {
		next = min(next, firstUnanswered+suspect+1)
	}
	next = min(next, p.lastSendAny.Load()+config.KeepAlive.Microseconds())
	return next
}

// ---------------------------------------------------------------- receiving

func (p *Path) receiveLoop() {
	defer p.core.wg.Done()
	for {
		n, source, err := p.conn.ReadFromUDPAddrPort(p.rxBuf[:MaxDatagram+1])
		if err != nil {
			if p.closed.Load() || p.core.closed.Load() || errors.Is(err, net.ErrClosed) {
				return
			}
			p.statsDecodeErr.Add(1)
			continue
		}
		p.processDatagram(p.rxBuf[:n], netip.AddrPortFrom(source.Addr().Unmap(), source.Port()), time.Now().UnixMicro())
	}
}

func (p *Path) processDatagram(wire []byte, source netip.AddrPort, now int64) {
	if len(wire) < shortHeaderLen+1+tagLen || len(wire) > MaxDatagram {
		p.core.statsInvalidPackets.Add(1)
		return
	}
	maskHeader(p.core.hp, wire, &p.rxMask)
	switch wire[0] {
	case formLong:
		p.core.handleLong(p, wire, source, now)
		return
	case 0:
	default:
		p.core.statsInvalidPackets.Add(1)
		return
	}

	k := p.keys.Load()
	if k == nil {
		p.statsDecodeErr.Add(1)
		return
	}

	p.rxMu.Lock()
	if k != p.rxKeys {
		p.rxKeys = k
		p.maxReceived = 0
		p.recvSeen = [recvRing]uint64{}
		p.ackPending = 0
	}
	packetNumber := decodePacketNumber(p.maxReceived, binary.BigEndian.Uint32(wire[1:5]))
	if packetNumber == 0 || packetNumber+recvRing <= p.maxReceived || p.recvSeen[packetNumber%recvRing] == packetNumber {
		p.rxMu.Unlock()
		p.core.statsReplayDropped.Add(1)
		return
	}
	copy(p.rxAAD[:shortHeaderLen], wire[:shortHeaderLen])
	makeNonce(&p.rxNonce, &k.recvIV, packetNumber)
	body, err := k.recv.Open(p.decryptBuf[:0], p.rxNonce[:], wire[shortHeaderLen:], p.rxAAD[:shortHeaderLen])
	if err != nil || len(body) == 0 {
		p.rxMu.Unlock()
		p.statsDecodeErr.Add(1)
		return
	}
	if body[0]&flagPadded != 0 {
		padding := int(body[len(body)-1])
		if padding+1 >= len(body) {
			p.rxMu.Unlock()
			p.core.statsInvalidPackets.Add(1)
			return
		}
		body = body[:len(body)-padding-1]
	}
	previousLargest := p.maxReceived
	newest := packetNumber > previousLargest
	p.recvSeen[packetNumber%recvRing] = packetNumber
	if newest {
		p.maxReceived = packetNumber
		p.largestRecvAt = now
	}
	kind := body[0] & kindMask
	ackNow := false
	if kind == kindData || kind == kindRepair || kind == kindChallenge {
		p.ackPending++
		gap := newest && previousLargest != 0 && packetNumber > previousLargest+1
		if p.ackPending >= ackEvery || gap || !newest {
			ackNow = true
		} else if p.ackPending == 1 {
			p.ackDeadline = now + maxAckDelayMicros
			p.core.wakeMaintenance()
		}
	}
	p.rxMu.Unlock()

	p.lastRecv.Store(now)
	p.statsReceived.Add(1)
	if newest && source != p.remoteAddress() {
		p.onAddressChange(source, now)
	}
	if ackNow {
		p.flushACK(now)
	}

	switch kind {
	case kindData:
		if len(body) <= dataBodyLen {
			p.core.statsInvalidPackets.Add(1)
			return
		}
		class := TrafficClass((body[0] & classMask) >> classShift)
		p.core.rx.handleData(k.gen, p.id, binary.BigEndian.Uint32(body[1:5]), class,
			binary.BigEndian.Uint16(body[5:7]), body[dataBodyLen:], now, p.oneWayDelay())
	case kindRepair:
		if len(body) <= repairBodyLen {
			p.core.statsInvalidPackets.Add(1)
			return
		}
		p.core.rx.handleRepair(k.gen, p.id, binary.BigEndian.Uint32(body[1:5]), body[5], body[6],
			body[repairBodyLen:], now, p.oneWayDelay())
	case kindACK:
		p.onACK(k, body, now)
	case kindChallenge:
		if len(body) != probeBodyLen {
			p.core.statsInvalidPackets.Add(1)
			return
		}
		p.sendMu.Lock()
		p.plainBuf[0] = kindResponse
		copy(p.plainBuf[1:9], body[1:9])
		_ = p.seal(probeBodyLen, false, source)
		p.sendMu.Unlock()
	case kindResponse:
		if len(body) != probeBodyLen {
			p.core.statsInvalidPackets.Add(1)
			return
		}
		p.onResponse(binary.BigEndian.Uint64(body[1:9]), now)
	default:
		p.core.statsInvalidPackets.Add(1)
	}
}

// Stats returns an observational snapshot without stopping the datapath.
func (p *Path) Stats() PathStats {
	return PathStats{
		SentPackets:      p.statsSent.Load(),
		ReceivedPackets:  p.statsReceived.Load(),
		SendErrors:       p.statsSendErr.Load(),
		DecodeErrors:     p.statsDecodeErr.Load(),
		ACKsSent:         p.statsACKs.Load(),
		LostPackets:      p.statsLost.Load(),
		SpuriousLosses:   p.statsSpurious.Load(),
		RTTMicros:        p.srttA.Load(),
		MinRTTMicros:     p.minRTTA.Load(),
		QueueDelayMicros: p.queueDelay(),
		LossPermille:     p.lossPPM.Load() / 1000,
		Valid:            p.valid.Load(),
		Remote:           p.remoteAddress(),
	}
}
