# Java2Kotlin

## Goal and scope
- Migrate all Java code to Kotlin for the target module(s).
- One PR per module unless multiple modules are required to build together.

## Expected Pull Requests (PRs)

### Note
Each app/library migration should be done in a separate PR for clarity and reviewability, unless multiple modules are required to build together.

### 1) Migrate Apps (1 PR per app)
- `apps/backAndForth`
- `apps/basicAssembler`
- `apps/basicCharger`
- `apps/basicQRReceiver`
- `apps/basicQRTransmitter`
- `apps/basicServer`
- `apps/basicSubscriber`
- `apps/compoundController`
- `apps/handsOnApp`
- `apps/pidBalancer`
- `apps/serverLearning`

### 2) Migrate `abcvlib` Library
- `abcvlib/core`
- `abcvlib/fragments`
- `abcvlib/tests`
- `abcvlib/util`

## Required outcomes
- Target module(s) are 100% Kotlin (no remaining Java files).

## Migration rules (review checks)
- Apply Android Studio hints and suggestions that improve code quality.
- If a layout is complex, View Binding is allowed:

```gradle
buildFeatures {
    viewBinding = true
}
```

- Do not introduce lifecycle- or resource-safety logic at this milestone.

## Null safety (see tekkura/sr-android#56)
- Avoid safe calls for required objects. Do not use `?.` for objects that are essential to the app’s
- If something is essential for the app to function, it must NOT be nullable, and it must crash loudly if missing.
- If an object will never be null” → then make Kotlin enforce it, for example `public void onSerialReady(UsbSerial usbSerial)` might be converted automatically as nullable `onSerialReady(usbSerial: UsbSerial?)` however this should be strictly non-null, and by the same for all function parameters if they are non-null in java code.

## Periodic / scheduled updates
- UI updates may use coroutines:

```kotlin
lifecycleScope.launch {
    while (isActive) {
        // call suspend method that update ui
        delay(100)
    }
}
```

- State updates must continue to use `scheduleAtFixedRate`.
- Fixed delay is only acceptable for UI updates; non-UI state/actions must use fixed rate.
