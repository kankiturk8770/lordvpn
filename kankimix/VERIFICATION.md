# Verification record

Toolchain: Go 1.24, linux/amd64. All commands pass:

```sh
go vet ./...
go test -race -count=15 ./...
GOMAXPROCS=1 go test -count=5 ./...
go test -run '^$' -fuzz FuzzDecoder  -fuzztime 45s   # ~4.2M executions
go test -run '^$' -fuzz FuzzDatagram -fuzztime 40s   # ~3.5M executions
```

Covered behaviour:

- GF(256) field properties, 32-bit packet/symbol number reconstruction.
- FEC: burst loss across overlapping windows with one repair per symbol,
  4-packet bursts, repairs arriving before data, late duplicates, 40 randomized
  trials with 15% data / 15% repair loss (never a corrupted symbol).
- Over a lossy UDP relay: 1-RTT handshake, handshake surviving dropped INITs,
  ~99% delivery under ~16% bursty loss with reordering, reordering not counted
  as loss, server address learning, carrier NAT rebinding mid-session,
  critical duplication + dedup, dead-path detection and failover, client
  restart with fresh keys, header obfuscation (no cleartext CID/payload, flat
  header byte distribution, padded lengths), replay rejection, deadline
  eviction, config/submission validation, 28-byte overhead, MTU bounds.
- Zero heap allocations for Submit, the send path (seal + header protection +
  UDP write) and the receive/decoder path.

Benchmarks (Xeon 2.3 GHz):

```
BenchmarkSubmit-4          415 ns/op   0 allocs/op
BenchmarkGFMulAdd1KiB-4    323 ns/op   3167 MB/s   0 allocs/op
```
