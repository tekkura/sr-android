
package jp.oist.abcvlib

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import java.io.File

fun Project.configureLatencyBenchmarkTasks() {
    val devicePath = "/sdcard/Download/benchmark_results.md"
    val latestFile = rootProject.file("docs/benchmarks/latency/latest.md")

    tasks.register<Exec>("pullLatencyBenchmarkResult") {
        group = "verification"
        description = "Pull the latest latency benchmark result into docs/benchmarks/latency/latest.md."

        doFirst {
            latestFile.parentFile.mkdirs()
        }

        commandLine("adb", "pull", devicePath, latestFile.absolutePath)
    }

    tasks.register("runLatencyBenchmark") {
        group = "verification"
        description = "Run the latency benchmark and sync docs/benchmarks/latency/latest.md, history.csv, and plot.svg."
        dependsOn("connectedDebugAndroidTest")

        doLast {
            pullLatencyBenchmarkResult(latestFile, devicePath)
        }
    }
}

private fun pullLatencyBenchmarkResult(latestFile: File, devicePath: String) {
    latestFile.parentFile.mkdirs()
    val command = if (System.getProperty("os.name").contains("Windows", ignoreCase = true))
        listOf("cmd", "/c", "adb", "pull", devicePath, latestFile.absolutePath)
    else listOf("adb", "pull", devicePath, latestFile.absolutePath)

    val exitCode = ProcessBuilder(command)
        .inheritIO()
        .directory(latestFile.parentFile)
        .start()
        .waitFor()

    if (exitCode != 0) {
        throw GradleException("adb pull failed with exit code $exitCode")
    }
}