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
- `abcvlib/core..`
- `abcvlib/fragments..`
- `abcvlib/tests..`
- `abcvlib/util..`

## Required outcomes
- Files in the PR should be strictly Kotlin. Deletion of Java files is allowed. Updates or changes to Java files are not allowed.

## Migration rules (review checks)
-   Accept Android Studio hints and suggestions that improve code quality.
-   Keep using the existing logic as much as possible.
-   Do not suggest improvements or bug fixes at this milestone.
-   Allow minor typo corrections and identifier renaming, provided they do not affect files outside the current PR.
-   If a layout is complex, using `viewBinding` is allowed instead of `findViewById`.
-   Do not introduce lifecycle- or resource-safety logic at this milestone.

## Null Safety (see tekkura/sr-android#56)

-   Avoid safe calls for required objects. Do not use `?.` for objects that are essential to the app.
-   If something is essential for the app to function, it must not be nullable and must crash loudly if missing.
-   Use `lateinit` as much as possible for non-null objects.
-   If an object will never be null” → then make Kotlin enforce it, for example `public void onSerialReady(UsbSerial usbSerial)` might be converted automatically as nullable `onSerialReady(usbSerial: UsbSerial?)` however this should be strictly non-null, and by the same for all function parameters if they are non-null in java code.

## Periodic / Scheduled Updates

-   UI updates may use coroutines instead of `scheduleAtFixedRate` or `scheduleWithFixedDelay` (optional):

``` kotlin
lifecycleScope.launch {
    while (isActive) {
        // call suspend method that updates UI
        delay(100)
    }
}
```

-   UI updates may use View Binding directly instead of volatile variables and infinite loops or scheduled tasks.
-   State updates must continue using the existing method either `scheduleAtFixedRate` or `scheduleWithFixedDelay`. Do not accept replacing them with coroutines.
