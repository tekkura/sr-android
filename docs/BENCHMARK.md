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
| **M3: Transport Out**          | $T4 - T3$   | Latency from the phone to the robot (includes driver/mock transit).           |
| **M4: Robot + Transit In**     | $T5 - T4$   | Simulator processing time + Response transit back to phone.                   |
| **M5: Buffer Processing**      | $T6 - T5$   | Time spent inside `PacketBuffer` parsing the response.                        |
| **M6: Wake-up Lag**            | $T7 - T6$   | Delay between the packet being queued and the manager thread waking up.       |
| **M7: App logic**              | $T8 - T7$   | Time taken to update internal state models and notify UI publishers.          |
| **Total RTT**                  | $T8 - T1$   | The full round-trip latency as experienced by the application.                |

## 3. Methodology

- **Sample Size**: 10,000 iterations per benchmark run.
- **Warm-up**: 100 iterations are discarded to allow for JIT optimization.
- **Environment**: Dedicated Instrumentation Test (`LatencyBenchmark.kt`).
- **Mock Latency**: `MockSerialTransport` is configured with a 1ms artificial delay for M3 and M5 to simulate OS USB stack overhead.
- **Reporting**: Results are logged in Markdown format and appended to this document under the [Results](#results) section.

## 4. Results

### Benchmark Results (10000 iterations)

#### Initial measurements

Success Rate: 98.12% (9812/10000) 
 
| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 1.498     | 0.051    | 9921.545 | 0.939    |
| M2: Handling/Serialization | 0.131     | 0.013    | 4.814    | 0.318    |
| M3: Transport Out          | 1.665     | 0.156    | 23.518   | 2.793    |
| M4: Robot + Transit In     | 5.562     | 5.131    | 14.091   | 5.916    |
| M5: Buffer Processing      | 7.236     | 0.568    | 24.257   | 9.819    |
| M6: Wake-up Lag            | 0.751     | 0.104    | 8.657    | 1.362    |
| M7: App Logic              | 2.019     | 0.171    | 15.904   | 3.257    |
| Total RTT                  | 18.863    | 6.430    | 9949.598 | 22.232   |

#### Byte array logging optimization

Success Rate: 98.59% (9859/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 0.445     | 0.030    | 13.782   | 0.897    |
| M2: Handling/Serialization | 0.114     | 0.009    | 7.307    | 0.238    |
| M3: Transport Out          | 1.637     | 0.129    | 12.729   | 2.712    |
| M4: Robot + Transit In     | 5.620     | 5.116    | 16.563   | 6.027    |
| M5: Buffer Processing      | 1.263     | 0.140    | 10.629   | 1.899    |
| M6: Wake-up Lag            | 0.828     | 0.068    | 10.036   | 1.529    |
| M7: App Logic              | 1.870     | 0.146    | 13.960   | 3.086    |
| Total RTT                  | 11.778    | 5.717    | 28.377   | 14.651   |

#### PacketBuffer optimization

Success Rate: 98.92% (9892/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 0.432     | 0.032    | 11.496   | 0.802    |
| M2: Handling/Serialization | 0.109     | 0.009    | 1.788    | 0.214    |
| M3: Transport Out          | 1.567     | 0.157    | 7.825    | 2.413    |
| M4: Robot + Transit In     | 5.606     | 5.135    | 10.547   | 5.934    |
| M5: Buffer Processing      | 0.837     | 0.106    | 9.288    | 1.198    |
| M6: Wake-up Lag            | 0.774     | 0.090    | 8.612    | 1.225    |
| M7: App Logic              | 1.972     | 0.148    | 6.632    | 2.984    |
| Total RTT                  | 11.297    | 5.765    | 32.862   | 13.372   |

#### Updated android2PiWriter to handle the case where the command is already defined when loop starts

Success Rate: 98.93% (9893/10000)

| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |
|:---------------------------|:----------|:---------|:---------|:---------|
| M1: Outbound Queueing      | 0.398     | 0.039    | 3.843    | 0.806    |
| M2: Handling/Serialization | 0.116     | 0.017    | 7.311    | 0.220    |
| M3: Transport Out          | 1.578     | 0.245    | 9.660    | 2.412    |
| M4: Robot + Transit In     | 5.604     | 5.152    | 17.768   | 5.939    |
| M5: Buffer Processing      | 0.865     | 0.150    | 10.946   | 1.201    |
| M6: Wake-up Lag            | 0.781     | 0.077    | 12.094   | 1.238    |
| M7: App Logic              | 1.975     | 0.179    | 6.552    | 2.956    |
| Total RTT                  | 11.316    | 5.887    | 30.910   | 13.340   |