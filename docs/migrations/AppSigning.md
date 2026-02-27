# Migration Guide: App Signing

This guide defines the requirements and checklist for the **AppSigning** milestone. It focuses on establishing a reproducible, documented, and secure release-signing workflow as defined in Issues #1 and #2.

## Milestone Goal
Establish a consistent release-signing configuration across all modules to eliminate manual overwrite/delete workflows during deployment and ensure reproducible builds locally and in CI.

## Implementation Strategy: Dual-Source Loading
To support both local and CI environments without committing secrets, the signing configuration must follow a **Dual-Source** strategy:
1.  **Local Development**: Properties are loaded from `local.properties` (which is git-ignored).
2.  **GitHub Actions**: Properties are loaded from **Environment Variables** (mapped from GitHub Secrets).

The Gradle logic should use the `providers` API to prioritize properties:
```kotlin
val storeFile = providers.gradleProperty("RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
```

## Reviewer Checklist

### 1. Implementation (Issue #1)
- [ ] **No Secrets in Repo**: Verify `.jks`, `.keystore`, and plain-text passwords are not committed. Check `.gitignore`.
- [ ] **Dual-Source Loading**: Signing values (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) are loaded using the `gradleProperty(...).orElse(environmentVariable(...))` pattern.
- [ ] **Convention Plugin**: Signing logic is centralized in `:build-logic:convention` and applied to all app modules.
- [ ] **Graceful Failure**: The build provides a clear error message or skips signing if properties are missing, rather than failing with a generic NullPointerException.
- [ ] **Isolation**: Ensure `debug` and unsigned workflows remain unaffected.

### 2. Documentation & Process (Issue #2)
- [ ] **Two-Step Setup**: Documentation clearly outlines the two steps:
    1.  **Set up keys**: (Local: `local.properties` / CI: GitHub Secrets).
    2.  **Run build**: Execute `./gradlew assembleRelease`.
- [ ] **CI Instructions**: Include instructions for Base64 encoding/decoding the keystore file for use in GitHub Actions.
- [ ] **Verification Workflow**: A documented process exists for validating the install and update (overwriting an existing build) to confirm the signing identity is consistent.

## Acceptance Criteria

### Technical AC
- [ ] A release build can be produced with signing enabled using the documented inputs.
- [ ] `assembleRelease` fails gracefully if signing properties are missing for a release build.

### Process AC
- [ ] A developer can produce and install a signed release APK by following the updated documentation.
- [ ] The update workflow (installing over an existing build) is verified to work without requiring a manual uninstall.

## How to Apply
1. **Update Convention Plugin**: Implement the dual-source property loading in `build-logic:convention`.
2. **Define Property Keys**: Standardize on keys: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
3. **Update Docs**: Add a "Release Signing" section to `README.md` or `docs/DEPLOYMENT.md` explaining the local and CI setup.
