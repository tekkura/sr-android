# ROS vs FlatBuffers in This Project

This note clarifies the difference between the new ROS/rosbridge integration work and the older
HTTP/FlatBuffer-based server communication path still present in the repository.

## Short version

- `rosbridgeTest` proves that the Android device can participate in a ROS-based host workflow.
- The existing FlatBuffer path is not the same thing as the ROS path.
- Replacing FlatBuffers everywhere with ROS/JSON is not automatically the right architectural move.

## What ROS/rosbridge is good for

The `rosbridge` path is a good fit for:

- host-to-robot integration with standard ROS tools
- topic-style command/status messaging
- debugging with `ros2 topic echo`, `rqt`, and other ROS-native workflows
- development on a nearby LAN-connected PC without needing Android-native ROS client libraries

This is what the `rosbridgeTest` app demonstrates.

## What the FlatBuffer path is good for

The existing HTTP/FlatBuffer path is better suited to:

- custom server-driven reinforcement learning workflows
- compact binary transfer of structured episode data
- higher-volume payloads such as image-heavy recordings or training data uploads
- non-ROS backends that do not need ROS node/topic semantics

This is still represented in the repository by code such as:

- `apps/basicServer`
- `libs/abcvlib/core/learning/Trial.kt`
- `libs/abcvlib/core/learning/FlatbufferAssembler.kt`
- `libs/abcvlib/util/HttpConnection.kt`

## Why FlatBuffers were adopted in the first place

The original motivation was not arbitrary format preference. It was performance and transport
efficiency.

In particular:

- serializing large payloads such as images into JSON is slow
- JSON adds substantial size overhead compared with a compact binary format
- repeatedly encoding image-rich timestep/episode data as JSON creates avoidable CPU and bandwidth
  cost on Android

FlatBuffers were adopted to avoid that overhead for server/RL-style data exchange.

That historical reason still matters when evaluating whether ROS/JSON should replace the old path.

## Why `rosbridgeTest` does not automatically replace `basicServer`

`rosbridgeTest` proves:

- Android can connect to a ROS host
- Android can subscribe to a ROS topic
- Android can publish to a ROS topic

It does **not** do the following:

- migrate the existing FlatBuffer-based learning/server code to ROS
- replace the HTTP upload/download path used by server-oriented workflows
- prove that JSON-over-WebSocket is the right transport for large binary or image-heavy data

So `rosbridgeTest` is best understood as a ROS connectivity proof and smoke-test app, not as a full
replacement for the older FlatBuffer/HTTP server architecture.

## Practical interpretation

The most sensible interpretation for the current codebase is:

- use ROS/rosbridge for ROS-native host integration and debugging workflows
- keep HTTP/FlatBuffers for custom RL or data-transfer workflows unless and until a better binary
  ROS-aligned alternative is chosen

If the long-term goal is to unify on a single transport, that should be treated as a separate
architecture decision rather than assumed as a direct consequence of adding ROS support.
