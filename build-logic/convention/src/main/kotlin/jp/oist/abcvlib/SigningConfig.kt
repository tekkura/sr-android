package jp.oist.abcvlib

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
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

    // Helper to get property from local.properties, then env
    fun getProperty(key: String): String? {
        return localProperties.getProperty(key)
            ?: providers.environmentVariable(key).orNull
    }

    commonExtension.apply {
        val storeFileProperty = getProperty(RELEASE_STORE_FILE)
        val storePasswordProperty = getProperty(RELEASE_STORE_PASSWORD)
        val keyAliasProperty = getProperty(RELEASE_KEY_ALIAS)
        val keyPasswordProperty = getProperty(RELEASE_KEY_PASSWORD)

        val missingKeys = mutableListOf<String>()
        if (storeFileProperty.isNullOrBlank()) missingKeys.add(RELEASE_STORE_FILE)
        if (storePasswordProperty.isNullOrBlank()) missingKeys.add(RELEASE_STORE_PASSWORD)
        if (keyAliasProperty.isNullOrBlank()) missingKeys.add(RELEASE_KEY_ALIAS)
        if (keyPasswordProperty.isNullOrBlank()) missingKeys.add(RELEASE_KEY_PASSWORD)

        val isSigningConfigured = missingKeys.isEmpty()

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
                if (isSigningConfigured) {
                    signingConfig = signingConfigs.getByName(SIGNING_CONFIG_NAME)
                }
            }
        }

        /**
         * Task-graph aware detection.
         * Note: This catches commands like './gradlew build' or './gradlew assemble' because
         * 'allTasks' contains the expanded set of sub-tasks (e.g., :app:assembleRelease, :app:packageRelease).
         */
        gradle.taskGraph.whenReady {
            val hasReleaseTask = allTasks.any { task ->
                task.project == this@configureSigningConfig &&
                        (task.name.contains("Release", ignoreCase = true) || task.name.contains("Publish", ignoreCase = true))
            }

            if (hasReleaseTask && !isSigningConfigured) {
                error("Release signing configuration is incomplete for project '${this@configureSigningConfig.name}'. " +
                        "Missing keys: ${missingKeys.joinToString()}. " +
                        "Please provide these values in 'local.properties' or as environment variables.")
            }
        }
    }
}