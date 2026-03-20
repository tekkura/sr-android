## Release Signing

To produce a signed release APK, you need to provide the signing credentials. This project supports a dual-source strategy for loading these credentials. The build logic checks `local.properties` first for each signing property and falls back to Environment Variables if a value is missing.

### 1. Set up keys

#### Local Development
Add the following properties to your `local.properties` file (this file is git-ignored):
```properties
RELEASE_STORE_FILE=/path/to/your/keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```
Alternatively, you can add them to your Environment Variables.
*Note: `RELEASE_STORE_FILE` should be an absolute path or relative to the project root.*

#### GitHub Actions (CI)
For automated releases via GitHub Actions, you should do the following:

1. **Base64 encode your keystore file**:
   - Linux: `base64 -w 0 your_keystore.jks > keystore_base64.txt`
   - macOS: `base64 your_keystore.jks | tr -d '\n' > keystore_base64.txt`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes('your_keystore.jks')) | Out-File -Encoding ASCII keystore_base64.txt` (or use git bash)
2. **Obtain the SHA-256 certificate fingerprint**:
   `keytool -list -v -keystore your_keystore.jks -alias your_key_alias`
   Extract the `SHA256` value from the output (e.g., `FA:C6:17:...`).
3. **Create GitHub Secrets**:
   - `RELEASE_KEYSTORE_BASE64`: The content of `keystore_base64.txt`.
   - `RELEASE_STORE_PASSWORD`: Your keystore password.
   - `RELEASE_KEY_ALIAS`: Your key alias.
   - `RELEASE_KEY_PASSWORD`: Your key password.
   - `RELEASE_CERTIFICATE_SHA256`: The SHA-256 fingerprint obtained in step 2. This is used by CI to verify that the produced APKs were signed with the correct identity.

These secrets will be injected as environment variables during the build process.
The `automated-release.yml` workflow is pre-configured to decode the keystore to a file named `release.jks` and set the `RELEASE_STORE_FILE` environment variable accordingly at runtime.
```
echo "$ENCODED_STRING" | base64 --decode > $RELEASE_STORE_FILE
```

### 2. Run build

#### Local Development
Once the properties are set, you can build a signed release APK using:
```bash
./gradlew assembleRelease
```
The build will use these credentials to sign the APK. If any properties are missing for a release build, the build will **fail** with an error message identifying the missing keys.

#### GitHub Actions (CI)
Signed releases are automated via the `automated-release.yml` workflow. This workflow is triggered when a version tag (e.g., `v1.2.3`) is pushed to the repository:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The CI job will automatically decode the keystore, inject the secrets as environment variables, build the signed APKs for all applications, verify their signing identity against `RELEASE_CERTIFICATE_SHA256`, and create a new GitHub Release with the artifacts attached.

### 3. Verification

#### Signature Identity Validation
To verify that an APK has been correctly signed with the expected certificate, use `apksigner` (found in the Android SDK `build-tools` directory):

```bash
apksigner verify --print-certs -v path/to/your-app-release.apk
```

The output should contain a `SHA-256 digest` value that matches the expected fingerprint of your release certificate.

#### Install/Update Validation
Confirm that the signing identity is consistent by attempting to install a new version of the APK over a previously installed one. If the signatures match, the update will succeed without needing a manual uninstall.

1.  **Install the previous release**:
    ```bash
    adb install previous-release.apk
    ```
2.  **Install the new release over it**:
    ```bash
    adb install -r new-release.apk
    ```

If the installation fails with a `INSTALL_FAILED_UPDATE_INCOMPATIBLE` error, the signing certificates do not match, and you must verify your keystore configuration.
