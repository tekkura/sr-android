plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.undercouch.download) apply false
}

allprojects {
    // Check for GITHUB_USER and GITHUB_TOKEN environment variables
    if (System.getenv("GITHUB_USER").isNullOrEmpty() || System.getenv("GITHUB_TOKEN")
            .isNullOrEmpty()
    ) {
        throw GradleException("Environment variables GITHUB_USER and GITHUB_TOKEN must be set.")
    }
}

subprojects {
    // Configure Android block immediately after plugin application (not in afterEvaluate)
    pluginManager.withPlugin("com.android.library") {
        configureAndroidExtension()
    }
    pluginManager.withPlugin("com.android.application") {
        configureAndroidExtension()
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            jvmToolchain(17)
        }
    }
}

// In Kotlin DSL, android {} isn't directly available in subprojects {}
// because the extension type isn't known at compile time.
// Using pluginManager.withPlugin + configure<BaseExtension> as a workaround.
// TODO: Replace with build-logic convention plugins.
fun Project.configureAndroidExtension() {
    extensions.configure<com.android.build.gradle.BaseExtension>("android") {
        namespace = "jp.oist.abcvlib"
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        defaultConfig {
            compileSdkVersion(36)
            buildToolsVersion = "35.0.0"
            minSdk = 30
            targetSdk = 36
        }

        ndkVersion = "21.0.6113669"
    }
}