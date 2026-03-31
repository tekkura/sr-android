# Android ROS Connectivity Smoke Test

Verifies that the Android app can connect to `rosbridge`, subscribe to a topic, and publish a topic.

---

## Prerequisites

- Either:
  - ROS 2 installed on the PC (Linux or WSL2), or
  - Docker / Docker Compose available so the provided project-local rosbridge container can be used
- If using WSL2, allow port 9090 through the Windows firewall (run as Administrator):
  ```powershell
  netsh advfirewall firewall add rule name="ROS Bridge 9090" dir=in action=allow protocol=TCP localport=9090
  ```
  Then forward port 9090 from Windows to WSL2 (run as Administrator):
  ```powershell
  # Get the WSL2 IP
  wsl hostname -I
  # Use the first IP returned (e.g. 172.x.x.x)
  netsh interface portproxy add v4tov4 listenport=9090 listenaddress=0.0.0.0 connectport=9090 connectaddress=<WSL_IP>
  ```
  > **Note:** The WSL2 IP may change on reboot. Re-run `wsl hostname -I` and update the rule if the Android app can no longer connect.
- Android device and PC on the same LAN
- Android app built and installed on the device

---

## PC Setup

You can use either a native ROS install or the provided Docker setup.

### Option A: Docker (recommended for isolated setup)

Run these commands from the repo root.
The provided compose file already pins `ROS_DISTRO=kilted`, so no extra host environment setup is required.
The rosbridge service publishes TCP port `9090` to the host, so the Android phone should connect to
the PC's LAN IP on port `9090`.

**One-command end-to-end smoke test:**
```bash
./scripts/ros-smoke/run.sh
```

This wrapper will:
- start the Docker rosbridge/pub/echo services
- install the Android app
- launch the app with adb intent extras
- wait for the automatic `SmokeTest READY/FAIL ...` Logcat summary
- fail if the Android publish is not observed on `/test_from_android`

**All-in-one — start all services together:**
```bash
sudo docker compose -f docker/rosbridge/docker-compose.yml up --build
```

Or start each service in a separate terminal for easier debugging:

**Terminal 1 — start rosbridge:**
```bash
docker compose -f docker/rosbridge/docker-compose.yml up --build rosbridge
```

**Terminal 2 — publish a test topic to Android:**
```bash
docker compose -f docker/rosbridge/docker-compose.yml run --rm pub
```

**Terminal 3 — verify Android is publishing:**
```bash
docker compose -f docker/rosbridge/docker-compose.yml run --rm echo
```

### Option B: Native ROS 2 install

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

1. Build and run the `rosbridgeTest` app on the device.
2. Enter the PC's LAN IP address in the `ROS PC IP` input field.
3. Tap **Run Smoke Test** — the app will automatically:
    - Connect to `ws://<ROS_PC_IP>:9090`
    - Subscribe to `/test_from_ros`
    - Publish one message to `/test_from_android`

---

## Expected Logs

**Android logcat — pass:**
```
I/SmokeTest: connect=PASS
I/SmokeTest: subscribe=PASS
I/SmokeTest: publish=SENT
I/SmokeTest: READY connect=PASS subscribe=PASS publish=SENT
```

**Android logcat — failure examples:**
```
[RosBridge] ERROR: Unable to resolve host "192.168.x.xx": No address associated with hostname
[RosBridge] ERROR: Connection reset
[RosBridge] ERROR: Failed to parse message
```

**Terminal 3 (PC) — pass:**
```
data: <sent_message>
```

When using the wrapper, the default message is:
```
data: hello_from_android
```

**Terminal 1 (PC) — failure example:**
```
[rosbridge_websocket]: publish: Cannot infer topic type for topic /test_from_android as it is not yet advertised
```

---

## Pass Criteria

| Step | Pass condition |
|---|---|
| Connect | WebSocket handshake succeeds, no error log |
| Subscribe | At least one message received on `/test_from_ros` |
| Publish | App reports `publish=SENT` and the message appears on `/test_from_android` |
