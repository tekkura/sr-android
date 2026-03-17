# Milestone Summary
Initializing  publishers blocks the main thread  leading to an unresponsive application.

------------------------------------------------------------------------

# Objective

Use **Kotlin Coroutines** to eliminate main thread blocking and enable
smooth publisher initialization.

# Publisher Initialization Blocking UI

## Current State

After the device connects, the app calls:

`onSerialReady()`

Inside this method:

`publisherManager.initializePublishers()`
`publisherManager.startPublishers()`

During initialization, the app:

-   Requests several permissions
-   Waits for them to be granted

This process blocks the **UI completely**, causing the app to become
unresponsive.

## Expected State

In `AbcvlibActivity`:

-   Move the `onSerialReady` callback execution context to the **Default
    Dispatcher**

This will:

-   Allow publishers to initialize asynchronously
-   Handle permission requests without blocking the UI