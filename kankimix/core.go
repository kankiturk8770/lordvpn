package kankimix

import (
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

type queuedSymbol struct {
	class    TrafficClass
	deadline int64
	length   int
	data     []byte
}

type historySymbol struct {
	id       uint64
	present  bool
	deadline int64
	length   int
	block    []byte
}

// encoder is the sender-side FEC state. It is owned exclusively by the
// scheduler goroutine and therefore needs no locking.
type encoder struct {
	gen          uint64
	nextSymbol   uint64
	history      [maxWindowSize]historySymbol
	credit       int64 // milli-repair budget (may go negative after a tail repair)
	unprotected  int
	lastBase     uint64
	lastIndex    uint8
	hasLast      bool
	lastSymbolAt int64
	gapAvg       int64
	sizeAvg      int64
	rng          uint64
	primary      *Path
	parity       []byte
}

func (e *encoder) reset(gen uint64) {
	e.gen = gen
	e.nextSymbol = 0
	for i := range e.history {
		e.history[i].present = false
		e.history[i].id = 0
	}
	e.credit = 0
	e.unprotected = 0
	e.hasLast = false
}

// Core owns one KankiMix session. Workers: one scheduler, one maintenance
// timer loop and one receiver per path. AddPath transfers ownership of conn.
type Core struct {
	config Config
	psk    [32]byte
	hp     cipher.Block
	hsSend cipher.AEAD
	hsRecv cipher.AEAD

	submit chan *queuedSymbol
	done   chan struct{}
	wake   chan struct{}
	wg     sync.WaitGroup

	closeOnce    sync.Once
	closed       atomic.Bool
	submissionMu sync.RWMutex

	pathsMu sync.Mutex
	paths   atomic.Pointer[[]*Path]

	pool sync.Pool
	enc  encoder
	rx   *receiver

	sessMu       sync.Mutex
	sessionKey   [32]byte
	established  atomic.Bool
	sessionGen   atomic.Uint64
	handshaking  bool
	hsGeneration uint64
	clientRandom [16]byte
	serverRandom [16]byte
	acceptCache  []byte
	nextInitAt   int64
	initInterval int64

	statsSubmitted      atomic.Uint64
	statsSentData       atomic.Uint64
	statsSentRepairs    atomic.Uint64
	statsDelivered      atomic.Uint64
	statsRecovered      atomic.Uint64
	statsDuplicates     atomic.Uint64
	statsStaleDropped   atomic.Uint64
	statsQueueDropped   atomic.Uint64
	statsNoPathDropped  atomic.Uint64
	statsInvalidPackets atomic.Uint64
	statsReplayDropped  atomic.Uint64
	statsHandshakes     atomic.Uint64
	statsAddressChanges atomic.Uint64
	statsPathFailures   atomic.Uint64
	statsShaped         atomic.Uint64
}

// NewCore validates config and starts the scheduler and maintenance workers.
func NewCore(config Config) (*Core, error) {
	normalized, err := normalizeConfig(config)
	if err != nil {
		return nil, err
	}
	c := &Core{
		config: normalized,
		submit: make(chan *queuedSymbol, normalized.QueueSize),
		done:   make(chan struct{}),
		wake:   make(chan struct{}, 1),
	}
	c.psk = kdf(normalized.Secret, "psk")
	cid := u64bytes(normalized.ConnectionID)
	hpKey := kdf(c.psk[:], "hp", cid)
	if c.hp, err = aes.NewCipher(hpKey[:]); err != nil {
		return nil, err
	}
	sendLabel, recvLabel := directionLabels(normalized.Role)
	if c.hsSend, err = newGCM(kdf(c.psk[:], "hs", []byte(sendLabel), cid)); err != nil {
		return nil, err
	}
	if c.hsRecv, err = newGCM(kdf(c.psk[:], "hs", []byte(recvLabel), cid)); err != nil {
		return nil, err
	}
	empty := make([]*Path, 0)
	c.paths.Store(&empty)
	for i := range c.enc.history {
		c.enc.history[i].block = make([]byte, maxBlockLen)
	}
	c.enc.parity = make([]byte, maxBlockLen)
	c.rx = newReceiver(c)
	c.pool.New = func() any {
		return &queuedSymbol{data: make([]byte, MaxPayload)}
	}
	// Pre-warm the pool so the bounded queue can fill without allocation.
	for i := 0; i < normalized.QueueSize; i++ {
		c.pool.Put(&queuedSymbol{data: make([]byte, MaxPayload)})
	}
	c.wg.Add(2)
	go c.schedulerLoop()
	go c.maintenanceLoop()
	return c, nil
}

// PayloadLimit returns the largest payload Submit accepts with this config.
func (c *Core) PayloadLimit() int {
	return c.config.MaxDatagram - repairOverhead
}

// Established reports whether session keys have been agreed with the peer.
func (c *Core) Established() bool {
	return c.established.Load()
}

func (c *Core) snapshot() []*Path {
	return *c.paths.Load()
}

func (c *Core) findPath(id uint16) *Path {
	for _, p := range c.snapshot() {
		if p.id == id {
			return p
		}
	}
	return nil
}

func (c *Core) wakeMaintenance() {
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

func (c *Core) notePathFailure() {
	c.wakeMaintenance()
}

// AddPath attaches a UDP socket to the session. A client must pass the
// server's address. A server may pass an invalid (zero) AddrPort: the peer's
// address is then learned from its first authenticated packet, which is what
// makes the server reachable from behind carrier-grade NAT. Paths survive NAT
// rebinding automatically.
func (c *Core) AddPath(id uint16, conn *net.UDPConn, remote netip.AddrPort) error {
	if id == 0 || conn == nil {
		return errors.New("kankimix: path ID and UDP connection are required")
	}
	if !remote.IsValid() && c.config.Role == RoleClient {
		return errors.New("kankimix: a client path requires the remote address")
	}
	if remote.IsValid() {
		remote = netip.AddrPortFrom(remote.Addr().Unmap(), remote.Port())
	}
	if c.closed.Load() {
		return ErrClosed
	}

	c.pathsMu.Lock()
	if c.closed.Load() {
		c.pathsMu.Unlock()
		return ErrClosed
	}
	current := c.snapshot()
	if len(current) >= maxPaths {
		c.pathsMu.Unlock()
		return ErrTooManyPaths
	}
	for _, existing := range current {
		if existing.id == id {
			c.pathsMu.Unlock()
			return ErrPathExists
		}
	}
	p := newPath(c, id, conn, remote)

	c.sessMu.Lock()
	if c.established.Load() {
		keys, err := derivePathKeys(&c.sessionKey, c.config.Role, id, c.sessionGen.Load())
		if err != nil {
			c.sessMu.Unlock()
			c.pathsMu.Unlock()
			return err
		}
		p.keys.Store(keys)
	}
	if c.config.Role == RoleClient && !c.established.Load() && !c.handshaking {
		c.startHandshakeLocked(time.Now().UnixMicro())
	}
	if c.config.Role == RoleClient && c.handshaking {
		c.nextInitAt = 0 // send INIT on the new path right away
	}
	c.sessMu.Unlock()

	next := make([]*Path, len(current), len(current)+1)
	copy(next, current)
	next = append(next, p)
	c.paths.Store(&next)
	c.wg.Add(1)
	go p.receiveLoop()
	c.pathsMu.Unlock()

	c.wakeMaintenance()
	return nil
}

// MigratePath changes the remote endpoint of an existing path and revalidates
// it. Other validated paths remain eligible during the migration.
func (c *Core) MigratePath(id uint16, remote netip.AddrPort) error {
	if !remote.IsValid() {
		return errors.New("kankimix: invalid migration address")
	}
	p := c.findPath(id)
	if p == nil {
		return ErrPathNotFound
	}
	p.setRemote(netip.AddrPortFrom(remote.Addr().Unmap(), remote.Port()))
	if p.keys.Load() != nil {
		p.valid.Store(false)
		p.probeNow(time.Now().UnixMicro())
	} else {
		c.sessMu.Lock()
		c.nextInitAt = 0
		c.sessMu.Unlock()
	}
	c.wakeMaintenance()
	return nil
}

// RemovePath closes and detaches a path. The session and its application symbol
// sequence remain alive on every other path.
func (c *Core) RemovePath(id uint16) error {
	c.pathsMu.Lock()
	current := c.snapshot()
	var removed *Path
	next := make([]*Path, 0, len(current))
	for _, p := range current {
		if p.id == id {
			removed = p
			continue
		}
		next = append(next, p)
	}
	if removed == nil {
		c.pathsMu.Unlock()
		return ErrPathNotFound
	}
	c.paths.Store(&next)
	c.pathsMu.Unlock()
	removed.closed.Store(true)
	removed.valid.Store(false)
	return removed.conn.Close()
}

// Submit copies data into a pooled ownership buffer and enqueues it without
// blocking. The caller retains ownership of the input slice.
func (c *Core) Submit(data []byte, class TrafficClass, ttl time.Duration) error {
	if c.closed.Load() {
		return ErrClosed
	}
	if len(data) == 0 {
		return ErrEmptyPayload
	}
	if len(data) > c.PayloadLimit() {
		return ErrPayloadTooLarge
	}
	if ttl <= 0 || ttl > MaxTTL {
		return ErrInvalidDeadline
	}
	if class < ClassRealtime || class > ClassBackground {
		return errors.New("kankimix: invalid traffic class")
	}

	c.submissionMu.RLock()
	defer c.submissionMu.RUnlock()
	if c.closed.Load() {
		return ErrClosed
	}

	symbol := c.pool.Get().(*queuedSymbol)
	symbol.class = class
	symbol.deadline = time.Now().Add(ttl).UnixMicro()
	symbol.length = copy(symbol.data, data)

	select {
	case c.submit <- symbol:
		c.statsSubmitted.Add(1)
		return nil
	default:
		c.statsQueueDropped.Add(1)
		c.releaseSymbol(symbol)
		return ErrQueueFull
	}
}

func (c *Core) releaseSymbol(symbol *queuedSymbol) {
	symbol.class = 0
	symbol.deadline = 0
	symbol.length = 0
	c.pool.Put(symbol)
}

// ---------------------------------------------------------------- scheduler

func (c *Core) schedulerLoop() {
	defer c.wg.Done()
	tail := time.NewTimer(time.Hour)
	tail.Stop()
	armed := false
	for {
		select {
		case symbol := <-c.submit:
			now := time.Now().UnixMicro()
			c.processSymbol(symbol, now)
			c.releaseSymbol(symbol)
			if delay := c.tailDelay(); delay > 0 {
				tail.Reset(time.Duration(delay) * time.Microsecond)
				armed = true
			} else if armed {
				tail.Stop()
				armed = false
			}
		case <-tail.C:
			armed = false
			c.tailRepair(time.Now().UnixMicro())
			if delay := c.tailDelay(); delay > 0 {
				tail.Reset(time.Duration(delay) * time.Microsecond)
				armed = true
			}
		case <-c.done:
			tail.Stop()
			for {
				select {
				case symbol := <-c.submit:
					c.statsStaleDropped.Add(1)
					c.releaseSymbol(symbol)
				default:
					return
				}
			}
		}
	}
}

func (c *Core) processSymbol(symbol *queuedSymbol, now int64) {
	e := &c.enc
	if gen := c.sessionGen.Load(); gen != e.gen {
		e.reset(gen)
	}
	if now >= symbol.deadline {
		c.statsStaleDropped.Add(1)
		return
	}
	primary := c.selectPrimary()
	if primary == nil {
		c.statsNoPathDropped.Add(1)
		return
	}
	if symbol.class == ClassBackground && c.shapeDrop(primary) {
		c.statsShaped.Add(1)
		return
	}
	ttl := (symbol.deadline - now + 999) / 1000
	ttl = max(min(ttl, 65535), 1)
	ttlMillis := uint16(ttl)

	e.nextSymbol++
	id := e.nextSymbol
	h := &e.history[id%maxWindowSize]
	h.id = id
	h.present = true
	h.deadline = symbol.deadline
	h.length = blockMetaLen + symbol.length
	encodeBlockMeta(h.block, symbol.length, symbol.class, ttlMillis)
	copy(h.block[blockMetaLen:], symbol.data[:symbol.length])

	payload := symbol.data[:symbol.length]
	_ = primary.sendData(id, symbol.class, ttlMillis, payload)

	lossPPM := primary.lossPPM.Load()
	// Low-rate realtime streams (game ticks) are duplicated on a second path:
	// it costs a few KB/s and turns an outage of one network into no loss.
	lowRate := e.gapAvg >= 4_000 && e.sizeAvg <= 400
	if symbol.class == ClassCritical || (symbol.class == ClassRealtime && (lossPPM >= 100_000 || lowRate)) {
		if second := c.secondPath(primary); second != nil {
			_ = second.sendData(id, symbol.class, ttlMillis, payload)
		}
	}

	if e.lastSymbolAt > 0 {
		gap := min(now-e.lastSymbolAt, 1_000_000)
		e.gapAvg = (7*e.gapAvg + gap) / 8
	}
	e.lastSymbolAt = now
	e.sizeAvg = (7*e.sizeAvg + int64(h.length)) / 8

	ratio := c.repairRatio(primary, symbol.class)
	if ratio <= 0 {
		return
	}
	e.credit = min(e.credit+ratio, int64(c.config.MaxRepairs)*1000)
	e.unprotected++
	// High-rate streams emit inline to keep up. Low-rate (game tick) streams
	// are paced by the scheduler timer so repairs are spread between data
	// packets instead of riding the same loss burst back-to-back.
	if e.gapAvg < 2_000 {
		for e.credit >= 1000 {
			c.emitRepair(now, primary)
			e.credit -= 1000
		}
	}
}

// shapeDrop is an active queue manager for bulk traffic carried inside the
// tunnel. When the path's queueing delay rises above target, background
// symbols are dropped with a probability that grows with the excess delay.
// Tunnelled TCP reacts by slowing down, so a download cannot build a
// bottleneck queue under the game traffic sharing the link.
func (c *Core) shapeDrop(path *Path) bool {
	queueDelay := path.queueDelay()
	target := max(int64(15_000), path.minRTTA.Load()/4)
	if queueDelay <= target {
		return false
	}
	permille := min((queueDelay-target)*1000/100_000, 1000)
	e := &c.enc
	if e.rng == 0 {
		e.rng = uint64(time.Now().UnixNano()) | 1
	}
	e.rng ^= e.rng << 13
	e.rng ^= e.rng >> 7
	e.rng ^= e.rng << 17
	return int64(e.rng%1000) < permille
}

func (e *encoder) repairPending() bool {
	return e.credit >= 1000 || (e.credit > 0 && e.unprotected > 0)
}

// tailDelay returns how long to wait (µs) before the next paced repair, or 0
// when none is pending.
func (c *Core) tailDelay() int64 {
	e := &c.enc
	if !e.repairPending() {
		return 0
	}
	return max(min(e.gapAvg/3, 8_000), 1_000)
}

// tailRepair emits one paced repair. When traffic pauses it also protects the
// tail of a burst by borrowing from future budget, so the long-run repair rate
// is unchanged while the last packets of a burst stay recoverable.
func (c *Core) tailRepair(now int64) {
	e := &c.enc
	if !e.repairPending() || e.gen != c.sessionGen.Load() {
		return
	}
	primary := c.selectPrimary()
	if primary == nil {
		return
	}
	c.emitRepair(now, primary)
	e.credit -= 1000
}

// repairRatio returns the per-symbol repair budget in thousandths. It scales
// with the ACK-derived loss rate and backs off when queueing delay shows the
// path is congested, so FEC never feeds bufferbloat.
func (c *Core) repairRatio(path *Path, class TrafficClass) int64 {
	loss := int64(path.lossPPM.Load()) // parts per million
	ratio := int64(0)
	if loss >= 3_000 {
		ratio = 3*loss/1000 + 100
	}
	switch class {
	case ClassRealtime:
		// Low-rate small-packet streams (game ticks, voice) cost almost no
		// bandwidth to protect, while every loss is a visible stutter: keep a
		// generous floor so a loss is repaired within about one tick.
		if e := &c.enc; e.gapAvg >= 4_000 && e.sizeAvg <= 400 {
			switch {
			case loss >= 30_000:
				ratio = max(ratio, 2000)
			case loss >= 3_000:
				ratio = max(ratio, 1000)
			default:
				ratio = max(ratio, 500)
			}
		}
	case ClassCritical:
		ratio = max(ratio, 500)
	case ClassBackground:
		if loss < 50_000 {
			ratio = 0
		} else {
			ratio /= 2
		}
	}
	queueDelay := path.queueDelay()
	threshold := max(int64(30_000), path.minRTTA.Load()/2)
	switch {
	case queueDelay > 2*threshold:
		if class == ClassCritical {
			ratio = min(ratio, 250)
		} else {
			ratio = 0
		}
	case queueDelay > threshold:
		if class == ClassBackground {
			ratio = 0
		} else {
			ratio /= 3
		}
	}
	return min(ratio, int64(c.config.MaxRepairs)*1000)
}

func (c *Core) emitRepair(now int64, primary *Path) {
	e := &c.enc
	latest := e.nextSymbol
	if latest == 0 {
		return
	}
	window := uint64(c.config.WindowSize)
	if latest < window {
		window = latest
	}
	base := latest - window + 1
	// Do not spend coding capacity on symbols that are already useless.
	for base < latest {
		h := &e.history[base%maxWindowSize]
		if h.present && h.id == base && h.deadline > now {
			break
		}
		base++
	}
	size := latest - base + 1
	maximum := 0
	for id := base; id <= latest; id++ {
		h := &e.history[id%maxWindowSize]
		if !h.present || h.id != id {
			return
		}
		maximum = max(maximum, h.length)
	}
	index := e.indexFor(base)
	parity := e.parity[:maximum]
	clear(parity)
	for id := base; id <= latest; id++ {
		h := &e.history[id%maxWindowSize]
		gfMulAdd(parity, h.block[:h.length], coefficient(base, uint8(size), index, int(id-base)))
	}
	e.lastBase, e.lastIndex, e.hasLast = base, index, true
	_ = c.repairPath(primary).sendRepair(base, uint8(size), index, parity)
	e.unprotected = 0
}

func (e *encoder) indexFor(base uint64) uint8 {
	if e.hasLast && e.lastBase == base {
		return e.lastIndex + 1
	}
	return 0
}

const degradedPenalty = int64(1) << 40

func pathScore(p *Path, now int64) int64 {
	srtt := p.srttA.Load()
	if srtt <= 0 {
		srtt = 100_000
	}
	score := srtt + int64(p.lossPPM.Load())/4
	if p.degraded(now) {
		score += degradedPenalty
	}
	return score
}

// selectPrimary picks the lowest-cost validated path with hysteresis, so two
// similar paths do not flap. With no validated path it falls back to the most
// recently alive keyed path instead of dropping traffic.
func (c *Core) selectPrimary() *Path {
	e := &c.enc
	now := time.Now().UnixMicro()
	var best *Path
	var bestScore int64
	for _, p := range c.snapshot() {
		if !p.usable() {
			continue
		}
		if score := pathScore(p, now); best == nil || score < bestScore {
			best, bestScore = p, score
		}
	}
	current := e.primary
	if best != nil && current != nil && current != best && current.usable() {
		if currentScore := pathScore(current, now); currentScore < degradedPenalty && bestScore+2_000 >= currentScore*9/10 {
			best = current
		}
	}
	if best == nil {
		var lastHeard int64
		for _, p := range c.snapshot() {
			if p.closed.Load() || p.keys.Load() == nil || !p.remoteAddress().IsValid() {
				continue
			}
			if heard := p.lastRecv.Load(); best == nil || heard > lastHeard {
				best, lastHeard = p, heard
			}
		}
	}
	e.primary = best
	return best
}

func (c *Core) secondPath(exclude *Path) *Path {
	now := time.Now().UnixMicro()
	var best *Path
	var bestScore int64
	for _, p := range c.snapshot() {
		if p == exclude || !p.usable() {
			continue
		}
		if score := pathScore(p, now); best == nil || score < bestScore {
			best, bestScore = p, score
		}
	}
	return best
}

// repairPath sends repairs over a second, comparably fast path when one
// exists, so a burst outage on the primary cannot take data and its repairs
// down together.
func (c *Core) repairPath(primary *Path) *Path {
	second := c.secondPath(primary)
	if second == nil {
		return primary
	}
	primaryRTT, secondRTT := primary.srttA.Load(), second.srttA.Load()
	if secondRTT <= 0 || primaryRTT <= 0 || secondRTT > primaryRTT+20_000 {
		return primary
	}
	if second.lossPPM.Load() > primary.lossPPM.Load()+20_000 {
		return primary
	}
	return second
}

// ---------------------------------------------------------------- maintenance

func (c *Core) maintenanceLoop() {
	defer c.wg.Done()
	timer := time.NewTimer(10 * time.Millisecond)
	defer timer.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-c.wake:
		case <-timer.C:
		}
		now := time.Now().UnixMicro()
		next := c.maintainSession(now)
		for _, p := range c.snapshot() {
			next = min(next, p.maintain(now))
		}
		delay := max(min(next-now, 250_000), 2_000)
		timer.Reset(time.Duration(delay) * time.Microsecond)
	}
}

func (c *Core) lastAnyRecv() int64 {
	var last int64
	for _, p := range c.snapshot() {
		last = max(last, p.lastRecv.Load())
	}
	return last
}

func (c *Core) anyUsable() bool {
	for _, p := range c.snapshot() {
		if p.usable() {
			return true
		}
	}
	return false
}

// Stats returns a non-blocking snapshot of session counters.
func (c *Core) Stats() Stats {
	return Stats{
		Submitted:      c.statsSubmitted.Load(),
		SentData:       c.statsSentData.Load(),
		SentRepairs:    c.statsSentRepairs.Load(),
		Delivered:      c.statsDelivered.Load(),
		Recovered:      c.statsRecovered.Load(),
		Duplicates:     c.statsDuplicates.Load(),
		StaleDropped:   c.statsStaleDropped.Load(),
		QueueDropped:   c.statsQueueDropped.Load(),
		NoPathDropped:  c.statsNoPathDropped.Load(),
		InvalidPackets: c.statsInvalidPackets.Load(),
		ReplayDropped:  c.statsReplayDropped.Load(),
		Handshakes:     c.statsHandshakes.Load(),
		AddressChanges: c.statsAddressChanges.Load(),
		PathFailures:   c.statsPathFailures.Load(),
		ShapedDropped:  c.statsShaped.Load(),
	}
}

// PathStats returns statistics for one path.
func (c *Core) PathStats(id uint16) (PathStats, error) {
	p := c.findPath(id)
	if p == nil {
		return PathStats{}, ErrPathNotFound
	}
	return p.Stats(), nil
}

// Close stops all workers and closes every attached UDP socket. It is
// idempotent and must not be called from OnData.
func (c *Core) Close() error {
	c.closeOnce.Do(func() {
		c.submissionMu.Lock()
		c.closed.Store(true)
		close(c.done)
		c.submissionMu.Unlock()
		c.pathsMu.Lock()
		for _, p := range c.snapshot() {
			p.closed.Store(true)
			_ = p.conn.Close()
		}
		c.pathsMu.Unlock()
	})
	c.wg.Wait()
	return nil
}
