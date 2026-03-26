# Android ROS 2 Integration Path

## Deployment Architecture

The robot hardware consists of an Android phone and an RP2040 MCU driving the wheels. The RP2040 connects to the phone over serial (USB OTG). ROS 2 runs on a nearby PC on the same LAN. The Android phone connects to the PC over local Wi-Fi. `rosbridge` runs on the PC and exposes a WebSocket endpoint that the Android app connects to as a plain client.

Two candidate integration paths were evaluated for the milestone:

- **`rosbridge`** – A WebSocket/JSON bridge server running on the ROS PC. Android connects as a plain WebSocket client using the rosbridge v2 protocol.
- **`ros2_java`** – Native Java/JNI bindings that cross-compile ROS 2 client libraries (rcljava) into the Android APK, allowing the device to be a first-class ROS 2 node.

## Decision

**Selected path: `rosbridge` (WebSocket/JSON)**

---

## Evaluation

### 1. Android Build and Packaging Complexity

| | rosbridge | ros2_java |
|---|---|---|
| Android-side build | Zero. Standard OkHttp/Ktor WebSocket client; no NDK, no CMake. | Very high. Requires cross-compiling the entire ROS 2 C layer (rcl, rmw, DDS vendor) against the Android NDK, colcon + Gradle integration, and a custom ament_gradle_plugin. |
| ROS PC setup | `sudo apt install ros-$DISTRO-rosbridge-suite`; one launch file. | None (PC runs ROS 2 on Linux or WSL2). |
| APK size impact | Negligible (≤ 50 KB WebSocket library). | Significant; bundled `.so` libraries for rcl + DDS vendor add several MB per ABI. |
| CI/CD | No change to existing Android build pipeline. | Requires a specialized build environment with NDK; blocks standard AGP/Gradle toolchain. |

### 2. Runtime Reliability and Maintenance Risk

**rosbridge** is actively maintained by the Robot Web Tools working group and released in every ROS 2 distro (Humble, Iron, Jazzy, Rolling). The protocol is stable and widely used.

**ros2_java** has had no meaningful new commits to the `ros2-java/ros2_java` repository in several years. The Android examples (`ros2_android_examples`) target outdated NDK and DDS versions. Taking on ros2_java introduces an unmaintained dependency that we would effectively own.

### 3. Message Throughput and Latency

rosbridge serializes messages as JSON over WebSocket. Latency has not been measured for this project. The built-in `delay_between_messages` default of 10 ms ([rosbridge issue #203](https://github.com/RobotWebTools/rosbridge_suite/issues/203)) means per-message latency is at minimum 10 ms unless explicitly set to 0 in the launch configuration.

Binary sensor payloads (e.g., images) would be costly to serialize as JSON, but `abcvlib` streams object-detection results, not raw camera frames, so this is not a bottleneck today.

### 4. Debuggability and Developer Workflow

**rosbridge** wins clearly here:
- The ROS side uses standard `ros2 topic echo`, `ros2 topic hz`, `rqt` — all familiar tools.
- The Android side sends and receives plain JSON strings; any HTTP client, curl, or browser-based tool can inspect traffic.
- No special environment setup is needed to build or run the Android app.

**ros2_java** would require the full ROS 2 toolchain on the developer machine to inspect or trace node behavior, and the NDK build environment to make any library changes.

---

## Decision Rationale Summary

| Criterion | rosbridge | ros2_java |
|---|:---:|:---:|
| Android build complexity | ✅ Minimal | ❌ Very high |
| Maintenance risk | ✅ Actively maintained | ❌ Effectively unmaintained |
| Latency for current use case | ⚠️ ≥10 ms/msg | ✅ Marginally better |
| Debuggability | ✅ Standard ROS tooling | ⚠️ Requires ROS env |
| Milestone deliverability | ✅ | ❌ |

---

## Known Risks and Constraints (rosbridge)

1. **`delay_between_messages` throttle.** The rosbridge default of 10 ms per outgoing message must be set to 0 in the launch configuration, or latency will compound under any meaningful publish rate.
2. **JSON throughput ceiling.** If a future feature requires streaming raw sensor data (e.g., camera frames) over ROS from robot to Android, JSON-over-WebSocket will be a bottleneck. Mitigation: use compressed binary transport (e.g., CBOR bridge or WebRTC data channel) or move those topics out-of-band.
3. **Single point of failure.** If the rosbridge server crashes, Android loses all ROS connectivity. Implement reconnect logic on the Android client with exponential backoff.
4. **Network dependency.** The Android device must be on the same LAN as the ROS PC. This is already the case for the current hardware setup.

---

## Rejected Alternative: `ros2_java`

ros2_java is rejected for this milestone on the following grounds:

- The Android cross-compile toolchain is not maintained against current NDK (r25+) or AGP (8+) versions, making it impossible to integrate into the existing build pipeline without owning the dependency.
- There is no evidence of recent community adoption or production use on Android.
- The latency and protocol fidelity advantages over `rosbridge` are not material for `abcvlib`'s current message types and rates.
- The setup cost would consume a disproportionate share of milestone time with no functional benefit over rosbridge in the near term.
---

## References

- rosbridge_suite: <https://index.ros.org/r/rosbridge_suite/>
- rosbridge source: <https://github.com/RobotWebTools/rosbridge_suite>
- rosbridge issue #203 (`delay_between_messages`): <https://github.com/RobotWebTools/rosbridge_suite/issues/203>
- ros2_java: <https://github.com/ros2-java/ros2_java>
- ROS 2 client libraries overview: <https://docs.ros.org/en/rolling/Concepts/Basic/About-Client-Libraries.html>