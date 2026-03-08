# Migration Guide: PermissionHandlingUpdates

## Summary
`onSerialReady` callback initializes publishers on the main thread. The `initializePublishers` method blocks the main thread until permissions are handled, causing the UI to freeze and the application to become unresponsive unless the user manually grants the required permissions.

## Objective
- Use Kotlin Coroutines to prevent main thread blocking and ensure smooth, asynchronous publisher initialization.
- Centralize permission-handling behavior as much as possible while allowing required supporting changes in dependent permission/lifecycle paths.

## Scope
- Primary file: `AbcvlibActivity`.
- Allowed supporting files:
  - Directly related permission/lifecycle plumbing required to make permission-handling robust (for example `SerialCommManager` when needed for sequencing/thread-safety tied to permission flow).
  - `AndroidManifest.xml` permission declarations required for this milestone's runtime permission behavior.

## Rules
- Move `AbcvlibActivity.onSerialReady` context to `Dispatchers.Default`.
- Maintain concurrency behavior: moving code to coroutines must not introduce race conditions.
- Launch coroutines exclusively from `AbcvlibActivity`.
- Manifest edits in this milestone must be limited to permission declarations only.
- Changes outside permission-handling/lifecycle correctness are out of scope.

## Test/Validation Expectations for PR Acceptance
- At runtime, a newly installed app must:
  - Request and grant multiple permissions smoothly.
  - Operate without blocking the UI during publishers initialization.
  
