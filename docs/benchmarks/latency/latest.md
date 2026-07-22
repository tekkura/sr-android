### Benchmark Results (1000 iterations)

- Generated at (UTC): 2026-07-22T09:14:11.042918Z
- Runner: samsung SM-A356E (physical device)
- Android: 16 (API 36)
- Device: brand=samsung, device=a35x, product=a35xjvxx
- Hardware loop: No; uses virtual transport and simulated firmware
- Transport: VirtualRobotPort
- Firmware: MockRP2040 simulator with 5 ms processing delay
- Protocol: Existing RP2040 serial request/response protocol
- Git commit: 108b21174c15afc69cf1bc9bbfa372f955193c89 (dirty)
- Warm-up iterations: 100
- Measured iterations: 1000

Success Rate: 100.00% (1000/1000)

| Metric                             | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:-----------------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing              | 0.185     | 0.037    | 3.484    | 0.520    |
| M2: Handling/Serialization         | 0.069     | 0.016    | 1.550    | 0.179    |
| M3: Transport Out                  | 0.487     | 0.158    | 2.005    | 1.193    |
| M4: Firmware Processing            | 5.221     | 5.090    | 8.178    | 5.469    |
| M5: Response Transit in            | 0.487     | 0.158    | 2.005    | 1.193    |
| M6: Buffer Processing              | 0.513     | 0.171    | 3.052    | 1.126    |
| M7: Wake-up Lag                    | 0.390     | 0.100    | 2.170    | 1.058    |
| M8: App Logic                      | 0.723     | 0.212    | 4.748    | 2.259    |
| Total RTT                          | 8.076     | 6.099    | 16.415   | 12.638   |
