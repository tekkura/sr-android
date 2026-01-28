// build-logic pattern from nowinandroid

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "jp.oist.abcvlib"

java {
    val jvm = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = jvm
    targetCompatibility = jvm
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}


dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {

    }
}
