plugins {
    alias(libs.plugins.oist.application)
}

android {
    namespace = "jp.oist.abcvlib.kidsfacedemo"

    buildFeatures {
        viewBinding = true
    }
}
