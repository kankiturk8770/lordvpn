// Command kmgame simulates real-time game traffic (fixed tick rate, small UDP
// packets) against an echo server and reports what a player would feel:
// on-time delivery, round-trip latency percentiles, jitter and lag spikes.
package main

import (
	"encoding/binary"
	"flag"
	"fmt"
	"log"
	"math"
	"net"
	"net/netip"
	"sort"
	"time"
)

func main() {
	echo := flag.String("echo", "", "run as echo server on this address")
	target := flag.String("target", "", "echo server address")
	pps := flag.Int("pps", 30, "packets per second (game tick)")
	size := flag.Int("size", 90, "payload bytes")
	duration := flag.Duration("dur", 30*time.Second, "test duration")
	deadline := flag.Duration("deadline", 250*time.Millisecond, "round trip later than this counts as lost")
	label := flag.String("label", "", "result label")
	flag.Parse()

	if *echo != "" {
		c, err := net.ListenUDP("udp", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(*echo)))
		if err != nil {
			log.Fatal(err)
		}
		buf := make([]byte, 2048)
		for {
			n, from, err := c.ReadFromUDPAddrPort(buf)
			if err != nil {
				log.Fatal(err)
			}
			_, _ = c.WriteToUDPAddrPort(buf[:n], from)
		}
	}

	c, err := net.DialUDP("udp", nil, net.UDPAddrFromAddrPort(netip.MustParseAddrPort(*target)))
	if err != nil {
		log.Fatal(err)
	}
	total := int(duration.Seconds() * float64(*pps))
	sent := make([]time.Time, total)
	rtt := make([]time.Duration, total)
	go func() {
		buf := make([]byte, 2048)
		for {
			n, err := c.Read(buf)
			if err != nil || n < 8 {
				continue
			}
			seq := binary.BigEndian.Uint64(buf)
			if seq < uint64(total) && rtt[seq] == 0 {
				rtt[seq] = time.Since(sent[seq])
			}
		}
	}()
	payload := make([]byte, *size)
	tick := time.NewTicker(time.Second / time.Duration(*pps))
	for i := 0; i < total; i++ {
		<-tick.C
		binary.BigEndian.PutUint64(payload, uint64(i))
		sent[i] = time.Now()
		_, _ = c.Write(payload)
	}
	time.Sleep(time.Second)

	var ok []float64
	late, lost, spikes := 0, 0, 0
	var prev float64
	var jitterSum float64
	var jitterN int
	worstGap, gap := 0, 0
	for i := 0; i < total; i++ {
		r := rtt[i]
		switch {
		case r == 0:
			lost++
			gap++
		case r > *deadline:
			late++
			gap++
		default:
			ms := float64(r.Microseconds()) / 1000
			ok = append(ok, ms)
			if prev > 0 {
				jitterSum += math.Abs(ms - prev)
				jitterN++
			}
			prev = ms
			if ms > 150 {
				spikes++
			}
			gap = 0
		}
		if gap > worstGap {
			worstGap = gap
		}
	}
	sort.Float64s(ok)
	pct := func(p float64) float64 {
		if len(ok) == 0 {
			return 0
		}
		return ok[int(math.Min(float64(len(ok)-1), p*float64(len(ok))))]
	}
	fmt.Printf("RESULT %-28s on-time=%6.2f%% lost=%5.2f%% late=%5.2f%% rtt p50=%6.1f p95=%6.1f p99=%6.1f max=%6.1f ms jitter=%5.1f ms spikes>150ms=%d worst-freeze=%dms\n",
		*label, 100*float64(len(ok))/float64(total), 100*float64(lost)/float64(total), 100*float64(late)/float64(total),
		pct(0.5), pct(0.95), pct(0.99), pct(1), jitterSum/math.Max(1, float64(jitterN)), spikes, worstGap*1000 / *pps)
}
