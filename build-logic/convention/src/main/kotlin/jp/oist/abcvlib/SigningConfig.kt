package jp.oist.abcvlib

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import java.io.File

private const val RELEASE_STORE_FILE = "RELEASE_STORE_FILE"
private const val RELEASE_STORE_PASSWORD = "RELEASE_STORE_PASSWORD"
private const val RELEASE_KEY_ALIAS = "RELEASE_KEY_ALIAS"
private const val RELEASE_KEY_PASSWORD = "RELEASE_KEY_PASSWORD"
private const val SIGNING_CONFIG_NAME = "release"

internal fun Project.configureSigningConfig(
    commonExtension: ApplicationExtension,
) {
    commonExtension.apply {
        val storeFileProperty = providers.gradleProperty(RELEASE_STORE_FILE)
            .orElse(providers.environmentVariable(RELEASE_STORE_FILE))
        val storePasswordProperty = providers.gradleProperty(RELEASE_STORE_PASSWORD)
            .orElse(providers.environmentVariable(RELEASE_STORE_PASSWORD))
        val keyAliasProperty = providers.gradleProperty(RELEASE_KEY_ALIAS)
            .orElse(providers.environmentVariable(RELEASE_KEY_ALIAS))
        val keyPasswordProperty = providers.gradleProperty(RELEASE_KEY_PASSWORD)
            .orElse(providers.environmentVariable(RELEASE_KEY_PASSWORD))

        val isSigningConfigured = storeFileProperty.isPresent &&
                storePasswordProperty.isPresent &&
                keyAliasProperty.isPresent &&
                keyPasswordProperty.isPresent

        signingConfigs {
            create(SIGNING_CONFIG_NAME) {
                if (isSigningConfigured) {
                    storeFile = File(storeFileProperty.get())
                    storePassword = storePasswordProperty.get()
                    keyAlias = keyAliasProperty.get()
                    keyPassword = keyPasswordProperty.get()
                }
            }
        }

        buildTypes {
            getByName(SIGNING_CONFIG_NAME) {
                if (isSigningConfigured)
                    signingConfig = signingConfigs.getByName(SIGNING_CONFIG_NAME)
                else println("WARNING: Release signing properties are missing. The release build will not be signed.")
            }
        }
    }
}