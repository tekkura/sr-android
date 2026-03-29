# comprehensiveDemo

This document describes the current `comprehensiveDemo` app behavior as implemented on the
`comprehensiveDemo/app-scaffold` branch.

Relevant code:
- `apps/comprehensiveDemo/src/main/java/jp/oist/abcvlib/comprehensivedemo/MainActivity.kt`
- `apps/comprehensiveDemo/src/main/java/jp/oist/abcvlib/comprehensivedemo/ComprehensiveDemoController.kt`
- `apps/comprehensiveDemo/src/main/java/jp/oist/abcvlib/comprehensivedemo/BoundingBoxView.kt`
- `apps/comprehensiveDemo/src/main/java/jp/oist/abcvlib/comprehensivedemo/DetectionRectMapper.kt`

## Overview

`comprehensiveDemo` is an integration scaffold that combines:
- battery and charger sensing
- wheel output control
- MediaPipe-based object detection
- QR display and QR detection

The app currently uses:
- a fast main control loop from `AbcvlibActivity`
- a slower behavior-selection loop in `MainActivity`
- a controller state machine in `ComprehensiveDemoController`

Some milestone pieces are integrated only as scaffolding. In particular, QR exchange currently
updates a color payload directly in `MainActivity` rather than transitioning through a dedicated
"mating" behavior state.

## Runtime Loops

There are two important loops:

1. Main control loop
- Implemented in `MainActivity.abcvlibMainLoop()`
- Runs every 20 ms via `setDelay(20)`
- Reads current state
- Applies QR visibility
- Asks the controller for a `WheelCommand`
- Sends the command to `outputs.setWheelOutput(...)`

2. Behavior-selection loop
- Started in `MainActivity.onCreate()`
- Calls `updateBehavior()`
- Repeats every 2 seconds

This means:
- wheel commands can update quickly once a behavior is active
- behavior transitions themselves are only reconsidered every 2 seconds

## State Inputs

The controller currently reasons over:
- charger state
- recently-undocked state
- hardware-ready state
- battery voltage
- latest robot detection
- latest puck detection
- image dimensions

Those values are assembled into `ControllerState` in `MainActivity.getState(...)`.

## Behavior Selection

`ComprehensiveDemoController.selectBehavior(...)` currently chooses behaviors in this order:

1. Forced behavior, if one is supplied
2. Stay in `GET_UP_AND_BALANCE` while `recentlyUndocked` is still true
3. `REST_ON_TAIL` when the robot is on the charger
4. `GET_UP_AND_BALANCE` when the robot has recently undocked
5. `GO_CHARGING` when battery is low and a puck is visible
6. `SEARCH_AROUND` when battery is low but no puck is visible
7. `APPROACH_ANOTHER_ROBOT` when a robot is visible
8. `SEARCH_AROUND` otherwise

Important note:
- `SEXUAL_DISPLAY`
- `SHOW_QR_CODE`
- `ACCEPT_QR_CODE`

exist as behaviors, but the normal autonomous selector does not currently transition into them.

## Current Behaviors

### REST_ON_TAIL
- Sends zero wheel speed with braking enabled
- Intended resting state while on the charger

### GET_UP_AND_BALANCE
- Currently a scripted placeholder sequence
- Alternates forward, reverse, and turning motions every 500 ms
- Does not currently use the `PIDBalancer`

### SEARCH_AROUND
- Rotates in place
- Chooses a random turn direction
- Keeps that direction for `SEARCH_DIRECTION_CHANGE_MS`

### GO_CHARGING
- Uses puck detection
- Applies the shared `approachCommand(...)`
- Current tuning is intended for driving onto / toward the puck

### APPROACH_ANOTHER_ROBOT
- Uses robot detection
- Applies the shared `approachCommand(...)`
- Forward motion stops once the target is visually close enough according to `stopAtBottomError`
- Turning continues based on horizontal image error

### SEXUAL_DISPLAY
- Placeholder dance motion
- Not selected automatically in the current flow

### SHOW_QR_CODE
- Centers on the visible robot using turning only
- QR visibility is enabled in this behavior
- Not selected automatically in the current flow

### ACCEPT_QR_CODE
- Similar to `SHOW_QR_CODE`, but with a different centering threshold/gain
- Not selected automatically in the current flow

## Approach Steering

`GO_CHARGING` and `APPROACH_ANOTHER_ROBOT` both currently use the same steering helper:
- `approachCommand(...)`

That helper:
- computes horizontal image error
- computes a bottom-of-image proximity proxy
- applies a forward bias while the target is still far enough away
- mixes turning into left/right wheel outputs

The charging and robot-approach behaviors use different tuning values, but they still share the
same general control shape.

## Detection and Overlay

Object detection uses:
- `ObjectDetectorData`
- MediaPipe object detection
- GPU delegate in `comprehensiveDemo`

Bounding boxes are shown only for the currently relevant target:
- puck during `GO_CHARGING`
- robot during `APPROACH_ANOTHER_ROBOT`, `SHOW_QR_CODE`, and `ACCEPT_QR_CODE`

The display-space mapping is shared between:
- `BoundingBoxView`
- controller error computation

This avoids the earlier problem where the visual overlay and steering logic used different
coordinate conventions.

## QR Display and Detection

The UI is split vertically:
- top half shows the local QR code when QR visibility is enabled
- bottom half shows the camera preview and bounding box overlay

The QR payload currently encodes:
- a per-run local ID
- the robot's current RGB color

Format:
- `ID:RRGGBB`
- example: `42EE9BEF:3658B3`

The QR is rendered using its payload color as the QR foreground color.

## Current QR / "Mating" Flow

When `QRCodeData` decodes a QR payload:

1. `MainActivity.onQRCodeDetected(...)` receives the decoded string
2. The payload is parsed as `ID:RRGGBB`
3. Same-ID payloads are ignored to prevent self-mating
4. Different-ID payloads trigger a local color update
5. The local QR is regenerated immediately if QR is currently visible

The current offspring rule is:
- channel-wise average of local color and remote color
- plus a small random mutation on each channel

This means QR exchange is currently handled as a direct state update in `MainActivity`, not by a
controller behavior transition.

## Current Limitations

Known limitations of the current scaffold:

- Behavior selection never autonomously enters:
  - `SEXUAL_DISPLAY`
  - `SHOW_QR_CODE`
  - `ACCEPT_QR_CODE`

- QR detection does not currently:
  - change controller behavior
  - persist evolutionary state beyond the in-memory color
  - exchange any parameter set beyond the display color

- `GET_UP_AND_BALANCE` is still a scripted motion, not a true balancing controller

- `GO_CHARGING` and `APPROACH_ANOTHER_ROBOT` still share the same underlying approach helper,
  even though their end goals differ

- Some diagnostics and timing instrumentation remain in the app for debugging

## Useful Logcat Filters

Behavior selection:
```text
package:jp.oist.abcvlib.comprehensivedemo selectBehavior
```

Approach latency:
```text
package:jp.oist.abcvlib.comprehensivedemo e2eLatency
```

QR activity:
```text
package:jp.oist.abcvlib.comprehensivedemo QR
```

## Suggested Future Cleanup

Likely future improvements:
- promote QR exchange into explicit controller behaviors
- connect QR exchange to a real evolutionary parameter model
- separate charging and robot-approach terminal logic more cleanly
- replace placeholder get-up logic with a real balance-controller integration
- simplify the camera/detection/display transform pipeline further
