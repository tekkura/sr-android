### Benchmark Results (1000 iterations)

- Generated at (UTC): 2026-07-22T09:08:23.759522Z
- Runner: samsung SM-A356E (physical device)
- Android: 16 (API 36)
- Device: brand=samsung, device=a35x, product=a35xjvxx
- Hardware loop: No; uses virtual transport and simulated firmware
- Transport: VirtualRobotPort
- Firmware: MockRP2040 simulator with 5 ms processing delay
- Protocol: Existing RP2040 serial request/response protocol
- Git commit: 96acaacc180fbef8b067abf98f7a3b1741cd9617 (dirty)
- Warm-up iterations: 100
- Measured iterations: 1000

Success Rate: 100.00% (1000/1000)

| Metric                             | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:-----------------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing              | 0.236     | 0.043    | 6.257    | 0.779    |
| M2: Handling/Serialization         | 0.071     | 0.016    | 0.596    | 0.184    |
| M3: Transport Out                  | 0.516     | 0.170    | 2.888    | 1.174    |
| M4: Firmware Processing            | 5.249     | 5.099    | 7.642    | 5.595    |
| M5: Response Transit in            | 0.516     | 0.170    | 2.888    | 1.174    |
| M6: Buffer Processing              | 0.563     | 0.181    | 3.757    | 1.202    |
| M7: Wake-up Lag                    | 0.458     | 0.096    | 5.575    | 1.263    |
| M8: App Logic                      | 0.767     | 0.177    | 4.965    | 2.201    |
| Total RTT                          | 8.376     | 6.180    | 19.596   | 12.726   |
