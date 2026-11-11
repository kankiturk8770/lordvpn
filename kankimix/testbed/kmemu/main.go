// Command kmemu is a user-space mobile-network emulator for UDP: it relays
// datagrams between a client and a target while applying correlated delay and
// jitter, Gilbert-Elliott burst loss, a bandwidth bottleneck with a finite
// queue (bufferbloat), scheduled blackouts (handover) and carrier-NAT port
// rebinding. It is used because the sandbox kernel has no sch_netem.
package main

import (
	"flag"
	"log"
	"math"
	"math/rand/v2"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type profile struct {
	delay, jitter        time.Duration
	pGoodBad, pBadGood   float64
	lossGood, lossBad    float64
	reorder              float64
	rateBps              float64
	queue                time.Duration
	blackEvery, blackFor time.Duration
}

var profiles = map[string]profile{
	"lan":    {delay: 1 * time.Millisecond},
	"good4g": {delay: 25 * time.Millisecond, jitter: 4 * time.Millisecond, pGoodBad: 0.002, pBadGood: 0.5, lossGood: 0.001, lossBad: 0.5, reorder: 0.002, rateBps: 20e6, queue: 150 * time.Millisecond},
	"iran":   {delay: 35 * time.Millisecond, jitter: 15 * time.Millisecond, pGoodBad: 0.01, pBadGood: 0.3, lossGood: 0.005, lossBad: 0.7, reorder: 0.01, rateBps: 8e6, queue: 300 * time.Millisecond},
	"bad":    {delay: 45 * time.Millisecond, jitter: 25 * time.Millisecond, pGoodBad: 0.03, pBadGood: 0.25, lossGood: 0.01, lossBad: 0.8, reorder: 0.02, rateBps: 4e6, queue: 400 * time.Millisecond, blackEvery: 10 * time.Second, blackFor: 400 * time.Millisecond},
	"wifi":   {delay: 12 * time.Millisecond, jitter: 3 * time.Millisecond, pGoodBad: 0.003, pBadGood: 0.5, lossGood: 0.002, lossBad: 0.5, reorder: 0.002, rateBps: 30e6, queue: 100 * time.Millisecond},
}

type direction struct {
	name                               string
	p                                  profile
	rng                                *rand.Rand
	mu                                 sync.Mutex
	bad                                bool
	jit                                float64
	lastDep                            time.Time
	busy                               time.Time
	in, lossDrop, queueDrop, blackDrop atomic.Uint64
	start                              time.Time
	down                               *atomic.Bool
}

// admit decides the fate of one datagram and returns its delivery delay.
func (d *direction) admit(size int) (time.Duration, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.in.Add(1)
	now := time.Now()
	if d.down != nil && d.down.Load() {
		d.blackDrop.Add(1)
		return 0, false
	}
	if d.p.blackEvery > 0 {
		phase := now.Sub(d.start) % d.p.blackEvery
		if phase > d.p.blackEvery-d.p.blackFor {
			d.blackDrop.Add(1)
			return 0, false
		}
	}
	if d.bad {
		if d.rng.Float64() < d.p.pBadGood {
			d.bad = false
		}
	} else if d.rng.Float64() < d.p.pGoodBad {
		d.bad = true
	}
	loss := d.p.lossGood
	if d.bad {
		loss = d.p.lossBad
	}
	if d.rng.Float64() < loss {
		d.lossDrop.Add(1)
		return 0, false
	}
	// Bottleneck queue.
	depart := now
	if d.p.rateBps > 0 {
		if d.busy.After(now) {
			depart = d.busy
		}
		if depart.Sub(now) > d.p.queue {
			d.queueDrop.Add(1)
			return 0, false
		}
		depart = depart.Add(time.Duration(float64(size*8) / d.p.rateBps * float64(time.Second)))
		d.busy = depart
	}
	// Correlated jitter, FIFO unless an explicit reordering event occurs.
	d.jit = 0.85*d.jit + 0.15*math.Abs(d.rng.NormFloat64())*float64(d.p.jitter)*2.5
	arrive := depart.Add(d.p.delay + time.Duration(d.jit))
	if d.rng.Float64() >= d.p.reorder && arrive.Before(d.lastDep) {
		arrive = d.lastDep
	}
	if arrive.After(d.lastDep) {
		d.lastDep = arrive
	}
	return arrive.Sub(now), true
}

func main() {
	listen := flag.String("listen", "", "address clients send to")
	target := flag.String("target", "", "upstream target")
	name := flag.String("profile", "iran", "lan, good4g, iran, bad, wifi")
	seed := flag.Uint64("seed", 1, "random seed")
	rebind := flag.Duration("rebind", 0, "change the NAT source port at this interval")
	downAfter := flag.Duration("down-after", 0, "black-hole the link permanently after this time")
	flag.Parse()
	p, ok := profiles[*name]
	if !ok {
		log.Fatalf("unknown profile %q", *name)
	}
	front, err := net.ListenUDP("udp", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(*listen)))
	if err != nil {
		log.Fatal(err)
	}
	targetAddr := netip.MustParseAddrPort(*target)
	start := time.Now()
	var down atomic.Bool
	if *downAfter > 0 {
		time.AfterFunc(*downAfter, func() { down.Store(true) })
	}
	up := &direction{name: "up", p: p, rng: rand.New(rand.NewPCG(*seed, 1)), start: start, down: &down}
	dn := &direction{name: "down", p: p, rng: rand.New(rand.NewPCG(*seed, 2)), start: start, down: &down}

	var client atomic.Value
	var back atomic.Pointer[net.UDPConn]
	newBack := func() {
		c, err := net.ListenUDP("udp", &net.UDPAddr{})
		if err != nil {
			log.Fatal(err)
		}
		old := back.Swap(c)
		go func() {
			buf := make([]byte, 2048)
			for {
				n, _, err := c.ReadFromUDPAddrPort(buf)
				if err != nil {
					return
				}
				to, ok := client.Load().(netip.AddrPort)
				if !ok {
					continue
				}
				if delay, ok := dn.admit(n + 28); ok {
					pkt := append([]byte(nil), buf[:n]...)
					time.AfterFunc(delay, func() { _, _ = front.WriteToUDPAddrPort(pkt, to) })
				}
			}
		}()
		if old != nil {
			time.AfterFunc(2*time.Second, func() { old.Close() })
		}
	}
	newBack()
	if *rebind > 0 {
		go func() {
			for range time.Tick(*rebind) {
				newBack()
			}
		}()
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := front.ReadFromUDPAddrPort(buf)
			if err != nil {
				return
			}
			client.Store(from)
			if delay, ok := up.admit(n + 28); ok {
				pkt := append([]byte(nil), buf[:n]...)
				conn := back.Load()
				time.AfterFunc(delay, func() { _, _ = conn.WriteToUDPAddrPort(pkt, targetAddr) })
			}
		}
	}()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	for _, d := range []*direction{up, dn} {
		in := d.in.Load()
		log.Printf("emu %s %s: in=%d loss=%d queue=%d blackout=%d (%.1f%% dropped)", *name, d.name, in, d.lossDrop.Load(), d.queueDrop.Load(), d.blackDrop.Load(),
			100*float64(d.lossDrop.Load()+d.queueDrop.Load()+d.blackDrop.Load())/math.Max(1, float64(in)))
	}
}
