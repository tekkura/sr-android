In the absence of the custom robot hardware, it would be useful to have an instrumentation test that mimics the communication between the two. In this way both remote and automated tests could be performed at runtime.

https://github.com/tekkura/smartphone-robot-android/blob/3f31a3b4769493a56f6d145aeb8aaa41c6ca0cdb/libs/abcvlib/src/main/java/jp/oist/abcvlib/util/SerialCommManager.java#L110

That is the function that would have to be called by the test, with command = SET_MOTOR_LEVELS as thats really the only one being used right now. You'd have to create a test definition for an instance of https://github.com/tekkura/smartphone-robot-android/blob/main/libs/abcvlib/src/main/java/jp/oist/abcvlib/util/RP2040State.java.

## USB-Serial scope
Issues under this milestone are also related to the USB-serial communication protocol.

The main focus is:
1. Refactor low-level handling of start/end flags and other enveloping in favor of robust high-level read/write abstractions with intuitive defaults for common USB-serial errors/exceptions.
2. Reduce communication loop time between phone and firmware. Current round-trip latency is approximately 20 ms; identify contributors via targeted profiling and optimize accordingly.

### Background
Another scope is currently focused on refactoring the firmware of the external USB connected robot to use USB-CDC via TinyUSB instead of the current stdio-based USB-serial implementation. Many issues encountered during this project appear to stem from instability in the low-level USB-serial layer. By migrating to TinyUSB on RP2040, firmware aims to improve stability, separation of concerns, and long-term maintainability.

This work is dependent on progress in the firmware repo, so implementation that depends on firmware protocol changes should wait until firmware-side readiness is established.

TODO:
Add details about the expected communication to mimic.
