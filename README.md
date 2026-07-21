# OIST Smartphone Robot Android Framework

[![](https://jitpack.io/v/oist/smartphone-robot-android.svg)](https://jitpack.io/#oist/smartphone-robot-android)

This repository contains the Android side of the OIST Smartphone Robot project:
the shared `abcvlib` Android library, demo app modules, build configuration, and
documentation for creating robot-control apps.

Android apps handle phone-side sensing, user interaction, app-level control
logic, and communication with the robot hardware. For the full hardware,
firmware, model, and Android project context, see the
[Smartphone Robot wiki](https://github.com/oist/smartphone-robot/wiki).

## Table Of Contents

- [Getting Started](docs/getting-started.md): first run with the `backAndForth`
  demo app.
- [Demo Apps](docs/demo-apps.md): overview of the included demo apps.
- [Build Your Own App](docs/build-your-own-app.md): choose how to build a
  robot-control app from this project.
- [Communication Protocol](docs/communication-protocol.md): USB serial packet
  framing and parser API.
- [Testing](docs/testing.md): unit tests, instrumentation tests, and latency
  benchmarks.
- [Contributing](docs/contributing.md): contributor and maintainer references.

See the shared Tekkura PR workflow for issue scope, milestone, review, and merge policy:
https://github.com/tekkura/.github/blob/main/docs/pr-workflow.md

- `apps/`: Android demo app modules.
- `libs/abcvlib/`: shared Android framework/library code used by app modules.
