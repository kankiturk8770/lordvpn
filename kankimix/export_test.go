package kankimix

import (
	"net/netip"
	"sync"
	"time"
)

var fuzzMu sync.Mutex

// processDatagramForTest serialises fuzz input with the live receive loop by
// using a private buffer copy; the receive goroutine never touches it.
func (p *Path) processDatagramForTest(wire []byte, source netip.AddrPort) {
	fuzzMu.Lock()
	defer fuzzMu.Unlock()
	clone := &Path{core: p.core, id: p.id, conn: p.conn, remote: source,
		reorderThreshold: initialReorder, timeThreshold8: initialTimeThresh, rng: 1,
		wireBuf: make([]byte, MaxDatagram+64), plainBuf: make([]byte, MaxDatagram+64),
		rxBuf: make([]byte, MaxDatagram+64), decryptBuf: make([]byte, MaxDatagram+64)}
	clone.keys.Store(p.keys.Load())
	clone.processDatagram(wire, source, time.Now().UnixMicro())
}
