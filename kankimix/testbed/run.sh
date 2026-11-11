#!/usr/bin/env bash
# End-to-end testbed: a "phone" network namespace talks to a "server" namespace
# through user-space mobile-network emulators. Game traffic is measured both
# directly over the emulated network (baseline) and through a real KankiMix
# TUN tunnel. Requires root, iproute2 and /dev/net/tun.
#
# usage: testbed/run.sh <bin-dir> <scenario> [duration]
#   scenarios: good4g | iran | bad | nat | handover | throughput
set -u
BIN=$1; SCEN=$2; DUR=${3:-30s}
LOG=${LOG:-/tmp/kmtest}; mkdir -p "$LOG"
PIDS=()

cleanup() {
  for p in "${PIDS[@]}"; do kill -TERM "$p" 2>/dev/null; done
  sleep 0.5
  ip netns del phone 2>/dev/null; ip netns del srv 2>/dev/null
  ip link del e1 2>/dev/null; ip link del e2 2>/dev/null; ip link del e3 2>/dev/null
}
trap cleanup EXIT
cleanup 2>/dev/null; PIDS=()

ip netns add phone; ip netns add srv
ip -n phone link set lo up; ip -n srv link set lo up
ip link add e1 type veth peer name p0 netns phone
ip link add e3 type veth peer name p1 netns phone
ip link add e2 type veth peer name s0 netns srv
ip addr add 10.0.1.1/24 dev e1; ip addr add 10.0.3.1/24 dev e3; ip addr add 10.0.2.1/24 dev e2
ip link set e1 up; ip link set e2 up; ip link set e3 up
ip -n phone addr add 10.0.1.2/24 dev p0; ip -n phone addr add 10.0.3.2/24 dev p1; ip -n srv addr add 10.0.2.2/24 dev s0
ip -n phone link set p0 up; ip -n phone link set p1 up; ip -n srv link set s0 up

bg() { "$@" & PIDS+=($!); }

PROFILE=$SCEN; EMUARGS=(); PATH2=0
case $SCEN in
  nat) PROFILE=iran; EMUARGS=(-rebind 5s) ;;
  handover) PROFILE=wifi; EMUARGS=(-down-after 12s); PATH2=1 ;;
  throughput) PROFILE=good4g ;;
esac

# Emulators: tunnel path 1, tunnel path 2 (wifi), and baseline (no tunnel).
bg "$BIN/kmemu" -listen 10.0.1.1:7000 -target 10.0.2.2:7000 -profile "$PROFILE" -seed 11 "${EMUARGS[@]}" 2>"$LOG/emu1.log"
PROFILE2=wifi; [ "$SCEN" = handover ] && PROFILE2=iran
bg "$BIN/kmemu" -listen 10.0.3.1:7000 -target 10.0.2.2:7001 -profile "$PROFILE2" -seed 13 2>"$LOG/emu2.log"
bg "$BIN/kmemu" -listen 10.0.1.1:8000 -target 10.0.2.2:9000 -profile "$PROFILE" -seed 11 "${EMUARGS[@]}" 2>"$LOG/emu0.log"

bg ip netns exec srv "$BIN/kmgame" -echo 0.0.0.0:9000
SRVARGS=(-mode server -listen 10.0.2.2:7000 -tun km0)
CLIARGS=(-mode client -server 10.0.1.1:7000 -tun km0)
if [ "$PATH2" = 1 ]; then SRVARGS+=(-listen2 10.0.2.2:7001); CLIARGS+=(-server2 10.0.3.1:7000); fi
bg ip netns exec srv "$BIN/kmtun" "${SRVARGS[@]}" 2>"$LOG/server.log"
# The client runs like a phone: one CPU core, one Go scheduler thread.
bg ip netns exec phone env GOMAXPROCS=1 taskset -c 0 "$BIN/kmtun" "${CLIARGS[@]}" 2>"$LOG/client.log"
sleep 0.3
ip -n srv addr add 10.99.0.1/24 dev km0; ip -n srv link set km0 mtu 1180 up
ip -n phone addr add 10.99.0.2/24 dev km0; ip -n phone link set km0 mtu 1180 up

t0=$(date +%s%N)
until ip netns exec phone ping -c1 -W1 10.99.0.1 >/dev/null 2>&1; do sleep 0.05; done
echo "tunnel up after $(( ($(date +%s%N)-t0)/1000000 )) ms"

CLIPID=$(pgrep -f "kmtun -mode client" | head -1)
if [ "$SCEN" = throughput ]; then
  bg ip netns exec srv iperf3 -s >/dev/null 2>&1
  sleep 0.3
  ip netns exec phone iperf3 -c 10.99.0.1 -t 10 -R 2>&1 | grep -E "receiver|sender"
  sleep 0.5
  ip netns exec phone iperf3 -c 10.99.0.1 -t 12 -R >/dev/null 2>&1 & I=$!
  sleep 1
  ip netns exec phone "$BIN/kmgame" -target 10.99.0.1:9000 -dur 10s -label "game-during-download"
  wait $I
else
  ip netns exec phone "$BIN/kmgame" -target 10.0.1.1:8000 -dur "$DUR" -label "$SCEN/direct" & G1=$!
  ip netns exec phone "$BIN/kmgame" -target 10.99.0.1:9000 -dur "$DUR" -label "$SCEN/kankimix" & G2=$!
  wait $G1 $G2
fi
if [ -n "$CLIPID" ]; then
  read -r -a st < /proc/$CLIPID/stat
  echo "client cpu: $(( (st[13]+st[14]) * 10 )) ms total, rss $(( $(awk '/VmRSS/{print $2}' /proc/$CLIPID/status) / 1024 )) MiB"
fi
cleanup; trap - EXIT
for f in emu0 emu1 emu2 client server; do sed "s/^[0-9\/: ]*/[$f] /" "$LOG/$f.log" | grep -E "emu|final"; done
