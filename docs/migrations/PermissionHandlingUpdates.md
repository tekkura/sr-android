# Migration Guide: PermissionHandlingUpdates

## Summary
`onSerialReady` callback initializes publishers on the main thread. The `initializePublishers` method blocks the main thread until permissions are handled, causing the UI to freeze and the application to become unresponsive unless the user manually grants the required permissions.

## Objective
- Use Kotlin Coroutines to prevent main thread blocking and ensure smooth, asynchronous publisher initialization.
- Centralize this improvement in the base activity (AbcvlibActivity) to ensure consistent behavior across the application.

## Scope
- Files affected: `AbcvlibActivity`.

## Rules
- Move `AbcvlibActivity.onSerialReady` context to `Dispatchers.Default`.
- Maintain concurrency behavior: moving code to coroutines must not introduce race conditions.
- Launch coroutines exclusively from `AbcvlibActivity`.

## Test/Validation Expectations for PR Acceptance
- At runtime, a newly installed app must:
  - Request and grant multiple permissions smoothly.
  - Operate without blocking the UI during publishers initialization.
  