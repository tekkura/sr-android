package jp.oist.abcvlib

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Configure base Kotlin with Android options
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = versionCatalog.compileSdk

        defaultConfig {
            minSdk = versionCatalog.minSdk
        }

        compileOptions {
            sourceCompatibility = versionCatalog.javaVersion()
            targetCompatibility = versionCatalog.javaVersion()
        }
    }

    configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            // Align Kotlin JVM target with Java compile options
            jvmTarget.set(versionCatalog.jvmTarget())
            // Enable experimental coroutines APIs (flatMapLatest, collectLatest, etc.)
            freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

