# Android ROS Connectivity Smoke Test

Verifies that the Android app can connect to `rosbridge`, subscribe to a topic, and publish a topic.

---

## Prerequisites

- ROS 2 installed on the PC (Linux or WSL2). Supported distros: `humble`, `iron`, `jazzy`, `rolling`
- `rosbridge_suite` installed:
  ```bash
  sudo apt install ros-$ROS_DISTRO-rosbridge-suite
  ```
  e.g. for Jazzy (recommended) or Humble:
  ```bash
  sudo apt install ros-jazzy-rosbridge-suite
  # or
  sudo apt install ros-humble-rosbridge-suite
  ```
- If using WSL2, allow port 9090 through the Windows firewall (run as Administrator):
  ```powershell
  netsh advfirewall firewall add rule name="ROS Bridge 9090" dir=in action=allow protocol=TCP localport=9090
  ```
- Android device and PC on the same LAN
- Android app built and installed on the device

---

## PC Setup

Open three terminals, each sourced with your ROS 2 environment (`source /opt/ros/$ROS_DISTRO/setup.bash`).

**Terminal 1 — start rosbridge:**
```bash
ros2 launch rosbridge_server rosbridge_websocket_launch.xml
```
Expected output:
```
[INFO] [rosbridge_websocket]: Rosbridge WebSocket server started on port 9090
```

**Terminal 2 — publish a test topic to Android:**
```bash
ros2 topic pub /test_from_ros std_msgs/msg/String "data: 'hello from ros'" --rate 1
```

**Terminal 3 — verify Android is publishing:**
```bash
ros2 topic echo /test_from_android std_msgs/msg/String
```

---

## Android Setup

1. Build and run the `rosbridge-test` app on the device.
2. Enter the PC's LAN IP address in the `ROS PC IP` input field.
3. Tap **Run Smoke Test** — the app will automatically:
    - Connect to `ws://<ROS_PC_IP>:9090`
    - Subscribe to `/test_from_ros`
    - Publish one message to `/test_from_android`

---

## Expected Logs

**Android logcat — pass:**
```
[RosBridge] Connected to ws://192.168.x.x:9090
[RosBridge] Subscribed to /test_from_ros
[RosBridge] Received: hello from ros
[RosBridge] Published to /test_from_android
[SmokeTest] PASS: connect=OK  subscribe=OK  publish=OK
```

**Terminal 3 (PC) — pass:**
```
data: hello from android
```

**Android logcat — failure examples:**
```
[RosBridge] ERROR: Connection failed — check PC IP and rosbridge is running
[RosBridge] ERROR: No message received on /test_from_ros — check Terminal 2 is publishing
[RosBridge] ERROR: Publish failed — WebSocket not connected
[SmokeTest] FAIL: connect=FAIL  subscribe=SKIP  publish=SKIP
```

---

## Pass Criteria

| Step | Pass condition |
|---|---|
| Connect | WebSocket handshake succeeds, no error log |
| Subscribe | At least one message received on `/test_from_ros` |
| Publish | Message appears in Terminal 3 on the PC |