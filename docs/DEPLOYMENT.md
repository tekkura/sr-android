## Release Signing

To produce a signed release APK, you need to provide the signing credentials. This project supports a dual-source strategy for loading these credentials via Gradle properties (e.g., `local.properties`) or Environment Variables.

### 1. Set up keys

#### Local Development
Add the following properties to your `local.properties` file (this file is git-ignored) or `gradle.properties`:
```properties
RELEASE_STORE_FILE=/path/to/your/keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```
*Note: `RELEASE_STORE_FILE` should be an absolute path or relative to the project root.*

#### GitHub Actions (CI)
The build logic automatically looks for environment variables if Gradle properties are not found. For GitHub Actions, the following secrets should be configured:

1. **Base64 encode your keystore file**: 
   `base64 -w 0 your_keystore.jks > keystore_base64.txt`
2. **Create GitHub Secrets**:
   - `RELEASE_KEYSTORE_BASE64`: The content of `keystore_base64.txt`.
   - `RELEASE_STORE_PASSWORD`: Your keystore password.
   - `RELEASE_KEY_ALIAS`: Your key alias.
   - `RELEASE_KEY_PASSWORD`: Your key password.

The `automated-release.yml` workflow is pre-configured to decode the keystore to a file named `release.jks` and set the `RELEASE_STORE_FILE` environment variable accordingly.

### 2. Run build

#### Local Development
Once the properties are set, you can build a signed release APK using:
```bash
./gradlew assembleRelease
```
The build will use these credentials to sign the APK. If any properties are missing, the build will proceed but the output APK will **not** be signed (a warning will be displayed in the console).

#### GitHub Actions (CI)
Signed releases are automated via the `automated-release.yml` workflow. This workflow is triggered when a version tag (e.g., `v1.2.3`) is pushed to the repository:

```bash
git tag v1.2.3
git push origin v1.2.3
```

The CI job will automatically decode the keystore, inject the secrets as environment variables, build the signed APKs for all applications, and create a new GitHub Release with the artifacts attached.

### 3. Verification
Installing a signed APK over an existing one should work without requiring a manual uninstall, provided the signing identity (keystore and key) is consistent.
