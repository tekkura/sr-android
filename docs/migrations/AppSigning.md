# Migration Guide: App Signing

This guide defines the requirements and checklist for the **AppSigning** milestone. It focuses on establishing a reproducible, documented, and secure release-signing workflow as defined in Issues #148 and #149.

## Milestone Goal
Establish a consistent release-signing configuration across all modules to eliminate manual overwrite/delete workflows during deployment and ensure reproducible builds locally and in CI.

## Implementation Strategy: Dual-Source Loading
To support both local and CI environments without committing secrets, the signing configuration must follow a **Dual-Source** strategy:
1.  **Local Development**: Properties are loaded from `local.properties` (which is git-ignored).
2.  **GitHub Actions**: Properties are loaded from **Environment Variables** (mapped from GitHub Secrets).

The Gradle logic should use the `providers` API to prioritize properties:
```kotlin
fun getProperty(key: String): String? {
    return localProperties.getProperty(key)
        ?: providers.environmentVariable(key).orNull
}

val storeFileProperty = getProperty("RELEASE_STORE_FILE")
```

## Concrete CI Implementation Pattern
To handle the signing in GitHub Actions without committing the keystore, use the following pattern:
1.  **Secret Management**: Store the keystore file as a Base64 encoded string in a GitHub Secret (e.g., `RELEASE_KEYSTORE_BASE64`).
2.  **Workflow Step**:
    ```yaml
    - name: Prepare Keystore
      run: |
        echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 --decode > release.jks
      env:
        RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}

    - name: Build with Gradle
      run: ./gradlew assembleRelease
      env:
        RELEASE_STORE_FILE: ${{ github.workspace }}/release.jks
        RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
        RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
        RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}

    - name: Cleanup Keystore
      run: rm -f release.jks
    ```
  - Variables can be declared at step or job level, depending on the context of their usage

## Checklist
### Phase 1. Migration Guide (Issue #140)
  - Create AppSigning.md file detailing the full migration plan

### Phase 2. Implementation (Issue #148)
- **No Secrets in Repo**: Verify `.jks`, `.keystore`, and plain-text passwords are not committed. Check `.gitignore`.
- **Dual-Source Loading**: Signing values (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) are loaded using the `localProperties.getProperty(key) ?: providers.environmentVariable(key).orNull` pattern.
- **Convention Plugin**: Signing logic is centralized in `:build-logic:convention` and applied to all app modules.
- **Graceful Failure**: The build provides a clear error message if properties are missing, rather than failing with a generic NullPointerException.
- **Isolation**: Ensure `debug` and unsigned workflows remain unaffected.
- **Automated Release** `automated-release.yml` should produce signed releases instead of unsigned ones

### Phase 3. Documentation & Process (Issue #149)
- **Two-Step Setup**: Documentation clearly outlines the two steps:
    1.  **Set up keys**: (Local: `local.properties` / CI: GitHub Secrets).
    2.  **Run build**: Execute `./gradlew assembleRelease` / run `automated-release.yml`.
- **CI Instructions**: Include instructions for Base64 encoding/decoding the keystore file for use in GitHub Actions.
- **Verification Workflow**: A documented process exists for validating the install and update (overwriting an existing build) to confirm the signing identity is consistent.

## Note for reviewer
- Each phase must be completed in its own PR that is only focused on one issue.

## Acceptance Criteria

### Technical AC
- A release build can be produced with signing enabled using the documented inputs.
- `assembleRelease` fails gracefully if signing properties are missing for a release build.
- **Automatable Check**: Verify the release artifact is signed by the expected certificate fingerprint using `apksigner`:
  ```bash
  apksigner verify --print-certs -v app-release.apk
  ```

### Process AC
- A developer can produce and install a signed release APK by following the updated documentation.
- **Automatable Check**: Verify the update workflow (installing over an existing build) succeeds:
  ```bash
  adb install previous-release.apk
  adb install new-release.apk
  ```

## How to Apply
1. **Update Convention Plugin**: Implement the dual-source property loading in `build-logic:convention`.
2. **Define Property Keys**: Standardize on keys: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
3. **Update Docs**: Add a "Release Signing" section to `README.md` or `docs/DEPLOYMENT.md` explaining the local and CI setup.
