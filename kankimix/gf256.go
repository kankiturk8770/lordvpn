package kankimix

// GF(2^8) arithmetic with the 0x11d reduction polynomial.
//
// Scalar operations use log/exp tables. Bulk vector operations use a full
// 64 KiB multiplication table so the inner loop is a single indexed load and
// XOR per byte, with no branches on zero operands.

var (
	gfExp      [512]byte
	gfLog      [256]byte
	gfMulTable [256][256]byte
)

func init() {
	x := 1
	for i := 0; i < 255; i++ {
		gfExp[i] = byte(x)
		gfLog[x] = byte(i)
		x <<= 1
		if x&0x100 != 0 {
			x ^= 0x11d
		}
	}
	for i := 255; i < len(gfExp); i++ {
		gfExp[i] = gfExp[i-255]
	}
	for a := 1; a < 256; a++ {
		for b := 1; b < 256; b++ {
			gfMulTable[a][b] = gfExp[int(gfLog[a])+int(gfLog[b])]
		}
	}
}

func gfMul(a, b byte) byte {
	return gfMulTable[a][b]
}

func gfInv(a byte) byte {
	if a == 0 {
		return 0
	}
	return gfExp[255-int(gfLog[a])]
}

// gfScale multiplies dst by coefficient in place.
func gfScale(dst []byte, coefficient byte) {
	switch coefficient {
	case 0:
		clear(dst)
	case 1:
	default:
		row := &gfMulTable[coefficient]
		for i, value := range dst {
			dst[i] = row[value]
		}
	}
}

// gfMulAdd computes dst ^= coefficient*source over len(source) bytes.
// len(dst) must be at least len(source).
func gfMulAdd(dst, source []byte, coefficient byte) {
	switch coefficient {
	case 0:
		return
	case 1:
		dst = dst[:len(source)]
		for i, value := range source {
			dst[i] ^= value
		}
	default:
		row := &gfMulTable[coefficient]
		dst = dst[:len(source)]
		for i, value := range source {
			dst[i] ^= row[value]
		}
	}
}

// coefficient generates deterministic nonzero RLC coefficients from the
// authenticated repair identity (base, size, index) and the row offset.
// Both peers reconstruct every coefficient without it being transmitted.
func coefficient(base uint64, size, repairIndex uint8, row int) byte {
	x := base + 0x9e3779b97f4a7c15
	x ^= uint64(size) << 17
	x ^= uint64(repairIndex+1) * 0xbf58476d1ce4e5b9
	x ^= uint64(row+1) * 0x94d049bb133111eb
	x ^= x >> 30
	x *= 0xbf58476d1ce4e5b9
	x ^= x >> 27
	x *= 0x94d049bb133111eb
	x ^= x >> 31
	return byte(x%255) + 1
}
