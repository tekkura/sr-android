# Migration Guide: Comprehensive Demo

## Objective

Define the review contract for the `comprehensiveDemo` milestone.

This milestone is intended to produce a single integrated demo application which combines the
existing building blocks for:

- object detection
- charging behavior
- robot interaction / mating flow
- evolutionary parameter exchange or update behavior

The milestone is integration-focused. It is not a requirement to solve every robotics edge case or
to deliver a fully optimized autonomous controller.

## Scope

Changes in scope for this milestone include:

- adding or updating one integrated demo application that ties together the required behaviors
- adding controller/framework code for a periodic state-decision loop
- wiring together existing publishers or app-level behaviors needed by the integrated demo
- updating documentation needed to explain how the demo is intended to behave or be reviewed
- updating app resources, manifests, and module registration required for the integrated demo
- model-selection or detector label plumbing needed for the demo controller to interpret visible
  targets

## Out of Scope

The following are out of scope unless a PR explicitly justifies them as required to unblock the
demo:

- general-purpose robotics framework redesign
- broad `abcvlib` refactors unrelated to the integrated demo
- path planning or obstacle avoidance beyond simple heuristic behavior
- highly polished recovery behavior for all room or wall edge cases
- detector retraining optimization work beyond what is needed for reliable room-scale demo use
- changing unrelated standalone demo apps only for style, cleanup, or consistency
- introducing new detector subclasses unless the controller behavior actually depends on them

## Required Changes

### 1. Integrated demo behavior

The milestone must result in one cohesive demo application which can:

- observe robot-relevant state from existing publishers
- select behaviors periodically rather than hard-coding one fixed routine
- switch between charging-related and robot-interaction-related behavior based on state

Acceptance criteria:

- the demo is implemented as one app-level flow rather than a loose collection of unrelated sample
  pieces
- the control logic has an explicit notion of current state and current behavior
- the implementation can consume object-detection results, battery / charger state, and QR-related
  inputs as needed by the selected demo flow

### 2. State-decision loop

The integrated demo must expose or implement a simple decision loop that checks state every few
seconds and selects the next behavior.

Acceptance criteria:

- the app has a periodic state-evaluation step
- state inputs used by that step are visible in code and reviewable
- chosen behaviors are explicit enough that reviewers can tell why the robot switches modes

### 3. Behavior integration

The integrated demo must cover the intended milestone behavior categories in a simple, reviewable
way.

Expected categories include:

- resting / inactive behavior
- recovery or balancing behavior as needed for the body / charger transition
- searching behavior
- charger-seeking behavior
- approaching another robot
- QR display / QR acceptance behavior
- a simple mating-display or equivalent demo behavior

Acceptance criteria:

- each behavior used by the demo has a concrete implementation or a clearly documented stub
- transitions between behaviors are deterministic enough to review
- the demo does not rely on unstated manual intervention except where documented

## Behavior Preservation Rules

- Existing standalone apps must not change behavior unless the PR explicitly includes them in scope.
- Shared-library changes should be minimal and directly tied to the integrated demo requirement.
- If a PR changes detector label assumptions, the PR description must state:
  - the expected model labels
  - how those labels map to controller-visible state
  - whether the controller truly uses any subclass distinctions
- If a PR adds robot subclass labels, the controller must make meaningful use of them; otherwise a
  simpler label set is preferred.
- Demo logic may be simple and heuristic, but the code should make the heuristic explicit rather
  than burying it in ad hoc conditionals with no stated behavior intent.

## Validation Expectations

Each PR under this milestone should be validated according to the changes it introduces.

Expected validation may include:

- module compilation / build success for the affected app(s)
- review of controller transitions and behavior-selection logic
- verification that required publishers and subscriptions are initialized correctly
- verification that QR display / QR acceptance flow is reachable in the controller
- verification that charging-related logic still remains reachable and not regressed by robot
  interaction changes

Where on-device validation is not available, the PR should state what was verified statically and
what remains unverified on hardware.

## Review Constraints

- All feature PRs for this milestone must target `milestone/comprehensiveDemo`.
- One high-level task per PR. Do not mix migration-guide work with full demo implementation unless
  explicitly intended.
- Large unrelated formatting or cleanup changes should be split out.
- Reviewers should prefer incremental integration over speculative architecture work.
- If a PR only adds scaffolding, it must clearly state which milestone acceptance criteria remain
  unfinished.
- If a PR depends on a new detector model or label scheme, that dependency must be called out in
  the PR description so review is grounded in the actual intended runtime behavior.
