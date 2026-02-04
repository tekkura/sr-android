import jp.oist.abcvlib.AppVersioning.gitHash
import jp.oist.abcvlib.AppVersioning.isDirty
import jp.oist.abcvlib.AppVersioning.isTagged
import jp.oist.abcvlib.AppVersioning.scmTag
import jp.oist.abcvlib.ModelDownload
import jp.oist.abcvlib.loadNetworkConfig

plugins {
    alias(libs.plugins.oist.library)
    `maven-publish`
    signing
}

dependencies {
    // Ui
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    // Architecture
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    // IOIOLibAndroid
    api(libs.ioio.android)
    implementation(libs.ioio.accessory)
    implementation(libs.ioio.bluetooth)
    implementation(libs.ioio.device)
    // CameraX
    api(libs.androidx.camera.camera2)
    api(libs.androidx.camera.lifecycle)
    api(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    // TensorFlow Lite
    api(libs.tensorflow.lite.task.vision)
    implementation(libs.tensorflow.lite.gpu.delegate.plugin)
    implementation(libs.tensorflow.lite.gpu)
    // ZXing (QR/Barcode)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.integration)
    // Other
    implementation(libs.usb.serial)
    implementation(libs.commons.collections4)
    api(libs.flatbuffers)
    api(libs.android.permissions)
    api(libs.abcvlib.fbclasses)
}

android {
    namespace = "jp.oist.abcvlib.core"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val networkConfig = loadNetworkConfig(rootDir)
        buildConfigField("String", "IP", "\"${networkConfig.ip}\"")
        buildConfigField("int", "PORT", "${networkConfig.port}")
    }
}

// Download default models; if you wish to use your own models then
// place them in the "assets" directory and comment out this line.
ModelDownload.configure(project)

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
