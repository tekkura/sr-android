# OIST Smartphone Robot Android Framework
[![](https://jitpack.io/v/oist/smartphone-robot-android.svg)](https://jitpack.io/#oist/smartphone-robot-android)

The main purpose of this repo is to host the abcvlib development. This is the library governing the OIST Smartphone Robot Android Framework. The framework is designed to provide a simple and easy to use interface for developing Android applications for the OIST Smartphone Robot.

This repo itself is an Android project, so opening the root of the repo in Android Studio will provide all build tools necessary to build the library and the example applications located in the apps directory.

Although abcvlib can be used as an external dependency (see [Using abcvlib as an external dependency](#using-abcvlib-as-an-external-dependency)), it is likely easier to get to know the API by looking at the example applications. The example applications are located in the apps directory and are designed to be simple examples of how to use core features of the library.

## PR Workflow

See `docs/PR_WORKFLOW.md` for the required milestone branch naming, review process, and merge flow.

## Building abcvlib and the Example Applications

### Prerequisites
The following is a list of tools needed to build either abcvlib or any of the demo applications.

1. Java opensdk v17 or higher, with environmental variable JAVA_HOME set to the root of this sdk e.g. `/usr/lib/jvm/java-1.17.0-openjdk-amd64` (a requirement by modern Android gradle plugin versions)
2. git
3. Android SDK Tools (If you prefer the command line, you can use the Android Command Line Tools and avoid using Android Studio)
4. (Optional) Android Studio
5. (Optional) Docker can be used to provide all the above tools in a single ready-made image. See the [Docker README](docker/README.md) for more information.

### Building and Installing APKs
Start by cloning the repository

From here you have two choices. You can either use Android Studio or the command line to build the project.

#### 1.) Using Android Studio
1. Open the root of the repository in Android Studio
2. Build the project by clicking the hammer icon in the toolbar. If this fails, [this](https://github.com/oist/smartphone-robot-android/issues/39) may help.
3. Choose one of the build targets (backAndForth, basicSubscriber, basicAssembler) from the dropdown menu in the toolbar
4. Ensure you have a smartphone connected to your computer via adb, see [Pairing with a smartphone via wireless debugging](#pairing-with-a-smartphone-via-wireless-debugging) for more information
5. Click the green play button in the toolbar to install the APK on your device

#### 2.) Using the Command Line
There is an automated script to perform most of what is listed below, but it has only been tested on Ubuntu 22.04.
This script lies [here](https://github.com/topherbuckley/cmdline-tools-install/blob/main/install-cmdline-tools)
Manually, this does the following:

1. Download and unzip the Android Command Line Tools from the [Android Developer website](https://developer.android.com/studio#command-line-tools-only)
2. Unzip this to a location of your choice (e.g. ~/android_sdk)
3. Follow the silly manual directory restructuring steps [here](https://developer.android.com/tools/sdkmanager)
4. Install platform-tools, build-tools, and Android platform (`./sdkmanager "platform-tools" "platforms;android-30" "build-tools;33.0.1"`)
5. Accept all licenses (`y | ./sdkmanager --licenses`)
6. Set ANDROID_HOME env variable (`export ANDROID_HOME=~/android_sdk`)
7. Add platform-tools to your PATH (`export PATH=$PATH:$ANDROID_HOME/platform-tools`)

From here you can start building the project. There is also a terminal application to automate the below steps, and this is found at ./bi (build and install).

1. Navigate to the root of the repository
2. Run `./gradlew assembleDebug` to build the project (it will take a few minutes the first time as it downloads all the necessary dependencies)
3. You can build individual APKs by running `./gradlew <app_name>:assembleDebug` where `<app_name>` is one of the following: `backAndForth`, `basicSubscriber`, `basicAssembler`
4. You may need to uninstall the previous version of the APK before installing the new one. You can do this by running `adb uninstall jp.oist.abcvlib.backandforth` where `jp.oist.abcvlib.backandforth` is the package name of the APK you want to uninstall
5. Install the APK on your device by running `adb install -r <path_to_apk>` where `<path_to_apk>` is the path to the APK you just built e.g. `./apps/backAndForth/build/outputs/apk/debug/backAndForth-debug.apk`
6. Run the APK on your device via `adb shell am start -n jp.oist.abcvlib.backandforth/.MainActivity`

## Architecture

### State Variables
1. Camera
  a. Raw Images
  b. Hybrid Sensors (QR Code Boolean, coords of target, etc.)
2. Microphone
  a. Raw audio samples
3. Spatial sensors
  a. Accelerometer
  b. Gyroscope
  c. GPS
  d. Hybrid Sensors combining these
4. Wheels
  a. Raw encoder counts
  b. Hybrid Sensors (Relative position, speed, acceleration)
5. Power
  a. Internal battery voltage
  b. Charger voltage
  c. Wireless coil voltage

### Actions
1. Low Level Controller
  a. Direct control of motors via `outputs.setWheelOutput(float left, float right, boolean leftBrake, boolean rightBrake)`
2. Mid-level controllers
  b. e.g. See basicAssembler's ActionSpace
  c. Balance, Turn Left/Right, Move Forward/Backward 10mm

## Subscriber/Publisher Architecture
All state variables are published by the robot and can be subscribed to either directly within your MainActivity class (See apps/basicSubscriber) or via a TimeStepDataBuffer (See apps/basicAssembler) for examples of each. The following diagram shows the architecture of this system.
![Subscriber/Publisher Architecture](./media/publisher-subscriber.png)

## Communication Protocol

The communication between the Android phone and the robot firmware utilizes a custom packet-based protocol over USB-Serial.

### Packet Structure
Packets are framed with start and stop markers to ensure integrity during streaming:
- **START Marker**: `0xFE`
- **Command Type**: 1 byte identifying the command.
- **Payload Size**: 2 bytes (Little Endian) specifying the data length.
- **Payload**: Variable length data.
- **STOP Marker**: `0xFF`

### PacketBuffer API
The `PacketBuffer` class is the primary abstraction for parsing incoming serial data. It manages an internal state machine to handle partial packets and multiple packets received in a single transmission.

#### Usage
```kotlin
val packetBuffer = PacketBuffer()

// In the serial read callback:
packetBuffer.consume(incomingBytes) { result ->
    when (result) {
        is PacketBuffer.ParseResult.ReceivedPacket -> {
            val command = result.command
            // Handle the parsed RP2040IncomingCommand
        }
        is PacketBuffer.ParseResult.ReceivedErrorPacket -> {
            // Handled malformed packet or framing error. 
            // The parser will automatically try to resynchronize with the next START marker.
        }
        is PacketBuffer.ParseResult.Overflow -> {
            // Internal buffer limit reached. 
            // The parser will clear its state and wait for a fresh START marker.
        }
        is PacketBuffer.ParseResult.NotEnoughData -> {
            // Incomplete packet; the parser retains current state and waits for more bytes.
        }
    }
}
```

## Overview of API
Three demo applications exemplify the basic use of the API:
1. backAndForth
  a. Intentionally VERY bare-bones as a Hello World app for communicating between Android & Robot
  b. No feedback, just output via outputs.setWheelOutput
2. basicSubscriber
  a. Adding a minimal subscribe+read operation to all possible publishers.
  b. onCreate-->usbInitialize-->onSerialReady-->initializeOutputs-->onOutputsReady-->abcvlibMainLoop starts.
  c. Careful! There are several chronological dependencies in the initialization, so changing the order of any of these should be done with caution.
  d. Note: every onXXX method is called from a separate thread, so synchronization on shared variables can be messy
3. basicAssembler
  a. Assembles all subscribed data into TimeStepData objects (holder for all sampled state info within a single timestep)
  b. Takes the burden of synchronizing all the threads away. (See TimeStepDataBuffer)
    i. Synchronized get/set methods
    ii. Circular buffer for writing data, with read data separately
  c. Adds a high-level RL framework (StateSpace, ActionSpace, etc.)

## Testing

The framework includes a comprehensive test suite to ensure reliability and performance.

### 1. Unit Tests
These tests verify individual components (e.g., packet parsing, command serialization) and run on your local machine.

```bash
./gradlew :abcvlib:testDebugUnitTest
```

### 2. Instrumentation Tests
These tests verify end-to-end communication using a mocked transport layer to simulate robot hardware. They require a connected Android device or emulator.

```bash
./gradlew :abcvlib:connectedDebugAndroidTest
```

### 3. Latency Benchmark
A specialized test to measure communication round-trip time and identify bottlenecks. For details on the methodology and metrics, see [docs/BENCHMARK.md](docs/BENCHMARK.md).

```bash
# Run the benchmark
./gradlew :abcvlib:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=jp.oist.abcvlib.util.latency.LatencyBenchmark"

# Export results to docs/BENCHMARK.md
./util/pull_benchmark.bat  # (Windows)
./util/pull_benchmark.sh   # (Linux/macOS)
```

## Pairing with a smartphone via wireless debugging
1. Ensure your smartphone is connected to the same network as your computer
2. Enable wireless debugging on your smartphone by following the instructions [here](https://developer.android.com/studio/run/device#wireless)
3. Run `adb pair <ip_address>:<port>` where `<ip_address>` is the IP address of your smartphone and `<port>` is the port number displayed on your smartphone
4. Run `adb connect <ip_address>:<port>` to connect to your smartphone

## Installing/Rolling back APK versions
APK builds are stored in GitHub Releases.
1. Download any versioned release in the [releases page](https://github.com/oist/smartphone-robot-android/releases)
2. Install the APK on your device by running `adb install -r <path_to_apk>`. You may also find the following adb flags helpful:

    - -r: replace existing application
    - -t: allow test packages
    - -d: allow version code downgrade (debuggable packages only)
    - -g: grant all runtime permissions

   (Note if you see `adb: failed to install backAndForth-debug.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package jp.oist.abcvlib.backandforth signatures do not match previously installed version; ignoring!]` you may need to uninstall the previous version of the APK manually from the phone)

3. Run the APK on your device via `adb shell am start -n jp.oist.abcvlib.<AppName>/.MainActivity` where `<AppName>` is the name of the APK you just installed (e.g. `backandforth`)
4. Alternatively to step 3, you can open the app from the app drawer on your device


## Using abcvlib as an external dependency
`abcvlib` is intended to be consumed publicly via JitPack.

Add the JitPack repository to your Gradle build:

```gradle
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.oist.smartphone-robot-android:abcvlib:v2.0.2")
}
```

Notes:

- Public consumers do not need GitHub Packages credentials to depend on `abcvlib` through JitPack.
- Repository-owned publishing workflows may still use GitHub Packages separately from this consumer setup.
- JitPack consumption uses the repository/module coordinate `com.github.oist.smartphone-robot-android:abcvlib:<tag>`.
- This consumer coordinate is separate from the repository-owned Maven publication metadata used elsewhere in the build.
- Git release tags use the form `vX.Y.Z`.
- The published Maven version is normalized from `vX.Y.Z` to `X.Y.Z` by the checked-in build logic.
