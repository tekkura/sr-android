# Migration Guide: Python Droid

This guide covers how to build Python-based Android applications that integrate with the `abcvlib` robotics library using the Chaquopy plugin.

---

## Python Android Framework

Framework selection was evaluated in [issue #153](https://github.com/tekkura/sr-android/issues/153), which compared Chaquopy against Kivy/python-for-android+jnius across several criteria.

**Chaquopy** was selected as the preferred framework for this project for the following reasons:

- **Native Gradle integration** — Chaquopy is a standard Gradle plugin. The `abcvlib.aar` library works with zero additional configuration, consumed via the normal `implementation` dependency declaration.
- **Direct Java/Kotlin class imports** — Python code can import any class available on the Gradle classpath using standard `import` syntax (e.g., `from jp.oist.abcvlib.data import WheelData`). No reflection boilerplate is needed.
- **First-class interop** — Kotlin can invoke Python modules directly via `Python.getInstance()`, and Python receives live Android object references (e.g., `Activity`, `outputs`) with no conversion layer.
- **Standard build output** — Produces a normal APK via the standard Gradle build. No separate build environment or NDK configuration required.

---

## Goal

This milestone implements `backAndForth` and `basicSubscriber` as Python-based Android apps using Chaquopy. These are the two simplest demos in the repository and serve as the baseline for validating the Python-on-Android approach with `abcvlib`.

---

## Implementation

### App Structure

```
app/src/main/
├── java/<package>/
│   └── MainActivity.kt
└── python/
    ├── abcvlib.py
    └── main.py
```

---

### `MainActivity.kt` — Android Container

`MainActivity` extends `AbcvlibActivity` and is responsible for:

1. Establishing the USB serial connection (inherited from `AbcvlibActivity`).
2. Starting the Chaquopy runtime once serial is ready.
3. Injecting Android-side object references into the Python module.
4. Launching the Python run loop on a background coroutine.

```kotlin
class MainActivity : AbcvlibActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        super.onSerialReady(usbSerial)
        initPython()
    }

    private fun initPython() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val py = Python.getInstance()
        val module = py.getModule("abcvlib")

        // Inject Android-side instances into Python
        module.put("outputs", outputs)
        module.put("loop_delay", 0.005)  // 5 ms — matches abcvlibMainLoop default
        // Add further injections as needed, e.g.:
        // module.put("serialCommManager", serialCommManager)

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                module.callAttr("run")
            } catch (e: PyException) {
                Logger.e("MainActivity", "Python error: ${e.message}")
            }
        }
    }
}
```

**Notes:**
- `outputs` (and any other `AbcvlibActivity` fields) are injected before calling `run()`, making them available to `main.py` at runtime.
- The Python run loop executes on `Dispatchers.Default` to avoid blocking the main thread.
- Any additional instances needed by specific demos (e.g., `serialCommManager` for `basicSubscriber`) should be injected here in the same pattern.

---

### `abcvlib.py` — Bridge Module

This module is the bridge between the Android layer and the Python control logic. It receives injected Android instances, exposes them to `main.py` via `builtins`, and drives the Arduino-style `setup()` / `loop()` execution model.

```python
# src/main/python/abcvlib.py

import time
import builtins
import main

# Injected from Kotlin before run() is called
outputs = None
loop_delay = 0.005  # Default: 5 ms 


def run():
    # Expose Android instances to main.py globally
    builtins.outputs = outputs
    # Expose additional injected instances as needed:
    
    main.setup()

    while True:
        main.loop()
        time.sleep(loop_delay)
```

**Notes:**
- `loop_delay` has a default value here but is **injected (and therefore set) from Kotlin** before `run()` is called. The Kotlin-injected value takes effect at runtime; the Python default acts only as documentation/fallback.
- The `while True` loop is intentional — it mirrors `abcvlibMainLoop` but runs entirely within the Python interpreter to avoid repeated JNI crossing overhead.

---

### `main.py` — Application Logic

This is the file the developer writes. It follows an Arduino-style pattern with a one-time `setup()` and a repeated `loop()`.

```python
# src/main/python/main.py

def setup():
    """Called once before the loop starts."""
    pass


def loop():
    """Called repeatedly at the interval defined by loop_delay."""
    pass
```

## Design Decisions

| Decision | Rationale |
|---|---|
| Python run loop instead of `abcvlibMainLoop` | Avoids repeated JNI crossings (~200/sec would be costly); the Python `while` loop keeps control logic inside the interpreter. |
| `loop_delay` injected from Kotlin | Keeps the default timing consistent with `abcvlibMainLoop`; the Python side can still override if needed. |
| `builtins` for sharing Android instances | Provides a simple, global-scope mechanism for passing references from `abcvlib.py` to `main.py` without import coupling. |
| `Dispatchers.Default` for coroutine | Runs the blocking Python loop off the main thread; matches the threading model used by the existing Kotlin demos. |



## Scope

The following changes are required to deliver this milestone:

**1. New app modules**

- `backAndForthPython`
- `basicSubscriberPython`

Each module follows the standard Android app structure and contains:

```
<appModule>/src/main/
├── java/<package>/
│   └── MainActivity.kt
└── python/
    ├── abcvlib.py
    └── main.py
```

**2. Apps Registered at `apps-config.json`**

Each new android-python app will be added to `apps-config.json` so they are picked up by `settings.gradle.kts`:
```json
{
  "apps": [
    .
    .
    "backAndForthPython",
    "basicSubscriberPython"
  ]
}
```
**3. Chaquopy plugin is defined at `libs.versions.toml`**
```toml
[versions]
chaquopy = <latest>

[plugins]
chaquopy = { id = "com.chaquo.python", version.ref = "chaquopy" }
```

Each new android-python app module applies the plugin via the catalog alias:

```kotlin
plugins {
    alias(libs.plugins.chaquopy)
}
```

> **Note:** Implementation is delivered across two PRs:
> - **PR 1:** Chaquopy plugin wiring, `backAndForthPython` app, and `apps-config.json` registration
> - **PR 2:** `basicSubscriberPython` app and `apps-config.json` registration

## Verification
Each app module is considered complete when it runs on device and produces behavior consistent with the native android app.