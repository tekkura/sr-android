# APKDistribution Migration Guide

## Objective

Refine the project's distribution strategy so that:

- APK files continue to be distributed from GitHub Releases on the canonical repository.
- `abcvlib` can be consumed publicly without requiring GitHub Packages credentials.

This milestone does **not** require moving APK hosting away from GitHub Releases if the canonical repository can already produce release assets there.

## Decisions

- APK release artifacts remain on GitHub Releases and are created by the existing tag-triggered workflow.
- Public library consumption moves to JitPack for this milestone.
- GitHub Packages may still be used for repository-owned publication workflows, but external consumers must not need GitHub credentials to build against `abcvlib`.

## Required Changes

### 1. JitPack compatibility for `abcvlib`

The repository must be buildable by JitPack for the `abcvlib` library module.

Acceptance criteria:

- Public builds must not fail solely because `GITHUB_USER` or `GITHUB_TOKEN` are unset.
- The library dependency graph used to build `abcvlib` must not require authenticated GitHub Packages access.
- Any JitPack-specific runtime requirement needed by the project (for example the Java version) must be declared in-repo.
- The resulting JitPack coordinates for `abcvlib` must be derivable from the checked-in Gradle publishing/module configuration.

### 2. Consumer-facing distribution documentation

Project documentation must describe the supported distribution paths clearly.

Acceptance criteria:

- README or equivalent user-facing docs must state that APKs are distributed through GitHub Releases.
- README or equivalent user-facing docs must show how external Android projects consume `abcvlib` from JitPack.
- Docs must no longer claim that GitHub credentials are required for normal public consumption of `abcvlib`.
- Docs must distinguish between:
  - repository-owner publication workflows, and
  - external consumer dependency installation.

## Out of Scope

- Migrating `abcvlib` to Maven Central.
- Replacing GitHub Releases with another APK hosting service.
- Redesigning the automated release workflow beyond what is necessary to support the above distribution choices.
