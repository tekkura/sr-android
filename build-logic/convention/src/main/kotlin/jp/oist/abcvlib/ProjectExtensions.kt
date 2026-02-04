package jp.oist.abcvlib

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val Project.versionCatalog
    get(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.version(name: String): String = findVersion(name).get().toString()

// Plugin id
fun VersionCatalog.plugin(name: String): String = findPlugin(name).get().get().pluginId

// SDK versions
val VersionCatalog.compileSdk: Int get() = version("compileSdk").toInt()
val VersionCatalog.targetSdk: Int get() = version("targetSdk").toInt()
val VersionCatalog.minSdk: Int get() = version("minSdk").toInt()
val VersionCatalog.buildTools: String get() = version("buildTools")

// Java version
fun VersionCatalog.javaVersion(): JavaVersion =
    JavaVersion.toVersion(version("jvmTarget"))

fun VersionCatalog.jvmTarget(): JvmTarget =
    JvmTarget.fromTarget(version("jvmTarget"))