package jp.oist.abcvlib

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import java.io.File
import java.util.Properties

private const val RELEASE_STORE_FILE = "RELEASE_STORE_FILE"
private const val RELEASE_STORE_PASSWORD = "RELEASE_STORE_PASSWORD"
private const val RELEASE_KEY_ALIAS = "RELEASE_KEY_ALIAS"
private const val RELEASE_KEY_PASSWORD = "RELEASE_KEY_PASSWORD"
private const val SIGNING_CONFIG_NAME = "release"

internal fun Project.configureSigningConfig(
    commonExtension: ApplicationExtension,
) {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    // Helper to get property from local.properties, then gradle properties, then env
    fun getProperty(key: String): String? {
        return localProperties.getProperty(key)
            ?: providers.environmentVariable(key).orNull
    }

    commonExtension.apply {
        val storeFileProperty = getProperty(RELEASE_STORE_FILE)
        val storePasswordProperty = getProperty(RELEASE_STORE_PASSWORD)
        val keyAliasProperty = getProperty(RELEASE_KEY_ALIAS)
        val keyPasswordProperty = getProperty(RELEASE_KEY_PASSWORD)

        val isSigningConfigured = storeFileProperty != null &&
                storePasswordProperty != null &&
                keyAliasProperty != null &&
                keyPasswordProperty != null

        signingConfigs {
            create(SIGNING_CONFIG_NAME) {
                if (isSigningConfigured) {
                    storeFile = rootProject.file(storeFileProperty!!)
                    storePassword = storePasswordProperty
                    keyAlias = keyAliasProperty
                    keyPassword = keyPasswordProperty
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