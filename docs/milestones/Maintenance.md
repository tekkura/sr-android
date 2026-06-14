## Maintenance tasks.

### 1. Accommodate for edge-to-edge display mode 
Android 15 made edge-to-edge display mode mandatory, Android 16 made it so it is no longer possible to opt out of using it. The problem is that this mode breaks the UI of every test activity. It is a problem since some parts of the UI get covered by app bar or system bars. Changes should accommodate both the newer devices that enforce edge-to-edge mode and for older devices that don't support it by default.

### 2. Update NDK 
Update NDK to a more recent one. Current NDK (21.0.6113669) is almost 5 years old, and it will lead to some compatibility issues while establishing 16KB page support

### 3. Add 16KB page support 
Android 15 introduced 16 KB memory page alignment. As of now, all apps published into Google Play must support 16KB page alignment in order to be approved for release. Plus, it will make the library run smoother on newer devices.

### 4. Lifecycle not handled in apps 
The issue is about UI updates and publishers continuing while the app is in the background. We should track Activity lifecycle events (onPause, onResume) to stop UI updates, QR generation, etc . This helps prevent crashes, unnecessary processing, and battery drain.

### 5. Move USB initialization off UI thread

#### Problem
AbcvlibActivity.onCreate() calls usbInitialize() on the UI thread. usbInitialize() constructs UsbSerial, which enumerates devices and may call openDevice(), port.open(), setParameters(), and sleep(100) on the UI thread. This is potentially blocking and can cause UI jank or ANR on slow devices or flaky USB.

#### Evidence / Trace
- AbcvlibActivity.onCreate() -> usbInitialize()
- UsbSerial constructor does enumeration and connects via connect(d)
- connect(...) calls openPort(connection)
- openPort(...) blocks on port.open(connection) and calls sleep(100)

#### Proposed Fix
- Move USB enumeration and port open to a background dispatcher (e.g., Dispatchers.IO or a dedicated executor).
- Keep only UI-safe work on the main thread (e.g., receiver registration, UI dialogs).

#### Acceptance Criteria
- No blocking USB work runs on the UI thread.
- Behavior unchanged when USB is present and permission granted.
- Permission flow still works with the broadcast receiver.
- Add a short comment or doc note explaining threading expectations.

### 6. Error reporting
Implement error reporting tool like Crashlytics / bugsnag / acra (can be self hosted).

### 7. HandsOnApp rotates the image in non UI Thread
Image rotation is being done in UI thread, this blocks the thread.

https://github.com/tekkura/sr-android/pull/82/changes#diff-5e9cba269e44bfaad91a3a2a52448ff8c63f19c9f4ae18762db2a0b9609eac53R130

### 8. UI freeze
GUI freezes when I try to adjust the slider when the app is running in the pid-changes branch
```
2025-09-09 12:36:45.756  1821-16111 ActivityManager         system_server                        E  ANR in jp.oist.abcvlib.pidbalancer (jp.oist.abcvlib.pidbalancer/.MainActivity)
PID: 16020
Reason: Input dispatching timed out (641825d jp.oist.abcvlib.pidbalancer/jp.oist.abcvlib.pidbalancer.MainActivity (server) is not responding. Waited 5006ms for MotionEvent)
Parent: jp.oist.abcvlib.pidbalancer/.MainActivity
Frozen: false
Load: 2.1 / 1.93 / 2.06
```

After clipping the output to the range [-0.5, 0.5], all these weird errors are gone.
So I guess the large value output causes some error.

### 9. List all necessary tests for CI
Evaluate the library and demo applications and create a list of tests necessary to cover library's functionality. Then decide, if all of them should be checked in CI process or if some should be omitted from it. Expected result is a Markdown document containing a list of necessary tests and if they need to be included in CI checks.

### 10. Add testing jobs to GitHub CI pipeline
Add smoke/instrumentation tests to GitHub workflows for continuous integration. Expected result - A comprehensive test suite has been created for CI pipeline. If the resulting test suite is too long/costly to run, split it into full test suite and minimal one.