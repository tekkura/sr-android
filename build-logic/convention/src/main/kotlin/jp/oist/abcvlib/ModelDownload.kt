package jp.oist.abcvlib

import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

object ModelDownload {

    private val models = mapOf(
        "model.tflite" to "https://github.com/oist/smartphone-robot-object-detection/releases/download/0.1.1/model.tflite",
        "efficientdet-lite0.tflite" to "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite",
        "efficientdet-lite1.tflite" to "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite1_detection_metadata_1.tflite"
    )

    fun configure(project: Project) {
        with(project) {
            pluginManager.apply(versionCatalog.plugin("undercouch-download"))

            val assetDir = "${projectDir}/src/main/assets"

            models.forEach { (name, url) ->
                tasks.register<Download>("download_${name.replace(".", "_")}") {
                    src(url)
                    dest("$assetDir/$name")
                    overwrite(false)
                }
            }

            tasks.register("downloadModelFiles") {
                dependsOn(models.keys.map { "download_${it.replace(".", "_")}" })
            }

            tasks.named("preBuild") {
                dependsOn("downloadModelFiles")
            }
        }
    }
}