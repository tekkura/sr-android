apply(plugin = "de.undercouch.download")
apply(from = rootProject.file("common-buildconfig.gradle"))

dependencies {
    api("com.github.ytai.ioio:IOIOLibAndroid:6.1.0")
    implementation("com.github.ytai.ioio:IOIOLibAndroidAccessory:6.1.0")
    implementation("com.github.ytai.ioio:IOIOLibAndroidBluetooth:6.1.0")
    implementation("com.github.ytai.ioio:IOIOLibAndroidDevice:6.1.0")
    implementation("io.github.nishkarsh:android-permissions:2.0.54")
    implementation("org.apache.commons:commons-collections4:4.4")
}

android {
    namespace = "jp.oist.abcvlib.core"
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        getByName("optimized") {
            // shrinkResources not allowed for libs → disable it
            isShrinkResources = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro")
            )
        }
    }
}

project.extra["ASSET_DIR"] = "${projectDir}/src/main/assets"

// Download default models; if you wish to use your own models then
// place them in the "assets" directory and comment out this line.
apply(from = "download_models.gradle")

tasks.withType<GenerateMavenPom>().configureEach {
    doFirst {
        if (isDirty()) {
            throw GradleException("Current working directory is dirty. Use git stash or commit all your local changes before publishing.")
        }
        if (!isTagged()) {
            throw GradleException("You have commits after the most recent tag. Please add a new tag to capture the patch or updates after most recent tag")
        }
    }
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/oist/smartphone-robot-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
        publications {
            create<MavenPublication>("debug") {
                from(components["debug"])
                groupId = project.group.toString()
                artifactId = "abcvlib"
                version = project.version.toString()
                pom {
                    description.set("A library for controlling the OIST smartphone robots")
                    developers {
                        developer {
                            id.set("topherbuckley")
                            name.set("Christopher Buckley")
                            email.set("topherbuckley@gmail.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/oist/smartphone-robot-android/commit/" + gitHash())
                        tag.set(scmTag())
                    }
                }
            }
        }
    }
}

/**
 * These functions are duplicated here temporarily because
 * Kotlin DSL scripts cannot directly access functions defined
 * in the root project's build.gradle.kts.
 *
 * TODO: Move to build-logic module once implemented
 */
fun getGitVersion(): String {
    return try {
        val longVersionName = "git describe --tags --long".runCommand().trim()
        val parts = longVersionName.split('-')

        if (parts.size >= 3) {
            val fullVersionTag = parts[0]
            val commitCount = parts[1].toIntOrNull() ?: 0

            // Release
            if (commitCount == 0) {
                fullVersionTag
            }
            // Quickfixes
            else {
                longVersionName
            }
        } else {
            longVersionName
        }
    } catch (e: Exception) {
        println("Warning: Failed to fetch git version. Error: ${e.message}")
        "0.0.0-0-unknown"
    }
}

fun scmTag(): String {
    return try {
        var gitVersion = System.getenv("VERSION") ?: "null"

        if (gitVersion == "null") {
            val processTag = "git describe --tags --dirty".runCommand().trim()
            val processHash = "git rev-parse HEAD".runCommand().trim()
            gitVersion = "${processTag}_${processHash}"
        } else {
            val gitVersionToken = gitVersion.split("/")
            gitVersion = if (gitVersionToken.size > 2) {
                gitVersionToken[2]
            } else {
                gitVersionToken[0]
            }
        }
        gitVersion
    } catch (e: Exception) {
        println("Warning: Failed to fetch SCM tag. Error: ${e.message}")
        "0-unknown"
    }
}

fun gitHash(): String {
    return try {
        "git rev-parse HEAD".runCommand().trim()
    } catch (e: Exception) {
        println("Warning: Failed to fetch git hash. Error: ${e.message}")
        "unknown"
    }
}

fun isDirty(): Boolean {
    return try {
        val dirtyString = "git describe --tags --dirty".runCommand()
            .trim()
            .split("-")
            .lastOrNull() ?: ""
        dirtyString == "dirty"
    } catch (e: Exception) {
        println("Warning: Failed to determine if repository is dirty. Error: ${e.message}")
        false
    }
}

fun isTagged(): Boolean {
    return try {
        val longVersionName = "git describe --tags --long".runCommand().trim()
        val parts = longVersionName.split('-')

        if (parts.size >= 3) {
            val commitCount = parts[1].toIntOrNull() ?: -1
            commitCount == 0
        } else {
            false
        }
    } catch (e: Exception) {
        println("Warning: Failed to determine if repository is tagged. Error: ${e.message}")
        false
    }
}

// Helper extension function to run shell commands
fun String.runCommand(): String {
    val process = Runtime.getRuntime().exec(this)
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output
}