# Testing

The framework includes unit tests, instrumentation tests, and a latency
benchmark for the communication path.

## Unit Tests

Unit tests verify individual components such as packet parsing and command
serialization. They run on the local machine:

```bash
./gradlew :abcvlib:testDebugUnitTest
```

## Instrumentation Tests

Instrumentation tests verify end-to-end communication using a mocked transport
layer to simulate robot hardware. They require a connected Android device or
emulator:

```bash
./gradlew :abcvlib:connectedDebugAndroidTest
```

## Latency Benchmark

The latency benchmark measures communication round-trip time and helps identify
bottlenecks. For methodology and metrics, see
[Communication Latency Benchmark Specification](BENCHMARK.md).

Run the benchmark and sync the published artifacts:

```bash
./gradlew :abcvlib:runLatencyBenchmark
```

Run against attached hardware instead of the virtual simulator:

```bash
./gradlew :abcvlib:runLatencyBenchmark -PuseHardware=true
```

Benchmark outputs are published under `docs/benchmarks/latency/`.
