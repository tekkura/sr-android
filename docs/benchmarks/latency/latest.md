# Latency Benchmark Result

This file is the latest accepted benchmark snapshot.

## Provenance

- Imported from: `docs/BENCHMARK.md` legacy results
- Seeded at (UTC): 2026-07-02T12:23:57.5957900Z
- Repository commit: `4e96e9e0`

## Benchmark Results (10000 iterations)

Success Rate: 100.00% (10000/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 0.525     | 0.051    | 5.606    | 1.171    |
| M2: Handling/Serialization | 0.029     | 0.003    | 1.614    | 0.043    |
| M3: Transport Out          | 1.536     | 0.214    | 9.117    | 2.360    |
| M4: Robot + Transit In     | 5.591     | 5.148    | 11.876   | 5.924    |
| M5: Buffer Processing      | 0.840     | 0.151    | 12.033   | 1.188    |
| M6: Wake-up Lag            | 0.771     | 0.115    | 4.167    | 1.254    |
| M7: App Logic              | 1.905     | 0.211    | 11.506   | 2.884    |
| Total RTT                  | 11.196    | 6.248    | 30.403   | 13.139   |
