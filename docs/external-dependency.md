# External Dependency

Add JitPack to your Gradle repositories:

```gradle
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

Add the dependency:

```gradle
dependencies {
    implementation("com.github.oist.smartphone-robot-android:abcvlib:<jitpack-version>")
}
```

Notes:

- Public consumers do not need GitHub Packages credentials.
- [JitPack](https://jitpack.io/#oist/smartphone-robot-android) shows available versions.
- Use the version string shown by JitPack for the release you want.
