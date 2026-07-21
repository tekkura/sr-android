# Android SDK Command-Line Tools Setup

An automated installer is available here:
[cmdline-tools-install](https://github.com/topherbuckley/cmdline-tools-install/blob/main/install-cmdline-tools).
It has only been tested on Ubuntu 22.04.

Manual setup on Linux/macOS:

1. Download the Android command-line tools from
   [Android Studio downloads](https://developer.android.com/studio#command-line-tools-only).
2. Unzip the tools into an Android SDK directory, such as `~/android_sdk`.
3. Arrange the command-line tools directory as required by
   [sdkmanager](https://developer.android.com/tools/sdkmanager).
4. Run `sdkmanager` from the command-line tools directory and install the
   required SDK packages:

   ```bash
   sdkmanager "platform-tools" "platforms;android-30" "build-tools;33.0.1"
   ```

5. Accept SDK licenses:

   ```bash
   yes | sdkmanager --licenses
   ```

6. Set `ANDROID_HOME`:

   ```bash
   export ANDROID_HOME=~/android_sdk
   ```

7. Add `adb` to `PATH`:

   ```bash
   export PATH=$PATH:$ANDROID_HOME/platform-tools
   ```

Manual setup on Windows:

1. Download the Android command-line tools from
   [Android Studio downloads](https://developer.android.com/studio#command-line-tools-only).
2. Unzip the tools into an Android SDK directory, such as
   `%LOCALAPPDATA%\Android\Sdk`.
3. Arrange the command-line tools directory as required by
   [sdkmanager](https://developer.android.com/tools/sdkmanager).
4. Run `sdkmanager.bat` from the command-line tools directory and install the
   required SDK packages:

   ```powershell
   sdkmanager.bat "platform-tools" "platforms;android-30" "build-tools;33.0.1"
   ```

5. Accept SDK licenses:

   ```powershell
   sdkmanager.bat --licenses
   ```

6. Add these user environment variables:

   ```text
   ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
   Path=%Path%;%ANDROID_HOME%\platform-tools
   ```
