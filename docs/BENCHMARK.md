# Communication Latency Benchmark Specification

This document defines the metrics and methodology for measuring the round-trip latency of the robot-phone communication stack. These benchmarks are used to identify bottlenecks and verify that the target latency of **< 20ms** is achieved.

## 1. Timing Junctions (Markers)

To precisely isolate delays, the following high-resolution timestamps (`System.nanoTime()`) are captured during a single command-response cycle:

| ID     | Junction Point            | Location                                             |
|:-------|:--------------------------|:-----------------------------------------------------|
| **T1** | Command Creation Start    | `SerialCommManager.setMotorLevels()`                 |
| **T2** | Writer Loop Wake-up       | `SerialCommManager.android2PiWriter` (after `wait`)  |
| **T3** | Transport Dispatch        | `UsbSerial.send(ByteArray)` (start)                  |
| **T4** | Simulator Receipt         | `MockRP2040.processPacket()` (start)                 |
| **T5** | Response Receipt at Phone | `UsbSerial.onNewData()` (start)                      |
| **T6** | Packet Queue Entry        | `UsbSerial.onCompletePacketReceived()` (end)         |
| **T7** | Manager Wake-up           | `SerialCommManager.receivePacket()` (after await)    |
| **T8** | State Applied             | `SerialCommManager.parseStatus()` (after publishers) |

## 2. Calculated Metrics

The following metrics are derived from the timestamps above to measure specific overheads:

| Metric                         | Calculation | Description                                                                   |
|:-------------------------------|:------------|:------------------------------------------------------------------------------|
| **M1: Outbound Queueing**      | $T2 - T1$   | Delay between command creation and the background thread picking it up.       |
| **M2: Handling/Serialization** | $T3 - T2$   | Time from thread wake-up until bytes are sent (includes `command.toBytes()`). |
| **M3: Transport Out**          | $T4 - T3$   | Latency from the phone to the robot (includes `VirtualRobotPort` dispatch).    |
| **M4: Robot + Transit In**     | $T5 - T4$   | Simulator processing time (5ms sleep) + Response transit back to phone.        |
| **M5: Buffer Processing**      | $T6 - T5$   | Time spent inside `PacketBuffer` parsing the response.                        |
| **M6: Wake-up Lag**            | $T7 - T6$   | Delay between the packet being queued and the manager thread waking up.       |
| **M7: App logic**              | $T8 - T7$   | Time taken to update internal state models and notify UI publishers.          |
| **Total RTT**                  | $T8 - T1$   | The full round-trip latency as experienced by the application.                |

## 3. Methodology

- **Sample Size**: 10,000 iterations per benchmark run.
- **Warm-up**: 100 iterations are discarded to allow for JIT optimization.
- **Environment**: Dedicated Instrumentation Test (`LatencyBenchmark.kt`).
- **Mock Latency**: `VirtualRobotPort` connects to `MockRP2040`. `MockRP2040` includes a 5ms `Thread.sleep()` to simulate firmware processing time.
- **Reporting**: Results are logged in Markdown format and appended to this document under the [Results](#results) section.

## 4. Results

### Benchmark Results (10000 iterations)
 
Success Rate: 100.00% (10000/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 1.810     | 0.040    | 18.318   | 10.898   |
| M2: Handling/Serialization | 0.031     | 0.004    | 0.883    | 0.048    |
| M3: Transport Out          | 1.618     | 0.218    | 14.032   | 2.602    |
| M4: Robot + Transit In     | 5.568     | 5.136    | 13.286   | 5.892    |
| M5: Buffer Processing      | 8.336     | 0.653    | 28.031   | 11.336   |
| M6: Wake-up Lag            | 0.781     | 0.094    | 10.465   | 1.278    |
| M7: App Logic              | 2.179     | 0.224    | 10.723   | 3.390    |
| Total RTT                  | 20.323    | 6.654    | 46.107   | 32.139   |

#### Byte array logging optimization

Success Rate: 100.00% (10000/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) | 
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 1.647     | 0.042    | 15.256   | 10.955   |
| M2: Handling/Serialization | 0.046     | 0.004    | 1.004    | 0.065    |
| M3: Transport Out          | 1.660     | 0.139    | 10.018   | 2.549    |
| M4: Robot + Transit In     | 5.632     | 5.161    | 10.411   | 5.986    |
| M5: Buffer Processing      | 1.405     | 0.176    | 5.982    | 1.911    |
| M6: Wake-up Lag            | 0.871     | 0.073    | 5.523    | 1.450    |
| M7: App Logic              | 2.052     | 0.164    | 10.285   | 3.127    |
| Total RTT                  | 13.315    | 5.944    | 35.268   | 23.150   |

#### PacketBuffer optimization

Success Rate: 100.00% (10000/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) | 
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 1.461     | 0.029    | 13.238   | 10.902   |
| M2: Handling/Serialization | 0.030     | 0.003    | 1.214    | 0.044    |
| M3: Transport Out          | 1.580     | 0.161    | 7.473    | 2.447    |
| M4: Robot + Transit In     | 5.630     | 5.131    | 16.571   | 5.985    |
| M5: Buffer Processing      | 0.898     | 0.128    | 3.920    | 1.237    |
| M6: Wake-up Lag            | 0.824     | 0.085    | 16.368   | 1.414    |
| M7: App Logic              | 1.979     | 0.147    | 7.192    | 3.132    |
| Total RTT                  | 12.402    | 5.811    | 37.138   | 22.229   |

#### Updated android2PiWriter to handle the case where the command is already defined when loop starts

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