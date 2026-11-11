package kankimix

import (
	"encoding/binary"
	"sync"
	"time"
)

// Receiver-side sliding-window RLC decoder.
//
// Every repair symbol is an equation over the source symbols of its window
// [base, base+size). On arrival, all already-known symbols are substituted out
// immediately, so a stored equation only references *unknown* symbols. The
// equations are kept in fully reduced row-echelon form across ALL windows
// (an on-the-fly Gauss-Jordan decoder), independent of base or size. That means
// overlapping windows with different bases combine naturally: two consecutive
// losses covered by repairs with base=3 and base=4 are solved jointly.
//
//   - New equation: eliminate existing pivots, choose a new pivot, normalise,
//     back-substitute into all other rows. Redundant equations are discarded.
//   - Source symbol arrives late (reordering): it is substituted into every row
//     that references it; a row that loses its pivot is re-pivoted.
//   - A row whose only nonzero coefficient is its pivot is a recovered symbol.
//
// Repairs whose windows contain no unknown symbols carry no information and are
// discarded in O(window) without being stored, so lossless operation costs
// almost nothing. All storage is preallocated.

const (
	symbolRing  = 256 // receive history ring (must exceed maxWindowSize*3)
	horizon     = symbolRing - maxWindowSize - 1
	maxRows     = 64
	maxCols     = 64
	noRow       = -1
	maxLookback = maxWindowSize
)

type rxSymbol struct {
	id        uint64
	present   bool
	delivered bool
	length    int
	arrival   int64
	block     []byte
}

type equation struct {
	used   bool
	pivot  int
	length int
	coef   [maxCols]byte
	vec    []byte
}

type receiver struct {
	core *Core
	mu   sync.Mutex
	gen  uint64

	symbols   [symbolRing]rxSymbol
	maxSymbol uint64

	rows    [maxRows]equation
	colID   [maxCols]uint64
	colUsed [maxCols]bool
	colRow  [maxCols]int

	scratch    equation
	building   bool
	blockBuf   []byte
	newCols    [maxWindowSize]uint64
	solvedRows [maxRows]int
}

func newReceiver(core *Core) *receiver {
	r := &receiver{core: core}
	for i := range r.symbols {
		r.symbols[i].block = make([]byte, maxBlockLen)
	}
	for i := range r.rows {
		r.rows[i].vec = make([]byte, maxBlockLen)
	}
	r.scratch.vec = make([]byte, maxBlockLen)
	r.blockBuf = make([]byte, maxBlockLen)
	for i := range r.colRow {
		r.colRow[i] = noRow
	}
	return r
}

// reset clears all decoder state for a new session generation.
func (r *receiver) reset(gen uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gen = gen
	r.maxSymbol = 0
	for i := range r.symbols {
		s := &r.symbols[i]
		s.id, s.present, s.delivered, s.length, s.arrival = 0, false, false, 0, 0
	}
	for i := range r.rows {
		r.dropRow(i)
	}
	for i := range r.colUsed {
		r.colUsed[i] = false
		r.colRow[i] = noRow
	}
}

func encodeBlockMeta(dst []byte, payloadLength int, class TrafficClass, ttlMillis uint16) {
	binary.BigEndian.PutUint16(dst[0:2], uint16(payloadLength))
	dst[2] = byte(class)
	binary.BigEndian.PutUint16(dst[3:5], ttlMillis)
}

func (r *receiver) lowLimit() uint64 {
	if r.maxSymbol > horizon {
		return r.maxSymbol - horizon
	}
	return 1
}

func (r *receiver) known(id uint64) *rxSymbol {
	s := &r.symbols[id%symbolRing]
	if s.present && s.id == id {
		return s
	}
	return nil
}

// decodeSymbolID expands a 32-bit wire symbol ID.
func (r *receiver) decodeSymbolID(truncated uint32) uint64 {
	return decodePacketNumber(r.maxSymbol, truncated)
}

func (r *receiver) store(id uint64, block []byte, arrival int64) *rxSymbol {
	s := &r.symbols[id%symbolRing]
	s.id = id
	s.present = true
	s.delivered = false
	s.length = len(block)
	s.arrival = arrival
	copy(s.block, block)
	return s
}

func (r *receiver) advance(id uint64) {
	if id <= r.maxSymbol {
		return
	}
	r.maxSymbol = id
	low := r.lowLimit()
	for c := range r.colUsed {
		if r.colUsed[c] && r.colID[c] < low {
			r.expireColumn(c)
		}
	}
}

// handleData processes an original source symbol. It never waits for anything:
// the symbol is delivered immediately and only then fed to the decoder.
func (r *receiver) handleData(gen uint64, pathID uint16, truncatedID uint32, class TrafficClass, ttlMillis uint16, payload []byte, now, owd int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if gen != r.gen {
		return
	}
	id := r.decodeSymbolID(truncatedID)
	if id == 0 || len(payload) == 0 || len(payload) > MaxPayload || class < ClassRealtime || class > ClassBackground {
		r.core.statsInvalidPackets.Add(1)
		return
	}
	if id < r.lowLimit() {
		r.core.statsStaleDropped.Add(1)
		return
	}
	if r.known(id) != nil {
		r.core.statsDuplicates.Add(1)
		return
	}
	block := r.blockBuf[:blockMetaLen+len(payload)]
	encodeBlockMeta(block, len(payload), class, ttlMillis)
	copy(block[blockMetaLen:], payload)
	symbol := r.store(id, block, now)
	r.advance(id)

	deadline := now + int64(ttlMillis)*1000 - owd
	r.deliver(symbol, pathID, class, deadline, payload, false, now)
	r.substituteKnown(id, symbol.block[:symbol.length])
	r.collectSolved(pathID, now, owd)
}

// handleRepair adds one repair equation and attempts recovery.
func (r *receiver) handleRepair(gen uint64, pathID uint16, truncatedBase uint32, size, index uint8, parity []byte, now, owd int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if gen != r.gen {
		return
	}
	if size == 0 || size > maxWindowSize || len(parity) < blockMetaLen+1 || len(parity) > maxBlockLen {
		r.core.statsInvalidPackets.Add(1)
		return
	}
	base := r.decodeSymbolID(truncatedBase)
	if base == 0 {
		r.core.statsInvalidPackets.Add(1)
		return
	}
	high := base + uint64(size) - 1
	if base < r.lowLimit() {
		r.core.statsStaleDropped.Add(1)
		return
	}
	if high > r.maxSymbol+maxWindowSize {
		r.core.statsInvalidPackets.Add(1)
		return
	}

	// Pass 1: count unknowns and the columns they would need.
	needed := 0
	unknowns := 0
	for row := 0; row < int(size); row++ {
		id := base + uint64(row)
		if s := r.known(id); s != nil {
			if s.length > len(parity) {
				r.core.statsInvalidPackets.Add(1)
				return
			}
			continue
		}
		unknowns++
		if r.findColumn(id) < 0 {
			r.newCols[needed] = id
			needed++
		}
	}
	if unknowns == 0 {
		return // carries no information
	}
	if needed > r.freeColumns() && !r.evictForColumns(needed) {
		return
	}

	// Pass 2: build the residual equation over unknown columns only.
	eq := &r.scratch
	r.building = true
	clear(eq.coef[:])
	eq.length = len(parity)
	copy(eq.vec, parity)
	for row := 0; row < int(size); row++ {
		id := base + uint64(row)
		c := coefficient(base, size, index, row)
		if s := r.known(id); s != nil {
			gfMulAdd(eq.vec, s.block[:s.length], c)
			continue
		}
		col := r.findColumn(id)
		if col < 0 {
			col = r.allocColumn(id)
		}
		eq.coef[col] ^= c
	}
	r.insert(eq)
	r.building = false
	r.gcColumns()
	r.collectSolved(pathID, now, owd)
}

func (r *receiver) findColumn(id uint64) int {
	for c := range r.colUsed {
		if r.colUsed[c] && r.colID[c] == id {
			return c
		}
	}
	return -1
}

func (r *receiver) freeColumns() int {
	n := 0
	for c := range r.colUsed {
		if !r.colUsed[c] {
			n++
		}
	}
	return n
}

func (r *receiver) allocColumn(id uint64) int {
	for c := range r.colUsed {
		if !r.colUsed[c] {
			r.colUsed[c] = true
			r.colID[c] = id
			r.colRow[c] = noRow
			return c
		}
	}
	return -1
}

// evictForColumns frees the oldest unknown columns (and the equations that
// reference them) until n columns are available.
func (r *receiver) evictForColumns(n int) bool {
	for r.freeColumns() < n {
		oldest := -1
		for c := range r.colUsed {
			if r.colUsed[c] && (oldest < 0 || r.colID[c] < r.colID[oldest]) {
				oldest = c
			}
		}
		if oldest < 0 {
			return false
		}
		r.expireColumn(oldest)
	}
	return true
}

// expireColumn removes an unknown that can no longer be useful together with
// every equation referencing it.
func (r *receiver) expireColumn(c int) {
	for i := range r.rows {
		if r.rows[i].used && r.rows[i].coef[c] != 0 {
			r.dropRow(i)
		}
	}
	r.releaseColumn(c)
}

func (r *receiver) releaseColumn(c int) {
	r.colUsed[c] = false
	r.colRow[c] = noRow
}

func (r *receiver) dropRow(i int) {
	row := &r.rows[i]
	if !row.used {
		return
	}
	if row.pivot >= 0 && r.colRow[row.pivot] == i {
		r.colRow[row.pivot] = noRow
	}
	clear(row.vec[:row.length])
	clear(row.coef[:])
	row.length = 0
	row.used = false
	row.pivot = noRow
	r.gcColumns()
}

// gcColumns releases unknown columns that no equation references anymore.
func (r *receiver) gcColumns() {
	for c := range r.colUsed {
		if !r.colUsed[c] {
			continue
		}
		referenced := r.building && r.scratch.coef[c] != 0
		for i := 0; i < maxRows && !referenced; i++ {
			referenced = r.rows[i].used && r.rows[i].coef[c] != 0
		}
		if !referenced {
			r.releaseColumn(c)
		}
	}
}

// rowAXPY computes dst += f*src for coefficients and vectors.
func rowAXPY(dst, src *equation, f byte) {
	if f == 0 {
		return
	}
	mulRow := &gfMulTable[f]
	for c := 0; c < maxCols; c++ {
		if v := src.coef[c]; v != 0 {
			dst.coef[c] ^= mulRow[v]
		}
	}
	gfMulAdd(dst.vec, src.vec[:src.length], f)
	if src.length > dst.length {
		dst.length = src.length
	}
}

func normalize(eq *equation, pivot int) {
	inv := gfInv(eq.coef[pivot])
	if inv == 1 {
		return
	}
	mulRow := &gfMulTable[inv]
	for c := 0; c < maxCols; c++ {
		eq.coef[c] = mulRow[eq.coef[c]]
	}
	gfScale(eq.vec[:eq.length], inv)
}

func firstNonzero(eq *equation) int {
	for c := 0; c < maxCols; c++ {
		if eq.coef[c] != 0 {
			return c
		}
	}
	return -1
}

// insert adds eq (in scratch storage) to the reduced system.
func (r *receiver) insert(eq *equation) {
	for i := range r.rows {
		row := &r.rows[i]
		if row.used {
			rowAXPY(eq, row, eq.coef[row.pivot])
		}
	}
	pivot := firstNonzero(eq)
	if pivot < 0 {
		clear(eq.vec[:eq.length])
		eq.length = 0
		return // linearly dependent on what we already know
	}
	normalize(eq, pivot)
	for i := range r.rows {
		row := &r.rows[i]
		if row.used {
			rowAXPY(row, eq, row.coef[pivot])
		}
	}
	slot := r.freeRow()
	row := &r.rows[slot]
	row.vec, eq.vec = eq.vec, row.vec // buffer swap: the evicted vector is zero
	row.coef = eq.coef
	row.length = eq.length
	row.pivot = pivot
	row.used = true
	r.colRow[pivot] = slot
	eq.length = 0
}

func (r *receiver) freeRow() int {
	oldest := -1
	for i := range r.rows {
		if !r.rows[i].used {
			return i
		}
		if oldest < 0 || r.colID[r.rows[i].pivot] < r.colID[r.rows[oldest].pivot] {
			oldest = i
		}
	}
	r.dropRow(oldest)
	return oldest
}

// substituteKnown removes a now-known symbol from every stored equation.
func (r *receiver) substituteKnown(id uint64, block []byte) {
	c := r.findColumn(id)
	if c < 0 {
		return
	}
	pivotRow := r.colRow[c]
	for i := range r.rows {
		row := &r.rows[i]
		if !row.used {
			continue
		}
		if f := row.coef[c]; f != 0 {
			gfMulAdd(row.vec, block, f)
			if len(block) > row.length {
				row.length = len(block)
			}
			row.coef[c] = 0
		}
	}
	r.releaseColumn(c)
	if pivotRow == noRow {
		return
	}
	// The pivot row lost its pivot: re-pivot it to keep reduced form.
	row := &r.rows[pivotRow]
	row.pivot = noRow
	newPivot := firstNonzero(row)
	if newPivot < 0 {
		r.dropRow(pivotRow)
		return
	}
	normalize(row, newPivot)
	row.pivot = newPivot
	r.colRow[newPivot] = pivotRow
	for i := range r.rows {
		other := &r.rows[i]
		if i != pivotRow && other.used {
			rowAXPY(other, row, other.coef[newPivot])
		}
	}
}

// collectSolved delivers every equation reduced to a single unknown.
func (r *receiver) collectSolved(pathID uint16, now, owd int64) {
	solved := 0
	for i := range r.rows {
		row := &r.rows[i]
		if !row.used {
			continue
		}
		single := true
		for c := 0; c < maxCols; c++ {
			if c != row.pivot && row.coef[c] != 0 {
				single = false
				break
			}
		}
		if single {
			r.solvedRows[solved] = i
			solved++
		}
	}
	for k := 0; k < solved; k++ {
		i := r.solvedRows[k]
		row := &r.rows[i]
		if !row.used {
			continue // dropped by horizon expiry triggered by an earlier recovery
		}
		c := row.pivot
		id := r.colID[c]
		r.recover(id, row.vec[:row.length], pathID, now, owd)
		r.dropRow(i)
	}
}

func (r *receiver) recover(id uint64, block []byte, pathID uint16, now, owd int64) {
	if len(block) < blockMetaLen {
		r.core.statsInvalidPackets.Add(1)
		return
	}
	payloadLength := int(binary.BigEndian.Uint16(block[0:2]))
	class := TrafficClass(block[2])
	ttlMillis := binary.BigEndian.Uint16(block[3:5])
	if payloadLength == 0 || blockMetaLen+payloadLength > len(block) || class < ClassRealtime || class > ClassBackground {
		r.core.statsInvalidPackets.Add(1)
		return
	}
	block = block[:blockMetaLen+payloadLength]
	if r.known(id) != nil {
		return
	}
	// The original send instant is bounded by the arrival of the next known
	// later symbol (or now), which keeps the deadline clock-skew free.
	arrival := now
	for next := id + 1; next <= id+maxLookback && next <= r.maxSymbol; next++ {
		if s := r.known(next); s != nil {
			arrival = s.arrival
			break
		}
	}
	symbol := r.store(id, block, arrival)
	r.advance(id)
	deadline := arrival + int64(ttlMillis)*1000 - owd
	r.deliver(symbol, pathID, class, deadline, symbol.block[blockMetaLen:symbol.length], true, now)
}

func (r *receiver) deliver(symbol *rxSymbol, pathID uint16, class TrafficClass, deadline int64, payload []byte, recovered bool, now int64) {
	if symbol.delivered {
		return
	}
	symbol.delivered = true
	if now >= deadline {
		r.core.statsStaleDropped.Add(1)
		return
	}
	r.core.statsDelivered.Add(1)
	if recovered {
		r.core.statsRecovered.Add(1)
	}
	if r.core.config.OnData != nil {
		r.core.config.OnData(payload, SymbolMeta{
			ID:        symbol.id,
			PathID:    pathID,
			Class:     class,
			Deadline:  time.UnixMicro(deadline),
			Recovered: recovered,
		})
	}
}
