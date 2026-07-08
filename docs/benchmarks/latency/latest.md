### Benchmark Results (1000 iterations)

- Generated at (UTC): 2026-07-02T16:45:33.510188Z
- Runner: samsung SM-A356E (physical device)
- Android: 16 (API 36)
- Device: brand=samsung, device=a35x, product=a35xjvxx
- Hardware loop: No; uses virtual transport and simulated firmware
- Transport: VirtualRobotPort
- Firmware: MockRP2040 simulator with 5 ms processing delay
- Protocol: Existing RP2040 serial request/response protocol
- Git commit: 858fe6da7833e028a5f519a5b0417f2d42996426 (clean)
- Warm-up iterations: 100
- Measured iterations: 1000

Success Rate: 100.00% (1000/1000)

| Metric                             | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:-----------------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing              | 0.287     | 0.044    | 5.633    | 0.977    |
| M2: Handling/Serialization         | 0.061     | 0.015    | 1.996    | 0.123    |
| M3: Android Write Blocking         | 0.616     | 0.163    | 4.779    | 1.208    |
| M4: Response Wait After Write      | 5.816     | 4.910    | 11.959   | 6.935    |
| M5: Buffer Processing              | 0.521     | 0.143    | 4.318    | 0.966    |
| M6: Wake-up Lag                    | 0.519     | 0.095    | 3.717    | 1.150    |
| M7: App Logic                      | 0.688     | 0.186    | 3.888    | 1.322    |
| Total RTT                          | 8.508     | 6.018    | 19.376   | 11.359   |
