# Migration Guide: USB-Serial Refactor & Instrumentation Tests

This guide defines the requirements and implementation plan for the **InstrumentationTests** milestone. The primary goal is to refactor the USB-serial communication layer for better abstraction and performance, validated by a robust, hardware-independent testing environment.

## Milestone Goal
Refactor the low-level USB-serial logic into a robust, high-level API using a dedicated buffer abstraction and sealed class commands. This structure will be verified through targeted unit tests and full-loop instrumentation tests to ensure reliability and a round-trip latency of < 20ms.

---

## Implementation Plan

### Phase 1: Serial Command Abstraction & Unit Testing
The goal of this phase is to isolate the framing logic and provide a type-safe API for serial commands.

1. **PacketBuffer Abstraction**:
   - Implement a `PacketBuffer` class as an abstraction over raw byte arrays.
   - Entrust this class with the responsibility of command parsing, identifying start/stop markers, and extracting payloads.

2. **Command Sealed Classes**:
   - Migrate existing command enums (e.g., `AndroidToRP2040Command`) to a sealed class hierarchy or create new sealed classes if old enumerations cannot be changed without breaking existing logic.
   - These classes serve as high-level abstractions over serial commands, containing the internal logic to convert to/from raw byte arrays and providing convenient property getters for payload data.

3. **Logic Verification**:
   - Implement comprehensive unit tests for `PacketBuffer` and the new Command sealed classes.
   - Ensure the entire serializing/deserializing logic is covered, including edge cases like partial packets and malformed data.

### Phase 2: Integration Testing & Performance Optimization
The goal of this phase is to verify the entire communication stack and reach latency targets.

4. **Full-Loop Instrumentation Tests**:
   - Create end-to-end instrumentation tests that utilize the entire communication logic.
   - Use a mocked transport layer to simulate robot hardware, verifying that commands sent from the phone result in the correct state updates.

5. **Performance Profiling & Latency Optimization**:
   - Identify bottlenecks in the communication stack using the Android Profiler and timing logs.
   - Optimize round-trip latency
   - **Validation**: Confirm and document that the round-trip latency is significantly reduced (goal < 20ms).

---

## Acceptance Criteria

### Technical AC
- The USB-serial layer is refactored to use the `PacketBuffer` abstraction and sealed class command hierarchy.
- A comprehensive test suite verifies all command serialization and deserialization logic.
- Full-loop instrumentation tests pass reliably without physical hardware.
- Communication round-trip time is profiled and documented to be < 20ms.

### Process AC
- Documentation in `README.md` is updated to describe the new `PacketBuffer` API and instructions for running automated tests.
- All new features or protocol changes include corresponding unit or instrumentation tests.
