//go:build linux

// Command kmtun is a minimal point-to-point IP tunnel over a KankiMix session.
// It reads IP packets from a Linux TUN device and carries them over one or more
// UDP paths; the peer writes them to its own TUN device. IP addressing and
// routing are configured externally (e.g. with `ip`).
//
//	server: kmtun -mode server -listen 0.0.0.0:7000 -tun km0
//	client: kmtun -mode client -server 203.0.113.1:7000 [-server2 ...] -tun km0
package main

import (
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"syscall"
	"time"
	"unsafe"

	"kankimix"
)

func openTUN(name string) (*os.File, error) {
	f, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	var req [40]byte
	copy(req[:16], name)
	const iffTUN, iffNoPI = 0x0001, 0x1000
	*(*uint16)(unsafe.Pointer(&req[16])) = iffTUN | iffNoPI
	const tunSetIff = 0x400454ca
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), tunSetIff, uintptr(unsafe.Pointer(&req[0]))); errno != 0 {
		f.Close()
		return nil, errno
	}
	return f, nil
}

// classify maps IP traffic to KankiMix classes: small UDP/ICMP (games, voice,
// DNS) is realtime; TCP and bulk UDP are background.
func classify(packet []byte) (kankimix.TrafficClass, time.Duration) {
	if len(packet) < 20 {
		return kankimix.ClassBackground, time.Second
	}
	proto := packet[9]
	if packet[0]>>4 == 6 && len(packet) >= 40 {
		proto = packet[6]
	}
	switch {
	case proto == 17 && len(packet) <= 600, proto == 1, proto == 58:
		return kankimix.ClassRealtime, 250 * time.Millisecond
	default:
		return kankimix.ClassBackground, 2 * time.Second
	}
}

func main() {
	mode := flag.String("mode", "client", "client or server")
	listen := flag.String("listen", "0.0.0.0:7000", "server: UDP listen address")
	server := flag.String("server", "", "client: server address for path 1")
	server2 := flag.String("server2", "", "client: optional server address for path 2")
	listen2 := flag.String("listen2", "", "server: optional UDP listen address for path 2")
	tunName := flag.String("tun", "km0", "TUN device name")
	pskHex := flag.String("psk", "6b616e6b696d69782d746573746265642d70736b2d3332627974657321212121", "pre-shared key (hex, >=32 bytes)")
	cid := flag.Uint64("cid", 0x4b4d5654, "connection ID")
	statsEvery := flag.Duration("stats", 0, "print stats at this interval (0 disables)")
	flag.Parse()

	psk, err := hex.DecodeString(*pskHex)
	if err != nil {
		log.Fatal(err)
	}
	tun, err := openTUN(*tunName)
	if err != nil {
		log.Fatalf("open tun: %v", err)
	}
	role := kankimix.RoleClient
	if *mode == "server" {
		role = kankimix.RoleServer
	}
	core, err := kankimix.NewCore(kankimix.Config{
		ConnectionID: *cid,
		Role:         role,
		Secret:       psk,
		QueueSize:    1024,
		OnData: func(data []byte, _ kankimix.SymbolMeta) {
			_, _ = tun.Write(data)
		},
	})
	if err != nil {
		log.Fatal(err)
	}
	addPath := func(id uint16, local string, remote string) {
		conn, err := net.ListenUDP("udp", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(local)))
		if err != nil {
			log.Fatal(err)
		}
		var r netip.AddrPort
		if remote != "" {
			r = netip.MustParseAddrPort(remote)
		}
		if err := core.AddPath(id, conn, r); err != nil {
			log.Fatal(err)
		}
	}
	if role == kankimix.RoleServer {
		addPath(1, *listen, "")
		if *listen2 != "" {
			addPath(2, *listen2, "")
		}
	} else {
		addPath(1, "0.0.0.0:0", *server)
		if *server2 != "" {
			addPath(2, "0.0.0.0:0", *server2)
		}
	}
	log.Printf("kmtun %s up on %s (payload limit %d)", *mode, *tunName, core.PayloadLimit())

	go func() {
		buf := make([]byte, 65536)
		for {
			n, err := tun.Read(buf)
			if err != nil {
				log.Fatalf("tun read: %v", err)
			}
			class, ttl := classify(buf[:n])
			_ = core.Submit(buf[:n], class, ttl)
		}
	}()
	if *statsEvery > 0 {
		go func() {
			for range time.Tick(*statsEvery) {
				s := core.Stats()
				line := fmt.Sprintf("stats data=%d repairs=%d delivered=%d recovered=%d stale=%d nopath=%d qdrop=%d hs=%d addr=%d fail=%d",
					s.SentData, s.SentRepairs, s.Delivered, s.Recovered, s.StaleDropped, s.NoPathDropped, s.QueueDropped, s.Handshakes, s.AddressChanges, s.PathFailures)
				for id := uint16(1); id <= 2; id++ {
					if ps, err := core.PathStats(id); err == nil {
						line += fmt.Sprintf(" | p%d valid=%v rtt=%.1fms min=%.1fms loss=%d‰", id, ps.Valid, float64(ps.RTTMicros)/1000, float64(ps.MinRTTMicros)/1000, ps.LossPermille)
					}
				}
				log.Print(line)
			}
		}()
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	s := core.Stats()
	log.Printf("final data=%d repairs=%d delivered=%d recovered=%d stale=%d nopath=%d qdrop=%d handshakes=%d addrchanges=%d pathfailures=%d shaped=%d",
		s.SentData, s.SentRepairs, s.Delivered, s.Recovered, s.StaleDropped, s.NoPathDropped, s.QueueDropped, s.Handshakes, s.AddressChanges, s.PathFailures, s.ShapedDropped)
	_ = core.Close()
}
