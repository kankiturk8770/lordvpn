package kankimix

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
)

// Wire format
//
// Short (session) packet:
//
//	+--------+----------------+-------------------------+--------+
//	| form=0 | packet# (32b)  | AES-256-GCM ciphertext  | tag 16 |
//	+--------+----------------+-------------------------+--------+
//	 \______ masked _______/
//
// Long (handshake) packet:
//
//	+--------+-----------------+-------------------------+--------+
//	| form=1 | random nonce 12 | AES-256-GCM ciphertext  | tag 16 |
//	+--------+-----------------+-------------------------+--------+
//	 \__ masked (first 5 bytes) _/
//
// Header protection: mask = AES-ECB(hpKey, tag). The first five bytes are
// XORed with the mask after sealing, so every byte on the wire is
// indistinguishable from random to an observer that lacks the key: there is no
// cleartext connection ID, path ID, version, magic or incrementing counter.
// The unmasked header is authenticated as AEAD associated data.

const kdfLabel = "KankiMix/v2/"

// kdf returns HMAC-SHA256(key, label || parts...). It allocates and must only be
// used on control paths (setup, handshake, rekey).
func kdf(key []byte, label string, parts ...[]byte) [32]byte {
	h := hmac.New(sha256.New, key)
	_, _ = h.Write([]byte(kdfLabel))
	_, _ = h.Write([]byte(label))
	for _, part := range parts {
		var length [2]byte
		binary.BigEndian.PutUint16(length[:], uint16(len(part)))
		_, _ = h.Write(length[:])
		_, _ = h.Write(part)
	}
	var out [32]byte
	h.Sum(out[:0])
	return out
}

func u64bytes(v uint64) []byte {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	return b[:]
}

func u16bytes(v uint16) []byte {
	var b [2]byte
	binary.BigEndian.PutUint16(b[:], v)
	return b[:]
}

func newGCM(key [32]byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// pathKeys holds one session generation's directional keys for one path. A new
// object is installed atomically on every (re)handshake; per-direction packet
// number spaces restart with it, so (key, nonce) pairs are never reused.
type pathKeys struct {
	gen    uint64
	send   cipher.AEAD
	recv   cipher.AEAD
	sendIV [12]byte
	recvIV [12]byte
}

func directionLabels(role Role) (send, recv string) {
	if role == RoleServer {
		return "s2c", "c2s"
	}
	return "c2s", "s2c"
}

func derivePathKeys(sessionKey *[32]byte, role Role, pathID uint16, gen uint64) (*pathKeys, error) {
	sendLabel, recvLabel := directionLabels(role)
	id := u16bytes(pathID)
	sendKey := kdf(sessionKey[:], "key", []byte(sendLabel), id)
	recvKey := kdf(sessionKey[:], "key", []byte(recvLabel), id)
	sendIV := kdf(sessionKey[:], "iv", []byte(sendLabel), id)
	recvIV := kdf(sessionKey[:], "iv", []byte(recvLabel), id)
	send, err := newGCM(sendKey)
	if err != nil {
		return nil, err
	}
	recv, err := newGCM(recvKey)
	if err != nil {
		return nil, err
	}
	k := &pathKeys{gen: gen, send: send, recv: recv}
	copy(k.sendIV[:], sendIV[:12])
	copy(k.recvIV[:], recvIV[:12])
	return k, nil
}

// makeNonce writes iv XOR packetNumber (QUIC-style) into nonce.
func makeNonce(nonce *[12]byte, iv *[12]byte, packetNumber uint64) {
	*nonce = *iv
	var pn [8]byte
	binary.BigEndian.PutUint64(pn[:], packetNumber)
	for i := 0; i < 8; i++ {
		nonce[4+i] ^= pn[i]
	}
}

// maskHeader XORs the first shortHeaderLen bytes of a sealed datagram with a
// mask derived from its authentication tag. It is its own inverse.
func maskHeader(hp cipher.Block, datagram []byte, scratch *[16]byte) {
	hp.Encrypt(scratch[:], datagram[len(datagram)-tagLen:])
	for i := 0; i < shortHeaderLen; i++ {
		datagram[i] ^= scratch[i]
	}
}

// decodePacketNumber reconstructs a full packet number from its low 32 bits
// using the largest number seen so far (RFC 9000, appendix A.3).
func decodePacketNumber(largest uint64, truncated uint32) uint64 {
	const window = uint64(1) << 32
	const halfWindow = window / 2
	const mask = window - 1
	expected := largest + 1
	candidate := (expected &^ mask) | uint64(truncated)
	if candidate+halfWindow <= expected && candidate < (uint64(1)<<62)-window {
		return candidate + window
	}
	if candidate > expected+halfWindow && candidate >= window {
		return candidate - window
	}
	return candidate
}
