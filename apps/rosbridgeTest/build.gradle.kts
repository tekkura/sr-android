plugins {
    alias(libs.plugins.oist.application)
}

android {
    namespace = "jp.oist.abcvlib.rosbridge"

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.okhttp)
}
