import groovy.json.JsonSlurper
import org.gradle.kotlin.dsl.maven

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

val jsonFile = file("apps-config.json")
val jsonContent = JsonSlurper().parseText(jsonFile.readText()) as Map<*, *>

val androidLibs = listOf(
        "abcvlib"
)

gradle.extra["androidLibs"] = androidLibs

@Suppress("UNCHECKED_CAST")
val apps = jsonContent["apps"] as List<String>

gradle.extra["apps"] = apps

androidLibs.forEach { lib ->
    include(lib)
    project(":$lib").projectDir = file("libs/$lib")
}

apps.forEach { app ->
    include(app)
    project(":$app").projectDir = file("apps/$app")
}
