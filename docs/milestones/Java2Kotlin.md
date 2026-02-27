## Phase 3: Convert Java Code to Kotlin

### Goal
Migrate all Java code to Kotlin

---

## Expected Pull Requests (PRs)

### Note
Each app/library migration should be done in a separate PR for clarity and reviewability.

---

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

---

### 2) Migrate `abcvlib` Library
- `abcvlib/core`
- `abcvlib/fragments`
- `abcvlib/tests`
- `abcvlib/util`


### Migration Guide and recommended improvements.
- Address all Android Studio hints and suggestions to improve code quality.
- If a layout contains many views and you want to use **View Binding** for simplicity, you can enable it at the module level.
```gradle
buildFeatures {
    viewBinding = true
}
```

- Do not introduce lifecycle- or resource-safety logic at this milestone 

### Null safety (check this reviews https://github.com/tekkura/sr-android/pull/56)
- Use `lateinit` if possible and avoid safe calls for required objects. Do not use `?.` for objects that are essential to the app’s
- If something is essential for the app to function, it must NOT be nullable, and it must crash loudly if missing.
- If an object will never be null” → then make Kotlin enforce it, for example `public void onSerialReady(UsbSerial usbSerial)` might be converted automatically as nullable `onSerialReady(usbSerial: UsbSerial?)` however this should be strictly non-null, and by the same for all function parameters if they are non-null in java code.

### Periodic/Scheduled Updates 
- For simple ui updates feel free to use coroutines 
```kotlin
lifecycleScope.launch {
        while (isActive) { 
            // call suspend method that update ui
            delay(100)
        }
}
```

- For state updates  keep using `scheduleAtFixedRate`
> for anything UI related you can use fixed delay not rate. My comment only applies to non UI related code, specifically to code that would affect either the state (any incoming data from a publisher) or action (any call to setWheelSpeed or similar control of the wheels, or showing a QR code. These are actions that directly affect the state. The UI is only meant for debugging or tuning the controllers, not for real-time control itself. So nonuniform delays are fine there.