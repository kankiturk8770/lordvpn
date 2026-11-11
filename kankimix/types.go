package kankimix

import (
	"errors"
	"net/netip"
	"time"
)

// Wire and engine limits.
const (
	// MaxDatagram is the largest outer UDP payload the core will ever emit
	// (1500-byte Ethernet MTU minus IPv4 and UDP headers).
	MaxDatagram = 1472
	// DefaultDatagram keeps every datagram inside the IPv6 minimum MTU of 1280
	// bytes (minus IPv6 and UDP headers), so packets are never IP-fragmented on
	// mobile carriers, tunnels or PPPoE links.
	DefaultDatagram = 1232
	// MinDatagram is the smallest configurable datagram limit.
	MinDatagram = 256

	tagLen         = 16
	shortHeaderLen = 5  // masked: form byte + 32-bit truncated packet number
	longHeaderLen  = 13 // masked form byte + 96-bit random nonce
	dataBodyLen    = 7  // kind | symbol(4) | ttl(2)
	repairBodyLen  = 7  // kind | base(4) | size | index
	ackBodyLen     = 15 // kind | largest(4) | delay(2) | bitmap(8)
	probeBodyLen   = 9  // kind | token(8)
	blockMetaLen   = 5  // length(2) | class | ttl(2)

	// DataOverhead is the number of bytes added to every application payload
	// sent as a DATA packet (excluding IP/UDP headers).
	DataOverhead   = shortHeaderLen + dataBodyLen + tagLen
	repairOverhead = shortHeaderLen + repairBodyLen + blockMetaLen + tagLen

	// MaxPayload is the absolute largest symbol accepted when MaxDatagram is
	// configured to its maximum. Use Core.PayloadLimit for the configured limit.
	MaxPayload  = MaxDatagram - repairOverhead
	maxBlockLen = MaxPayload + blockMetaLen

	// MaxTTL is the largest deadline that can be carried on the wire.
	MaxTTL = 65535 * time.Millisecond

	maxWindowSize = 64
	maxPaths      = 16
)

// Plaintext frame kinds (low nibble of the first encrypted byte).
const (
	kindData uint8 = iota + 1
	kindRepair
	kindACK
	kindChallenge
	kindResponse
	kindInit
	kindAccept

	kindMask   = 0x0f
	classShift = 4
	classMask  = 0x30
	flagPadded = 0x80
	formLong   = 0x80
)

// Role determines directional key labels and handshake behavior. Peers must
// use opposite roles.
type Role uint8

const (
	RoleClient Role = iota + 1
	RoleServer
)

// TrafficClass controls scheduling and redundancy policy.
type TrafficClass uint8

const (
	// ClassRealtime is loss-adaptive FEC protected game/voice traffic.
	ClassRealtime TrafficClass = iota + 1
	// ClassCritical always receives FEC and is duplicated on a second path.
	ClassCritical
	// ClassBackground receives FEC only on very lossy, uncongested paths.
	ClassBackground
)

// SymbolMeta describes a delivered application symbol. Data passed to OnData is
// valid only for the duration of the callback and must be copied to retain it.
type SymbolMeta struct {
	ID        uint64
	PathID    uint16
	Class     TrafficClass
	Deadline  time.Time // receiver-local deadline (immune to peer clock skew)
	Recovered bool
}

// Config configures one session. Secret must contain at least 32 bytes of
// high-entropy keying material and must be identical at both peers.
type Config struct {
	ConnectionID uint64
	Role         Role
	Secret       []byte

	// WindowSize is the sliding FEC coding window (2..64, default 16).
	WindowSize int
	// QueueSize bounds the submission queue (default 256).
	QueueSize int
	// MaxRepairs caps the number of repair symbols per source symbol (1..4,
	// default 2). The effective rate is loss- and congestion-adaptive.
	MaxRepairs int
	// MaxDatagram bounds every emitted UDP payload (default DefaultDatagram).
	MaxDatagram int
	// Padding adds 0..Padding random bytes to every packet to blur the length
	// distribution (0 disables, max 255).
	Padding int
	// KeepAlive is the idle interval after which a path is probed to hold NAT
	// bindings open (default 10s).
	KeepAlive time.Duration
	// PathTimeout is how long a path may stay silent while we transmit before
	// it is considered failed and excluded from scheduling (default 1500ms).
	PathTimeout time.Duration

	OnData func(data []byte, meta SymbolMeta)
}

// Stats contains monotonic session counters.
type Stats struct {
	Submitted      uint64
	SentData       uint64
	SentRepairs    uint64
	Delivered      uint64
	Recovered      uint64
	Duplicates     uint64
	StaleDropped   uint64
	QueueDropped   uint64
	NoPathDropped  uint64
	InvalidPackets uint64
	ReplayDropped  uint64
	Handshakes     uint64
	AddressChanges uint64
	PathFailures   uint64
}

// PathStats contains per-path counters and estimates.
type PathStats struct {
	SentPackets      uint64
	ReceivedPackets  uint64
	SendErrors       uint64
	DecodeErrors     uint64
	ACKsSent         uint64
	LostPackets      uint64
	SpuriousLosses   uint64
	RTTMicros        int64
	MinRTTMicros     int64
	QueueDelayMicros int64
	LossPermille     uint32
	Valid            bool
	Remote           netip.AddrPort
}

var (
	ErrClosed          = errors.New("kankimix: core is closed")
	ErrQueueFull       = errors.New("kankimix: submission queue is full")
	ErrPayloadTooLarge = errors.New("kankimix: payload exceeds the configured payload limit")
	ErrEmptyPayload    = errors.New("kankimix: payload is empty")
	ErrInvalidDeadline = errors.New("kankimix: TTL must be positive and at most MaxTTL")
	ErrPathExists      = errors.New("kankimix: path ID already exists")
	ErrPathNotFound    = errors.New("kankimix: path ID does not exist")
	ErrTooManyPaths    = errors.New("kankimix: too many paths")
	ErrNoRemote        = errors.New("kankimix: path has no remote address yet")
	ErrNoSession       = errors.New("kankimix: session keys are not established")
)

func normalizeConfig(in Config) (Config, error) {
	if in.ConnectionID == 0 {
		return Config{}, errors.New("kankimix: ConnectionID must be nonzero")
	}
	if in.Role != RoleClient && in.Role != RoleServer {
		return Config{}, errors.New("kankimix: invalid role")
	}
	if len(in.Secret) < 32 {
		return Config{}, errors.New("kankimix: Secret must contain at least 32 bytes")
	}
	if in.WindowSize == 0 {
		in.WindowSize = 16
	}
	if in.WindowSize < 2 || in.WindowSize > maxWindowSize {
		return Config{}, errors.New("kankimix: WindowSize must be between 2 and 64")
	}
	if in.QueueSize == 0 {
		in.QueueSize = 256
	}
	if in.QueueSize < 1 || in.QueueSize > 65536 {
		return Config{}, errors.New("kankimix: QueueSize must be between 1 and 65536")
	}
	if in.MaxRepairs == 0 {
		in.MaxRepairs = 2
	}
	if in.MaxRepairs < 1 || in.MaxRepairs > 4 {
		return Config{}, errors.New("kankimix: MaxRepairs must be between 1 and 4")
	}
	if in.MaxDatagram == 0 {
		in.MaxDatagram = DefaultDatagram
	}
	if in.MaxDatagram < MinDatagram || in.MaxDatagram > MaxDatagram {
		return Config{}, errors.New("kankimix: MaxDatagram must be between 256 and 1472")
	}
	if in.Padding < 0 || in.Padding > 255 {
		return Config{}, errors.New("kankimix: Padding must be between 0 and 255")
	}
	if in.KeepAlive == 0 {
		in.KeepAlive = 10 * time.Second
	}
	if in.KeepAlive < 100*time.Millisecond {
		return Config{}, errors.New("kankimix: KeepAlive must be at least 100ms")
	}
	if in.PathTimeout == 0 {
		in.PathTimeout = 1500 * time.Millisecond
	}
	if in.PathTimeout < 100*time.Millisecond {
		return Config{}, errors.New("kankimix: PathTimeout must be at least 100ms")
	}
	return in, nil
}
