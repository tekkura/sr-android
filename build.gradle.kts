import groovy.json.JsonSlurper

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24") // needed for YuvToRgbConverter
        classpath("de.undercouch:gradle-download-task:5.3.1")
    }
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

    repositories {
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
        google()
        jcenter()
        maven {
            url = uri("https://maven.pkg.github.com/topherbuckley/smartphone-robot-flatbuffers")
            credentials {
                username = System.getenv("GITHUB_USER")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }

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

    dependencies {
        "implementation"("androidx.annotation:annotation:1.2.0")
        "implementation"("androidx.constraintlayout:constraintlayout:2.1.0")
        "implementation"("androidx.lifecycle:lifecycle-livedata:2.3.1")
        "implementation"("androidx.lifecycle:lifecycle-viewmodel:2.3.1")
        "implementation"("androidx.appcompat:appcompat:1.3.1")
        "implementation"("androidx.activity:activity:1.3.1")
        "implementation"("androidx.fragment:fragment:1.3.6")
        "implementation"("androidx.core:core-ktx:1.17.0")
        "implementation"("androidx.navigation:navigation-fragment:2.3.5")
        "implementation"("androidx.navigation:navigation-ui:2.3.5")
        "implementation"("org.tensorflow:tensorflow-lite-task-vision:0.4.0")
        "implementation"("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.0")
        "implementation"("org.tensorflow:tensorflow-lite-gpu:2.9.0")

        // CameraX core library using the camera2 implementation
        "implementation"("androidx.camera:camera-camera2:1.1.0-alpha07")
        // If you want to additionally use the CameraX Lifecycle library
        "implementation"("androidx.camera:camera-lifecycle:1.1.0-alpha07")
        // If you want to additionally use the CameraX View class
        "implementation"("androidx.camera:camera-view:1.0.0-alpha27")
        // If you want to additionally use the CameraX Extensions library
        "implementation"("androidx.camera:camera-extensions:1.0.0-alpha27")

        "implementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.5.21")
        "implementation"("com.google.flatbuffers:flatbuffers-java:1.12.0")
        "implementation"("com.google.android.material:material:1.4.0")
        "implementation"("com.google.zxing:core:3.4.1")
        "implementation"("com.google.zxing:android-integration:3.3.0")
        "implementation"("io.github.nishkarsh:android-permissions:2.0.54")

        "implementation"("com.github.mik3y:usb-serial-for-android:3.6.0")
        "implementation"("jp.oist.abcvlib.core.learning:fbclasses:0.0.1")
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