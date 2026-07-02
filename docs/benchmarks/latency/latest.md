### Benchmark Results (1000 iterations)

- Generated at (UTC): 2026-07-02T16:41:56.004538Z
- Runner: samsung SM-A356E (physical device)
- Android: 16 (API 36)
- Device: brand=samsung, device=a35x, product=a35xjvxx
- Hardware loop: No; uses virtual transport and simulated firmware
- Transport: VirtualRobotPort
- Firmware: MockRP2040 simulator with 5 ms processing delay
- Protocol: Existing RP2040 serial request/response protocol
- Git commit: 3639c37a0ae8f8960bb493a226ff1c06e801a1f4 (dirty)
- Warm-up iterations: 100
- Measured iterations: 1000

Success Rate: 100.00% (1000/1000)

| Metric                             | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:-----------------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing              | 0.231     | 0.029    | 5.148    | 0.540    |
| M2: Handling/Serialization         | 0.071     | 0.015    | 1.580    | 0.187    |
| M3: Android Write Blocking         | 0.674     | 0.187    | 2.504    | 1.521    |
| M4: Response Wait After Write      | 5.830     | 4.676    | 10.476   | 6.765    |
| M5: Buffer Processing              | 0.619     | 0.155    | 5.463    | 1.232    |
| M6: Wake-up Lag                    | 0.477     | 0.094    | 3.094    | 1.108    |
| M7: App Logic                      | 0.731     | 0.183    | 7.599    | 1.656    |
| Total RTT                          | 8.634     | 5.963    | 16.350   | 11.452   |
