package kankimix

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"math/rand/v2"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ---------------------------------------------------------------- helpers

type testDelivery struct {
	data string
	meta SymbolMeta
}

func testSecret() []byte {
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i*7 + 11)
	}
	return secret
}

func newTestCore(t testing.TB, role Role, mutate func(*Config), onData func([]byte, SymbolMeta)) *Core {
	t.Helper()
	config := Config{
		ConnectionID: 0x1020304050607080,
		Role:         role,
		Secret:       testSecret(),
		WindowSize:   8,
		QueueSize:    512,
		MaxRepairs:   4,
		PathTimeout:  600 * time.Millisecond,
		OnData:       onData,
	}
	if mutate != nil {
		mutate(&config)
	}
	core, err := NewCore(config)
	if err != nil {
		t.Fatalf("NewCore: %v", err)
	}
	t.Cleanup(func() { _ = core.Close() })
	return core
}

func listenUDP(t testing.TB) (*net.UDPConn, netip.AddrPort) {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("ListenUDP: %v", err)
	}
	return conn, conn.LocalAddr().(*net.UDPAddr).AddrPort()
}

func waitFor(t testing.TB, timeout time.Duration, condition func() bool, description string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", description)
}

func collector(buffer int) (chan testDelivery, func([]byte, SymbolMeta)) {
	ch := make(chan testDelivery, buffer)
	return ch, func(data []byte, meta SymbolMeta) {
		select {
		case ch <- testDelivery{data: string(data), meta: meta}:
		default:
		}
	}
}

func receiveTestPacket(t testing.TB, packets <-chan testDelivery) testDelivery {
	t.Helper()
	select {
	case packet := <-packets:
		return packet
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for application data")
		return testDelivery{}
	}
}

func pathsValid(cores []*Core, ids ...uint16) bool {
	for _, c := range cores {
		for _, id := range ids {
			s, err := c.PathStats(id)
			if err != nil || !s.Valid {
				return false
			}
		}
	}
	return true
}

// lossyLink is a UDP relay between a client socket and a server socket with
// programmable loss, burst loss, reordering and rebinding (port change).
type lossyLink struct {
	t          testing.TB
	clientSide *net.UDPConn // faces the client
	serverSide atomic.Pointer[net.UDPConn]
	server     netip.AddrPort
	client     atomic.Value // netip.AddrPort

	mu       sync.Mutex
	rng      *rand.Rand
	lossUp   float64
	lossDown float64
	burst    int // drop this many consecutive packets after a loss
	reorder  float64
	delay    time.Duration
	dropNext int
	captured [][]byte
	capture  bool
	closed   atomic.Bool
	wg       sync.WaitGroup
}

func newLossyLink(t testing.TB, server netip.AddrPort, seed uint64) (*lossyLink, netip.AddrPort) {
	clientSide, address := listenUDP(t)
	serverSide, _ := listenUDP(t)
	l := &lossyLink{t: t, clientSide: clientSide, server: server, rng: rand.New(rand.NewPCG(seed, seed^0x9e37))}
	l.serverSide.Store(serverSide)
	l.wg.Add(2)
	go l.pumpUp()
	go l.pumpDown(serverSide)
	t.Cleanup(l.close)
	return l, address
}

func (l *lossyLink) close() {
	if l.closed.Swap(true) {
		return
	}
	_ = l.clientSide.Close()
	_ = l.serverSide.Load().Close()
	l.wg.Wait()
}

// rebind simulates a carrier NAT assigning a new public port.
func (l *lossyLink) rebind() {
	next, _ := listenUDP(l.t)
	old := l.serverSide.Swap(next)
	l.wg.Add(1)
	go l.pumpDown(next)
	_ = old.Close()
}

func (l *lossyLink) decide(loss float64) (drop bool, hold bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.dropNext > 0 {
		l.dropNext--
		return true, false
	}
	if l.rng.Float64() < loss {
		l.dropNext = l.burst
		return true, false
	}
	return false, l.rng.Float64() < l.reorder
}

func (l *lossyLink) forward(conn *net.UDPConn, data []byte, to netip.AddrPort, hold bool) {
	l.mu.Lock()
	delay := l.delay
	l.mu.Unlock()
	if hold {
		delay += 3 * time.Millisecond
	}
	if delay == 0 {
		_, _ = conn.WriteToUDPAddrPort(data, to)
		return
	}
	packet := append([]byte(nil), data...)
	time.AfterFunc(delay, func() { _, _ = conn.WriteToUDPAddrPort(packet, to) })
}

func (l *lossyLink) pumpUp() {
	defer l.wg.Done()
	buf := make([]byte, 2048)
	for {
		n, from, err := l.clientSide.ReadFromUDPAddrPort(buf)
		if err != nil {
			if l.closed.Load() {
				return
			}
			continue
		}
		l.client.Store(from)
		l.mu.Lock()
		if l.capture {
			l.captured = append(l.captured, append([]byte(nil), buf[:n]...))
		}
		loss := l.lossUp
		l.mu.Unlock()
		drop, hold := l.decide(loss)
		if !drop {
			l.forward(l.serverSide.Load(), buf[:n], l.server, hold)
		}
	}
}

func (l *lossyLink) pumpDown(conn *net.UDPConn) {
	defer l.wg.Done()
	buf := make([]byte, 2048)
	for {
		n, _, err := conn.ReadFromUDPAddrPort(buf)
		if err != nil {
			if l.closed.Load() || l.serverSide.Load() != conn {
				return
			}
			continue
		}
		client, ok := l.client.Load().(netip.AddrPort)
		if !ok {
			continue
		}
		l.mu.Lock()
		loss := l.lossDown
		l.mu.Unlock()
		drop, hold := l.decide(loss)
		if !drop {
			l.forward(l.clientSide, buf[:n], client, hold)
		}
	}
}

func (l *lossyLink) set(f func(l *lossyLink)) {
	l.mu.Lock()
	f(l)
	l.mu.Unlock()
}

// pair builds a client/server session over pathCount lossy links. The server
// learns client addresses (zero AddrPort), exactly as behind carrier NAT.
func pair(t testing.TB, pathCount int, mutate func(*Config), clientRx, serverRx func([]byte, SymbolMeta)) (*Core, *Core, []*lossyLink) {
	t.Helper()
	server := newTestCore(t, RoleServer, mutate, serverRx)
	client := newTestCore(t, RoleClient, mutate, clientRx)
	links := make([]*lossyLink, 0, pathCount)
	for i := 1; i <= pathCount; i++ {
		serverConn, serverAddress := listenUDP(t)
		if err := server.AddPath(uint16(i), serverConn, netip.AddrPort{}); err != nil {
			t.Fatalf("server AddPath: %v", err)
		}
		link, linkAddress := newLossyLink(t, serverAddress, uint64(i)*7919)
		links = append(links, link)
		clientConn, _ := listenUDP(t)
		if err := client.AddPath(uint16(i), clientConn, linkAddress); err != nil {
			t.Fatalf("client AddPath: %v", err)
		}
	}
	return client, server, links
}

func allIDs(n int) []uint16 {
	ids := make([]uint16, n)
	for i := range ids {
		ids[i] = uint16(i + 1)
	}
	return ids
}

// ---------------------------------------------------------------- GF(256)

func TestGF256Field(t *testing.T) {
	for a := 1; a < 256; a++ {
		if gfMul(byte(a), gfInv(byte(a))) != 1 {
			t.Fatalf("inverse failed for %d", a)
		}
		for b := 0; b < 256; b++ {
			if gfMul(byte(a), byte(b)) != gfMul(byte(b), byte(a)) {
				t.Fatal("multiplication is not commutative")
			}
		}
	}
	for i := 0; i < 1000; i++ {
		if coefficient(uint64(i), 8, uint8(i), i%8) == 0 {
			t.Fatal("zero coefficient")
		}
	}
}

func TestPacketNumberDecoding(t *testing.T) {
	cases := []struct{ largest, want uint64 }{
		{0, 1}, {10, 11}, {10, 5}, {0xffff_fff0, 0x1_0000_0005}, {0x1_0000_0005, 0xffff_fff0}, {0x5_0000_0000, 0x5_0000_0001},
	}
	for _, tc := range cases {
		if got := decodePacketNumber(tc.largest, uint32(tc.want)); got != tc.want {
			t.Fatalf("decode(%#x,%#x) = %#x", tc.largest, uint32(tc.want), got)
		}
	}
}

// ---------------------------------------------------------------- FEC decoder

// fecHarness drives the receiver directly with symbols and repairs generated
// exactly like the scheduler does.
type fecHarness struct {
	core      *Core
	delivered map[uint64]string
	recovered int
	blocks    map[uint64][]byte
	repairs   map[uint64]uint8
}

func newFECHarness(t *testing.T) *fecHarness {
	h := &fecHarness{delivered: map[uint64]string{}, blocks: map[uint64][]byte{}, repairs: map[uint64]uint8{}}
	h.core = newTestCore(t, RoleClient, nil, func(data []byte, meta SymbolMeta) {
		if _, dup := h.delivered[meta.ID]; dup {
			t.Fatalf("symbol %d delivered twice", meta.ID)
		}
		h.delivered[meta.ID] = string(data)
		if meta.Recovered {
			h.recovered++
		}
	})
	h.core.rx.reset(1)
	return h
}

func (h *fecHarness) payload(id uint64) []byte {
	return []byte(fmt.Sprintf("symbol-%03d-%s", id, bytes.Repeat([]byte{byte(id)}, int(id%23))))
}

func (h *fecHarness) block(id uint64) []byte {
	p := h.payload(id)
	b := make([]byte, blockMetaLen+len(p))
	encodeBlockMeta(b, len(p), ClassRealtime, 5000)
	copy(b[blockMetaLen:], p)
	return b
}

func (h *fecHarness) data(id uint64) {
	h.core.rx.handleData(1, 1, uint32(id), ClassRealtime, 5000, h.payload(id), time.Now().UnixMicro(), 0)
}

func (h *fecHarness) repair(base uint64, size int) {
	index := h.repairs[base]
	h.repairs[base] = index + 1
	maximum := 0
	for id := base; id < base+uint64(size); id++ {
		maximum = max(maximum, len(h.block(id)))
	}
	parity := make([]byte, maximum)
	for row := 0; row < size; row++ {
		gfMulAdd(parity, h.block(base+uint64(row)), coefficient(base, uint8(size), index, row))
	}
	h.core.rx.handleRepair(1, 1, uint32(base), uint8(size), index, parity, time.Now().UnixMicro(), 0)
}

func (h *fecHarness) check(t *testing.T, upto uint64) {
	t.Helper()
	for id := uint64(1); id <= upto; id++ {
		got, ok := h.delivered[id]
		if !ok {
			t.Fatalf("symbol %d not delivered (recovered=%d)", id, h.recovered)
		}
		if got != string(h.payload(id)) {
			t.Fatalf("symbol %d corrupted", id)
		}
	}
}

// The original core could only combine repairs with an identical base; a
// burst of two losses protected by one repair per symbol was unrecoverable.
func TestFECBurstAcrossOverlappingWindows(t *testing.T) {
	h := newFECHarness(t)
	const window = 8
	lost := map[uint64]bool{9: true, 10: true}
	for id := uint64(1); id <= 16; id++ {
		if !lost[id] {
			h.data(id)
		}
		if id >= 2 {
			base := uint64(1)
			if id > window {
				base = id - window + 1
			}
			h.repair(base, int(id-base+1)) // one repair per symbol, base slides
		}
	}
	h.check(t, 16)
	if h.recovered != 2 {
		t.Fatalf("recovered %d, want 2", h.recovered)
	}
}

func TestFECLongBurstAndReorderedLateData(t *testing.T) {
	h := newFECHarness(t)
	for id := uint64(1); id <= 20; id++ {
		if id < 11 || id > 14 { // four consecutive losses
			h.data(id)
		}
		if id%2 == 0 {
			base := uint64(1)
			if id > 8 {
				base = id - 7
			}
			h.repair(base, int(id-base+1))
			h.repair(base, int(id-base+1))
		}
	}
	h.check(t, 20)
	// Late duplicates of recovered symbols must be suppressed.
	before := h.core.Stats().Duplicates
	h.data(12)
	if h.core.Stats().Duplicates != before+1 {
		t.Fatal("late duplicate not suppressed")
	}
}

func TestFECRepairBeforeData(t *testing.T) {
	h := newFECHarness(t)
	for id := uint64(1); id <= 4; id++ {
		h.data(id)
	}
	// Repairs for 1..6 arrive before 5 and 6 (reordering); 6 is then lost.
	h.repair(1, 6)
	h.repair(1, 6)
	h.data(5)
	h.check(t, 6)
}

func TestFECRandomizedNeverCorrupts(t *testing.T) {
	rng := rand.New(rand.NewPCG(1, 2))
	for trial := 0; trial < 40; trial++ {
		h := newFECHarness(t)
		const total = 300
		const window = 12
		for id := uint64(1); id <= total; id++ {
			if rng.Float64() > 0.15 {
				h.data(id)
			}
			if rng.Float64() < 0.5 {
				base := uint64(1)
				if id > window {
					base = id - window + 1
				}
				if rng.Float64() > 0.15 {
					h.repair(base, int(id-base+1))
				}
			}
		}
		for id, got := range h.delivered {
			if got != string(h.payload(id)) {
				t.Fatalf("trial %d: symbol %d corrupted", trial, id)
			}
		}
		if len(h.delivered) < total*95/100 {
			t.Fatalf("trial %d: delivered only %d/%d", trial, len(h.delivered), total)
		}
	}
}

// ---------------------------------------------------------------- integration

func TestHandshakeAndBidirectionalExchange(t *testing.T) {
	clientRx, onClient := collector(16)
	serverRx, onServer := collector(16)
	client, server, _ := pair(t, 1, nil, onClient, onServer)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "path validation")

	if err := client.Submit([]byte("client hello"), ClassRealtime, time.Second); err != nil {
		t.Fatal(err)
	}
	got := receiveTestPacket(t, serverRx)
	if got.data != "client hello" || got.meta.Recovered {
		t.Fatalf("server got %+v", got)
	}
	if err := server.Submit([]byte("server hello"), ClassRealtime, time.Second); err != nil {
		t.Fatal(err)
	}
	if got := receiveTestPacket(t, clientRx); got.data != "server hello" {
		t.Fatalf("client got %q", got.data)
	}
	if time.Until(got.meta.Deadline) <= 0 || time.Until(got.meta.Deadline) > time.Second {
		t.Fatalf("receiver-local deadline out of range: %v", time.Until(got.meta.Deadline))
	}
}

// The original core sent one PATH_CHALLENGE and never retried.
func TestHandshakeSurvivesInitialLoss(t *testing.T) {
	server := newTestCore(t, RoleServer, nil, nil)
	client := newTestCore(t, RoleClient, nil, nil)
	serverConn, serverAddress := listenUDP(t)
	if err := server.AddPath(1, serverConn, netip.AddrPort{}); err != nil {
		t.Fatal(err)
	}
	link, linkAddress := newLossyLink(t, serverAddress, 3)
	link.set(func(l *lossyLink) { l.dropNext = 3 }) // first INITs vanish
	clientConn, _ := listenUDP(t)
	if err := client.AddPath(1, clientConn, linkAddress); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation after loss")
}

func TestFECRecoversUnderLossAndReordering(t *testing.T) {
	var delivered, recovered atomic.Int64
	onServer := func(data []byte, meta SymbolMeta) {
		delivered.Add(1)
		if meta.Recovered {
			recovered.Add(1)
		}
	}
	client, server, links := pair(t, 1, nil, nil, onServer)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	links[0].set(func(l *lossyLink) { l.lossUp = 0.08; l.burst = 1; l.reorder = 0.05 })

	const total = 1500
	payload := make([]byte, 120)
	for i := 0; i < total; i++ {
		binary.BigEndian.PutUint32(payload, uint32(i))
		if err := client.Submit(payload, ClassRealtime, 2*time.Second); err != nil {
			t.Fatal(err)
		}
		time.Sleep(time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)
	rate := float64(delivered.Load()) / total
	stats := client.Stats()
	t.Logf("delivered %.1f%% (recovered %d) with ~16%% bursty loss; repairs/data = %d/%d",
		rate*100, recovered.Load(), stats.SentRepairs, stats.SentData)
	if recovered.Load() == 0 || rate < 0.95 {
		t.Fatalf("delivery %.3f recovered %d", rate, recovered.Load())
	}
	if stats.SentRepairs > 2*stats.SentData {
		t.Fatalf("repair overhead unbounded: %d repairs for %d symbols", stats.SentRepairs, stats.SentData)
	}
}

// Pure reordering must not be treated as loss (the original core counted every
// out-of-order arrival as a loss and inflated FEC traffic).
func TestReorderingIsNotLoss(t *testing.T) {
	client, server, links := pair(t, 1, nil, nil, nil)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	links[0].set(func(l *lossyLink) { l.reorder = 0.2 })
	for i := 0; i < 600; i++ {
		_ = client.Submit([]byte("reordered traffic"), ClassRealtime, time.Second)
		time.Sleep(500 * time.Microsecond)
	}
	time.Sleep(200 * time.Millisecond)
	ps, _ := client.PathStats(1)
	t.Logf("loss=%d‰ lost=%d spurious=%d repairs=%d", ps.LossPermille, ps.LostPackets, ps.SpuriousLosses, client.Stats().SentRepairs)
	if ps.LossPermille > 20 {
		t.Fatalf("reordering inflated loss estimate to %d‰", ps.LossPermille)
	}
}

// Server address learning plus carrier NAT rebinding mid-session.
func TestNATRebinding(t *testing.T) {
	serverRx, onServer := collector(64)
	clientRx, onClient := collector(64)
	client, server, links := pair(t, 1, nil, onClient, onServer)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")

	links[0].rebind()
	for i := 0; i < 20; i++ {
		_ = client.Submit([]byte("after rebind"), ClassRealtime, time.Second)
		time.Sleep(5 * time.Millisecond)
	}
	receiveTestPacket(t, serverRx)
	waitFor(t, 3*time.Second, func() bool { return server.Stats().AddressChanges >= 1 }, "address change")
	if err := server.Submit([]byte("reply to new port"), ClassRealtime, time.Second); err != nil {
		t.Fatal(err)
	}
	if got := receiveTestPacket(t, clientRx); got.data != "reply to new port" {
		t.Fatalf("got %q", got.data)
	}
}

func TestMultipathDuplicationAndFailover(t *testing.T) {
	serverRx, onServer := collector(256)
	client, server, links := pair(t, 2, nil, nil, onServer)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1, 2) }, "two-path validation")

	if err := client.Submit([]byte("critical"), ClassCritical, time.Second); err != nil {
		t.Fatal(err)
	}
	if got := receiveTestPacket(t, serverRx); got.data != "critical" {
		t.Fatalf("got %q", got.data)
	}
	waitFor(t, time.Second, func() bool { return server.Stats().Duplicates >= 1 }, "duplicate suppression")

	// Blackhole whichever path is primary; traffic must fail over.
	links[0].set(func(l *lossyLink) { l.lossUp, l.lossDown = 1, 1 })
	links[1].set(func(l *lossyLink) { l.lossUp, l.lossDown = 0, 0 })
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		_ = client.Submit([]byte("failover"), ClassRealtime, time.Second)
		time.Sleep(10 * time.Millisecond)
		s, _ := client.PathStats(1)
		if !s.Valid {
			break
		}
	}
	drain := func() {
		for {
			select {
			case <-serverRx:
			default:
				return
			}
		}
	}
	drain()
	_ = client.Submit([]byte("on surviving path"), ClassRealtime, time.Second)
	got := receiveTestPacket(t, serverRx)
	if got.data != "on surviving path" || got.meta.PathID != 2 {
		t.Fatalf("got %q via path %d", got.data, got.meta.PathID)
	}
	if client.Stats().PathFailures == 0 {
		t.Fatal("dead path not detected")
	}
}

// A client restart reuses the same PSK and ConnectionID. Fresh session keys
// must be negotiated (no nonce reuse) and the server must follow.
func TestClientRestartRenegotiates(t *testing.T) {
	serverRx, onServer := collector(16)
	server := newTestCore(t, RoleServer, nil, onServer)
	serverConn, serverAddress := listenUDP(t)
	if err := server.AddPath(1, serverConn, netip.AddrPort{}); err != nil {
		t.Fatal(err)
	}
	var firstKey [32]byte
	for run := 0; run < 2; run++ {
		client, err := NewCore(Config{ConnectionID: 0x1020304050607080, Role: RoleClient, Secret: testSecret()})
		if err != nil {
			t.Fatal(err)
		}
		conn, _ := listenUDP(t)
		if err := client.AddPath(1, conn, serverAddress); err != nil {
			t.Fatal(err)
		}
		waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client}, 1) }, "client validation")
		client.sessMu.Lock()
		key := client.sessionKey
		client.sessMu.Unlock()
		if run == 0 {
			firstKey = key
		} else if key == firstKey {
			t.Fatal("session key reused across restarts")
		}
		msg := fmt.Sprintf("run %d", run)
		_ = client.Submit([]byte(msg), ClassRealtime, time.Second)
		if got := receiveTestPacket(t, serverRx); got.data != msg {
			t.Fatalf("got %q want %q", got.data, msg)
		}
		_ = client.Close()
	}
	if server.Stats().Handshakes != 2 {
		t.Fatalf("server handshakes = %d", server.Stats().Handshakes)
	}
}

func TestWireIsObfuscatedAndReplayProtected(t *testing.T) {
	_, onServer := collector(64)
	client, server, links := pair(t, 1, func(c *Config) { c.Padding = 16 }, nil, onServer)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	links[0].set(func(l *lossyLink) { l.capture = true })
	for i := 0; i < 200; i++ {
		_ = client.Submit([]byte("identical game packet"), ClassRealtime, time.Second)
	}
	waitFor(t, 2*time.Second, func() bool { return server.Stats().Delivered >= 200 }, "delivery")
	links[0].set(func(l *lossyLink) { l.capture = false })

	links[0].mu.Lock()
	captured := links[0].captured
	links[0].mu.Unlock()
	if len(captured) < 100 {
		t.Fatalf("captured %d datagrams", len(captured))
	}
	cid := u64bytes(0x1020304050607080)
	var counts [6][256]int
	lengths := map[int]bool{}
	for _, d := range captured {
		if bytes.Contains(d, cid) || bytes.Contains(d, []byte("identical")) {
			t.Fatal("cleartext identifier or payload on the wire")
		}
		for i := 0; i < 6; i++ {
			counts[i][d[i]]++
		}
		lengths[len(d)] = true
	}
	for i := 0; i < 6; i++ {
		for value, n := range counts[i] {
			if n > len(captured)/8 {
				t.Fatalf("header byte %d takes value %#x in %d/%d packets", i, value, n, len(captured))
			}
		}
	}
	if len(lengths) < 4 {
		t.Fatalf("padding produced only %d distinct lengths", len(lengths))
	}

	// Replay every captured datagram straight at the server.
	before := server.Stats().Delivered
	for _, d := range captured {
		_, _ = links[0].serverSide.Load().WriteToUDPAddrPort(d, links[0].server)
	}
	time.Sleep(100 * time.Millisecond)
	if server.Stats().Delivered != before {
		t.Fatal("replayed packets were delivered")
	}
	if server.Stats().ReplayDropped == 0 {
		t.Fatal("replays not counted")
	}
}

func TestDeadlineEviction(t *testing.T) {
	client, server, _ := pair(t, 1, nil, nil, nil)
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	if err := client.Submit([]byte("expired"), ClassRealtime, time.Nanosecond); err != nil {
		t.Fatal(err)
	}
	waitFor(t, time.Second, func() bool { return client.Stats().StaleDropped == 1 }, "stale eviction")
}

func TestSubmissionValidation(t *testing.T) {
	core := newTestCore(t, RoleClient, nil, nil)
	if err := core.Submit(nil, ClassRealtime, time.Second); err != ErrEmptyPayload {
		t.Fatalf("empty: %v", err)
	}
	if err := core.Submit(make([]byte, core.PayloadLimit()+1), ClassRealtime, time.Second); err != ErrPayloadTooLarge {
		t.Fatalf("large: %v", err)
	}
	if err := core.Submit([]byte{1}, ClassRealtime, 0); err != ErrInvalidDeadline {
		t.Fatalf("ttl: %v", err)
	}
	if err := core.Submit([]byte{1}, ClassRealtime, MaxTTL+time.Millisecond); err != ErrInvalidDeadline {
		t.Fatalf("ttl max: %v", err)
	}
	if err := core.Submit([]byte{1}, TrafficClass(9), time.Second); err == nil {
		t.Fatal("invalid class accepted")
	}
	if _, err := NewCore(Config{ConnectionID: 1, Role: RoleClient, Secret: make([]byte, 31)}); err == nil {
		t.Fatal("short secret accepted")
	}
}

func TestWireOverheadAndMTU(t *testing.T) {
	if DataOverhead != 28 {
		t.Fatalf("data overhead %d, want 28", DataOverhead)
	}
	core := newTestCore(t, RoleClient, nil, nil)
	if core.PayloadLimit()+repairOverhead > DefaultDatagram {
		t.Fatal("largest repair would exceed the default datagram")
	}
	if MaxPayload+repairOverhead > MaxDatagram {
		t.Fatal("MaxPayload inconsistent")
	}
}

// ---------------------------------------------------------------- allocation

func TestDatapathZeroAllocation(t *testing.T) {
	client, server, _ := pair(t, 1, nil, nil, func([]byte, SymbolMeta) {})
	waitFor(t, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	p := client.findPath(1)
	payload := make([]byte, 200)
	allocs := testing.AllocsPerRun(500, func() {
		_ = p.sendData(1, ClassRealtime, 100, payload)
	})
	if allocs != 0 {
		t.Fatalf("sendData allocates %.1f times", allocs)
	}
	allocs = testing.AllocsPerRun(500, func() {
		_ = client.Submit(payload, ClassRealtime, time.Second)
	})
	if allocs != 0 {
		t.Fatalf("Submit allocates %.1f times", allocs)
	}
	h := server.rx
	gen := server.sessionGen.Load()
	var id uint32 = 1 << 20
	allocs = testing.AllocsPerRun(500, func() {
		id++
		h.handleData(gen, 1, id, ClassRealtime, 1000, payload, time.Now().UnixMicro(), 0)
	})
	if allocs != 0 {
		t.Fatalf("receiver allocates %.1f times", allocs)
	}
}

func BenchmarkSubmit(b *testing.B) {
	core := newTestCore(b, RoleClient, func(c *Config) { c.QueueSize = 4096 }, nil)
	payload := make([]byte, 128)
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = core.Submit(payload, ClassRealtime, time.Second)
	}
}

func BenchmarkGFMulAdd1KiB(b *testing.B) {
	dst := make([]byte, 1024)
	src := make([]byte, 1024)
	b.SetBytes(1024)
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		gfMulAdd(dst, src, byte(i%254+2))
	}
}

// FuzzDatagram feeds arbitrary bytes into an established path: nothing may
// panic, deliver data, or corrupt session state.
func FuzzDatagram(f *testing.F) {
	f.Add(make([]byte, 40))
	f.Add(append([]byte{0x80}, make([]byte, 60)...))
	client, server, _ := pair(f, 1, nil, nil, nil)
	waitFor(f, 3*time.Second, func() bool { return pathsValid([]*Core{client, server}, 1) }, "validation")
	p := server.findPath(1)
	source := p.remoteAddress()
	f.Fuzz(func(t *testing.T, data []byte) {
		buf := append([]byte(nil), data...)
		before := server.Stats().Delivered
		p.processDatagramForTest(buf, source)
		if server.Stats().Delivered != before {
			t.Fatal("forged datagram delivered")
		}
	})
}

// FuzzDecoder drives the FEC decoder with arbitrary authenticated-looking
// frames (a malicious or buggy peer) to prove it never panics or corrupts.
func FuzzDecoder(f *testing.F) {
	f.Add([]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
	core := newTestCore(f, RoleClient, nil, func([]byte, SymbolMeta) {})
	f.Fuzz(func(t *testing.T, script []byte) {
		core.rx.reset(7)
		now := time.Now().UnixMicro()
		for len(script) >= 6 {
			op, a, b, c := script[0], binary.BigEndian.Uint16(script[1:3]), script[3], script[4]
			n := int(script[5]) % 40
			script = script[6:]
			body := script[:min(n, len(script))]
			script = script[len(body):]
			if op&1 == 0 {
				if len(body) > 0 {
					core.rx.handleData(7, 1, uint32(a), TrafficClass(b%4), 1000, body, now, 0)
				}
			} else {
				core.rx.handleRepair(7, 1, uint32(a), b, c, body, now, 0)
			}
		}
	})
}
