# Troubleshooting

## `adb` Is Not Found

Use this section if you see:

```text
adb: command not found
```

or:

```text
app: Unable to locate executable adb.
```

### 1. Find Your Android SDK

If you installed Android Studio:

1. Open Android Studio.
2. Open `Settings -> Languages & Frameworks -> Android SDK`.
3. Copy the `Android SDK Location`.

Common SDK locations:

- Linux: `~/Android/Sdk`
- macOS: `~/Library/Android/sdk`
- Windows: `C:\Users\<you>\AppData\Local\Android\Sdk`

### 2. Check Whether adb Exists

Look for `adb` inside the SDK's `platform-tools` directory:

```text
<Android SDK Location>/platform-tools/adb
```

Examples:

```bash
~/Android/Sdk/platform-tools/adb version
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

If this works, `adb` is installed but not on your terminal `PATH`.

If this file does not exist, install Android SDK Platform Tools:

- Android Studio: install `Android SDK Platform-Tools` from the SDK Manager.
- Command line: see [Android SDK command-line tools setup](android-sdk-command-line-tools-setup.md).

### 3. Make adb Available To This Project

Choose one fix.

Set `ANDROID_HOME` to the SDK directory:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

Or set `ADB` to the full adb executable path:

```bash
export ADB="$HOME/Android/Sdk/platform-tools/adb"
```

On Windows PowerShell:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
```

After setting one of these, run:

On macOS/Linux:

```bash
./app run --app backAndForth
```

On Windows:

```powershell
app.bat run --app backAndForth
```

### 4. Make adb Work Everywhere

To make plain `adb` work in any new terminal, add `platform-tools` to your
system `PATH`.

Linux/macOS example:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

Windows user `Path` entry:

```text
%LOCALAPPDATA%\Android\Sdk\platform-tools
```

Restart the terminal, then check:

On macOS/Linux:

```bash
adb version
adb devices
```

On Windows:

```powershell
adb.exe version
adb.exe devices
```

Official Android references:

- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [Android SDK environment variables](https://developer.android.com/tools/variables)

## Package Signatures Do Not Match

Use this section if `./app install`, `./app run`, `app.bat install`, or
`app.bat run` reports:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

Android will not replace an installed app if the existing app was signed with a
different certificate. This can happen after installing a release APK, using a
debug APK built on another computer, or regenerating the local debug keystore.

The `app` CLI detects this error and asks whether to uninstall the existing
package and retry the install. Uninstalling removes that app's local data.

If you choose `y`, the CLI runs:

```text
adb uninstall <package>
adb install -r -d <apk>
```

## JAVA_HOME Is Missing Or Wrong

Use this section if Gradle cannot find Java, uses an old Java version, or shows
a JDK compatibility error.

Gradle may report:

```text
Toolchain installation '<path>' does not provide the required capabilities: [JAVA_COMPILER]
```

This means Gradle found a Java runtime at that path, but not a full JDK with
`javac`. Java 17 or newer is acceptable, including Java 21, but it must be a JDK
rather than a JRE/runtime-only install.

### 1. Check Java

On macOS/Linux:

```bash
java -version
echo "$JAVA_HOME"
"$JAVA_HOME/bin/javac" -version
```

On Windows PowerShell:

```powershell
java -version
echo $env:JAVA_HOME
& "$env:JAVA_HOME\bin\javac.exe" -version
```

### 2. Find A JDK

If using Android Studio, the bundled JDK is usually here:

- Linux: `<android-studio>/jbr`
- macOS: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Windows: `C:\Program Files\Android\Android Studio\jbr`

You can also install a full JDK such as OpenJDK or Eclipse Temurin 17 or newer.
On Linux, install a `-jdk` package, for example `openjdk-17-jdk` or
`openjdk-21-jdk`, not a `-jre` package. On Windows, set `JAVA_HOME` to the JDK
directory, not the `bin` directory.

### 3. Set JAVA_HOME

Linux/macOS example:

```bash
export JAVA_HOME="<path_to_jdk>"
```

Windows PowerShell example:

```powershell
$env:JAVA_HOME="<path_to_jdk>"
```

Restart the terminal before running `./app`, `app.bat`, `./gradlew`, or
`gradlew.bat` again.
