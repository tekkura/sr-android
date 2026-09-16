### Benchmark Results (1000 iterations)

- Generated at (UTC): 2026-09-05T04:44:10.193851Z
- Runner: Google Pixel 3a (physical device)
- Android: 12 (API 32)
- Device: brand=google, device=sargo, product=sargo
- Hardware loop: Yes; physical USB serial transport to attached firmware
- Transport: UsbSerial + RealRobotSerialPort
- Firmware: `tekkura/feature/milestone-3-crc-framing` at `9bfeef30fbd91745275104a4a887046650797cdd`
- Firmware hash attribution: operator run history and branch ancestry; not embedded in the device report
- Protocol: CRC length-prefix
- Android branch: `tekkura/CommunicationFraming+crc-length-prefix`
- Git commit: c01c50bfe74894e862817aed8833a43c7e5705c6 (clean)
- Warm-up iterations: 100
- Measured iterations: 1000

Success Rate: 100.00% (1000/1000)

| Metric                             | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:-----------------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing              | 0.385     | 0.094    | 2.776    | 0.630    |
| M2: Handling/Serialization         | 0.208     | 0.065    | 1.485    | 0.309    |
| M3: Android Write Blocking         | 1.090     | 0.268    | 4.532    | 1.693    |
| M4: Response Wait After Write      | 14.337    | 8.683    | 25.098   | 17.890   |
| M5: Buffer Processing              | 3.855     | 1.924    | 6.536    | 4.869    |
| M6: Wake-up Lag                    | 0.815     | 0.198    | 2.466    | 1.093    |
| M7: App Logic                      | 2.015     | 0.555    | 4.702    | 2.671    |
| Total RTT                          | 22.705    | 18.607   | 35.097   | 26.253   |

All measured responses completed, but Gradle failed the mean RTT < 20 ms assertion.
These are the tested rebased revisions, before Android `720a67dc` (RESET_STATE
ACK alignment) and firmware `8d3e3c9` (protocol documentation only).
