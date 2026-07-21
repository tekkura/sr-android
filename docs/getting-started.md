# Getting Started

## 1. Set Up Tools

- Git: [GitHub: Set up Git](https://docs.github.com/en/get-started/git-basics/set-up-git)
- Repository checkout: [GitHub: Cloning a repository](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository)
- Python 3.9 or newer: [Python downloads](https://www.python.org/downloads/)
- Full JDK 17 or newer, not just a JRE/runtime. `JAVA_HOME` must point to the
  JDK root and `JAVA_HOME/bin/javac` must exist. See
  [Troubleshooting](troubleshooting.md#java_home-is-missing-or-wrong) if Gradle
  cannot find a Java compiler.

Choose one Android setup path:

- Android Studio: [Android Studio install guide](https://developer.android.com/studio/install)
- Command line:
  [Android SDK command-line tools setup](android-sdk-command-line-tools-setup.md) and
  [JDK setup](https://developer.android.com/build/jdks)

## 2. Check The App CLI

From the repository root:

On macOS/Linux:

```bash
./app --help
```

On Windows:

```bat
app.bat --help
```

If this fails with a Python version error, install or select Python 3.9 or newer.

## 3. Connect A Phone To The Computer

Official Android instructions:

- [Run apps on a hardware device](https://developer.android.com/studio/run/device)
- [Wireless debugging](https://developer.android.com/studio/run/device#wireless)

For wireless debugging from the command line:

1. Enable wireless debugging on the phone.
2. Pair with the phone:

   ```bash
   adb pair <ip_address>:<pairing_port>
   ```

3. Connect to the phone:

   ```bash
   adb connect <ip_address>:<debugging_port>
   ```

Use the pairing port for `adb pair`, then the debugging port for `adb connect`.

Confirm the computer can see the phone:

All platforms:

```bash
adb devices
```

The phone should appear in the device list before continuing.

## 4. Run backAndForth

Run the simplest motor-output demo:

```bash
./app run --app backAndForth
```

On Windows:

```bat
app.bat run --app backAndForth
```

The command builds the app, installs it on the connected phone, and launches it.
If Android shows a USB permission prompt after the app launches, allow it.

View logs:

On macOS/Linux:

```bash
./app logcat --app backAndForth
```

On Windows:

```bat
app.bat logcat --app backAndForth
```

## 5. Expected Result

The `backAndForth` app sends repeated wheel-output commands. The robot should
alternate between forward and backward motor output when the phone is connected
to the robot hardware and the app has USB permission.

## 6. Next Steps

- To compare the included demos, see [Demo Apps](demo-apps.md).
- To build your own app, see [Build Your Own App](build-your-own-app.md).
- For common setup errors, see [Troubleshooting](troubleshooting.md).
