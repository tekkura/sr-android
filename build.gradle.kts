import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.undercouch.download) apply false
}

allprojects {
    var jsonFile = file("$rootDir/config.json")
    if (!jsonFile.exists()) {
        jsonFile = file("$rootDir/config.template.json")
    }
    val pythonconfig = JsonSlurper().parseText(jsonFile.readText()) as Map<*, *>
    val custom = pythonconfig["CUSTOM"] as? Map<*, *>
    if (custom != null) {
        extra["ip"] = custom["ip"]
        extra["port"] = custom["port"]
    } else {
        val default = pythonconfig["DEFAULT"] as Map<*, *>
        extra["ip"] = default["ip"]
        extra["port"] = default["port"]
    }
    // Check for GITHUB_USER and GITHUB_TOKEN environment variables
    if (System.getenv("GITHUB_USER").isNullOrEmpty() || System.getenv("GITHUB_TOKEN")
            .isNullOrEmpty()
    ) {
        throw GradleException("Environment variables GITHUB_USER and GITHUB_TOKEN must be set.")
    }
}

subprojects {
    group = "jp.oist"
    version = getGitVersion()
    println("version = ${getGitVersion()}")
    extra["gitVersion"] = scmTag()
    extra["versionNamespace"] = "abcvlib"
    extra["versionString"] = "${extra["versionNamespace"]}$version"


    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    if ((gradle.extra["androidLibs"] as List<*>).any { it == project.name }) {
        apply(plugin = "com.android.library")
        apply(plugin = "kotlin-android") // needed for YuvToRgbConverter
    } else if ((gradle.extra["apps"] as List<*>).any { it == project.name }) {
        apply(plugin = "com.android.application")
        dependencies {
            "implementation"(project(":abcvlib"))
        }
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        // Navigation
        "implementation"(libs.findLibrary("androidx-navigation-fragment").get())
        "implementation"(libs.findLibrary("androidx-navigation-ui").get())
        // CameraX
        "implementation"(libs.findLibrary("androidx-camera-camera2").get())
        "implementation"(libs.findLibrary("androidx-camera-lifecycle").get())
        "implementation"(libs.findLibrary("androidx-camera-view").get())
        "implementation"(libs.findLibrary("androidx-camera-extensions").get())
        // TensorFlow Lite
        "implementation"(libs.findLibrary("tensorflow-lite-task-vision").get())
        "implementation"(libs.findLibrary("tensorflow-lite-gpu-delegate-plugin").get())
        "implementation"(libs.findLibrary("tensorflow-lite-gpu").get())
        // ZXing (QR/Barcode)
        "implementation"(libs.findLibrary("zxing-core").get())
        "implementation"(libs.findLibrary("zxing-android-integration").get())
        // Other
        "implementation"(libs.findLibrary("flatbuffers").get())
        "implementation"(libs.findLibrary("android-permissions").get())
        "implementation"(libs.findLibrary("usb-serial").get())
        "implementation"(libs.findLibrary("abcvlib-fbclasses").get())
    }

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

            val ip = rootProject.extra["ip"]
            val port = rootProject.extra["port"]
            buildConfigField("String", "IP", "\"$ip\"")
            buildConfigField("int", "PORT", "$port")

            // Fetch the version according to git latest tag and "how far are we from last tag"
            try {
                val longVersionName = "git -C $rootDir describe --tags --long --dirty".execute().trim()
                val (fullVersionTag, commitCount, gitSha, dirty) = longVersionName.split("-", limit = 4)
                val (versionMajor, versionMinor, versionPatch) = fullVersionTag.removePrefix("v").split(".")

                // Set the version name
                versionName = "$versionMajor.$versionMinor.$versionPatch-$commitCount-$dirty"

                // Turn the version name into a version code
                versionCode = versionMajor.toInt() * 100000 +
                        versionMinor.toInt() * 10000 +
                        versionPatch.toInt() * 1000 +
                        commitCount.toInt()

                println("versionName = $versionName")
                println("versionCode = $versionCode")

            } catch (e: Exception) {
                // Handle cases where there are no tags or the git command fails
                println("Warning: No tags present or failed to fetch version from git. Setting default version. Error: ${e.message}")
                // Set a default version name and version code
                versionName = "0.0.0-0-unknown"
                versionCode = 1
            }
        }

        ndkVersion = "21.0.6113669"
    }
}

fun String.execute(): String =
    Runtime.getRuntime().exec(this).inputStream.bufferedReader().readText()

fun getGitVersion(): String {
    return try {
        val longVersionName = "git describe --tags --long".execute().trim()
        val tokens = longVersionName.split("-")
        val fullVersionTag = tokens[0]
        val commitCount = tokens[1]
        // Release
        if (commitCount.toInt() == 0) {
            fullVersionTag
        }
        // Quickfixes
        else {
            longVersionName
        }
    } catch (e: Exception) {
        println("Warning: Failed to fetch git version. Error: ${e.message}")
        "0.0.0-0-unknown"
    }
}

fun scmTag(): String {
    return try {
        var gitVersion = System.getenv("VERSION")
        if (gitVersion.isNullOrEmpty() || gitVersion == "null") {
            val processTag = "git describe --tags --dirty".execute().trim()
            val processHash = "git rev-parse HEAD".execute().trim()
            gitVersion = "${processTag}_$processHash"
        } else {
            val gitVersionToken = gitVersion.split("/")
            gitVersion = if (gitVersionToken.size > 2) gitVersionToken[2] else gitVersionToken[0]
        }
        gitVersion
    } catch (e: Exception) {
        println("Warning: Failed to fetch SCM tag. Error: ${e.message}")
        "0-unknown"
    }
}

fun gitHash(): String {
    return try {
        "git rev-parse HEAD".execute().trim()
    } catch (e: Exception) {
        println("Warning: Failed to fetch git hash. Error: ${e.message}")
        "unknown"
    }
}

fun isDirty(): Boolean {
    return try {
        val dirtyString = "git describe --tags --dirty".execute().trim().split("-").last()
        dirtyString == "dirty"
    } catch (e: Exception) {
        println("Warning: Failed to determine if repository is dirty. Error: ${e.message}")
        false
    }
}

fun isTagged(): Boolean {
    return try {
        val longVersionName = "git describe --tags --long".execute().trim()
        val commitCount = longVersionName.split("-")[1]
        commitCount.toInt() == 0
    } catch (e: Exception) {
        println("Warning: Failed to determine if repository is tagged. Error: ${e.message}")
        false
    }
}