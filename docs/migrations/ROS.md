# Migration Guide: Android ROS Integration via rosbridge

## Summary

abcvlib has no Android-side ROS connectivity. This migration adds a standalone `rosbridgeTest` app that connects the Android phone to a ROS 2 system running on a nearby PC over local Wi-Fi using the rosbridge WebSocket protocol. The app verifies the integration path selected in [`docs/ros/Integration_Path.md`](../../docs/ros/Integration_Path.md) by running a connect → subscribe → publish smoke test as documented in [`docs/ros/Smoke_Test.md`](../../docs/ros/Smoke_Test.md). The milestone also includes the repo-local Docker rosbridge setup and one-command smoke-test wrapper used to make the flow reproducible for reviewers.

## Objective

- Create a standalone `rosbridgeTest` app with a minimal `RosBridgeClient` that connects to rosbridge over WebSocket, subscribes to a topic, and publishes a topic.
- Provide a `MainActivity` UI that accepts the ROS PC IP address at runtime and displays explicit smoke-test step status for connect, subscribe, and publish-send progress.
- Wire the new app into the existing multi-module build configuration.
- Provide a repo-local Docker rosbridge setup and scripted smoke-test wrapper so reviewers can run the end-to-end flow without a host ROS install.

---

## Scope

### New Files

#### `docs/ros/Integration_Path.md`
Architecture Decision Record for the rosbridge vs ros2_java evaluation. Documentation only.

#### `docs/ros/Smoke_Test.md`
Run instructions for the smoke test. Documentation only.

#### `docker/rosbridge/`
Project-local Docker setup for rosbridge/pub/echo smoke-test services.

#### `scripts/ros-smoke/run.sh`
One-command wrapper that starts the Docker services, installs the app, launches the smoke test, and verifies the Android publish on the PC side.

#### `apps/rosbridgeTest/`

```
apps/rosbridgeTest/
├── build.gradle.kts
├── AndroidManifest.xml
└── src/main/
    ├── java/
    │   ├── Rosbridgeclient.kt
    │   └── MainActivity.kt
    └── res/
        └── layout/
            └── activity_main.xml
```

Any additional resource files required to support the UI (strings, colors, themes) are in scope.



---

### Modified Files

#### `apps-config.json`
Add the `rosbridgeTest` module entry to include it in the multi-module build configuration.

#### `libs.versions.toml`
Add the WebSocket networking library. Suggested: OkHttp (`com.squareup.okhttp3:okhttp:$latest`), or Ktor WebSocket client if Ktor is already used elsewhere in the project.

OkHttp is preferred if no existing Ktor dependency is present - it has no additional transitive dependencies and is already a transitive dependency of many Android libraries.

---

## Rules

- `RosBridgeClient` must not perform network operations on the main thread - use coroutines (`Dispatchers.IO`).
- UI state updates must be dispatched back to the main thread.
- No changes to `AbcvlibActivity` or any existing library module in this milestone.
- `AndroidManifest.xml` changes are limited to `INTERNET` permission plus the cleartext traffic allowance required for local `ws://` rosbridge connections used by this milestone smoke test.

---

## Test / Acceptance Criteria

> Testing is performed at runtime. Reviewers are expected to follow the setup steps in [`docs/ros/Smoke_Test.md`](../../docs/ros/Smoke_Test.md) and evaluate the results directly on device.

| Criterion | Expected |
|---|---|
| App builds without error | `./gradlew :rosbridgeTest:assembleDebug` succeeds |
| Connect step | App connects to rosbridge on a LAN device running `ros2 launch rosbridge_server rosbridge_websocket_launch.xml` |
| Subscribe step | App receives at least one message from `/test_from_ros` within 5 seconds |
| Publish step | App reaches `READY connect=PASS subscribe=PASS publish=SENT`, and the message is observed on PC via `ros2 topic echo /test_from_android` or the repo smoke-test wrapper |
| Failure handling | App shows a clear error message when rosbridge is unreachable |
| No main thread blocking | UI remains responsive during the entire test flow |
| Logs | Logcat clearly shows connect/subscribe step PASS/FAIL states and the app-side `READY ... publish=SENT` status before wrapper-verified end-to-end PASS |

---

## References

- [`docs/ros/Integration_Path.md`](../../docs/ros/Integration_Path.md) - ADR for rosbridge selection
- [`docs/ros/Smoke_Test.md`](../../docs/ros/Smoke_Test.md) - PC and Android run instructions
- rosbridge v2 protocol: <https://github.com/RobotWebTools/rosbridge_suite/blob/ros2/ROSBRIDGE_PROTOCOL.md>
- OkHttp: <https://square.github.io/okhttp/>
- Issue #150, Issue #151
