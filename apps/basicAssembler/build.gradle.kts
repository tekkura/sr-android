plugins{
    alias(libs.plugins.oist.application)
}

android {
    namespace = "jp.oist.abcvlib.basicassembler"

    buildFeatures {
        viewBinding = true
    }
}
