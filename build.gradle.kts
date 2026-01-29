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

        }

        ndkVersion = "21.0.6113669"
    }
}