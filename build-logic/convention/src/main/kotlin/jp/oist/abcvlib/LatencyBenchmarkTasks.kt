package jp.oist.abcvlib

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import java.io.File

fun Project.configureLatencyBenchmarkTasks() {
    val devicePath = "/sdcard/Download/benchmark_results.md"
    val latestFile = rootProject.file("docs/benchmarks/latency/latest.md")
    val historyFile = rootProject.file("docs/benchmarks/latency/history.csv")

    tasks.register<Exec>("pullLatencyBenchmarkResult") {
        group = "verification"
        description = "Pull the latest latency benchmark result into docs/benchmarks/latency/latest.md."

        doFirst {
            latestFile.parentFile.mkdirs()
        }

        commandLine("adb", "pull", devicePath, latestFile.absolutePath)
    }

    tasks.register("updateLatencyBenchmarkHistory") {
        group = "verification"
        description = "Upsert the latest latency benchmark snapshot in docs/benchmarks/latency/history.csv."

        doLast {
            if (!latestFile.exists()) {
                throw GradleException("Missing benchmark snapshot: ${latestFile.absolutePath}")
            }

            val latestText = latestFile.readText()
            val latestMetadata = parseLatencyBenchmarkMetadata(latestText)
            val latestSummary = parseLatencyBenchmarkSummary(latestText)

            if (!historyFile.parentFile.exists()) {
                historyFile.parentFile.mkdirs()
            }
            if (!historyFile.exists()) {
                historyFile.writeText(LATENCY_BENCHMARK_HISTORY_HEADER + System.lineSeparator())
            }

            val existingLines = historyFile.readLines().filter { it.isNotBlank() }
            val header = existingLines.firstOrNull()
            if (header != null && header != LATENCY_BENCHMARK_HISTORY_HEADER) {
                throw GradleException("Unexpected benchmark history header: $header")
            }

            val latestRow = listOf(
                latestMetadata.commit,
                latestMetadata.generatedUtc,
                latestMetadata.runner,
                latestMetadata.usesRealFirmware,
                latestMetadata.firmware,
                latestSummary.measuredIterations,
                latestSummary.successRate,
                latestSummary.meanRtt,
                latestSummary.p95Rtt,
                latestSummary.maxRtt,
            ).joinToString(",")

            val (hash, time) = existingLines.drop(1)
                .lastOrNull()
                ?.split(",")
                .let { it?.getOrNull(0) to it?.getOrNull(1) }

            if (hash == latestMetadata.commit && time == latestMetadata.generatedUtc)
                return@doLast

            historyFile.appendText(latestRow + System.lineSeparator())
        }
    }

    tasks.register("pullLatencyBenchmark") {
        group = "verification"
        description = "Run the latency benchmark sync workflow for docs/benchmarks/latency/latest.md and history.csv."
    dependsOn("pullLatencyBenchmarkResult", "updateLatencyBenchmarkHistory")
    }
}

private data class LatencyBenchmarkMetadata(
    val commit: String,
    val generatedUtc: String,
    val runner: String,
    val usesRealFirmware: String,
    val firmware: String,
)

private data class LatencyBenchmarkSummary(
    val measuredIterations: String,
    val successRate: String,
    val meanRtt: String,
    val p95Rtt: String,
    val maxRtt: String,
)

private fun parseLatencyBenchmarkMetadata(text: String): LatencyBenchmarkMetadata {
    fun match(pattern: String): String? =
        Regex(pattern, RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    fun requireFirst(vararg patterns: String): String =
        patterns.firstNotNullOfOrNull { pattern -> match(pattern) }
            ?: throw GradleException(
                "Failed to parse benchmark metadata using any of patterns: ${patterns.joinToString()}"
            )

    fun firstOrDefault(defaultValue: String, vararg patterns: String): String =
        patterns.firstNotNullOfOrNull { pattern -> match(pattern) } ?: defaultValue

    val hardwareLoop = firstOrDefault(
        "No; uses virtual transport and simulated firmware",
        """^- Hardware loop: (.+)$"""
    )

    val usesRealFirmware = when {
        hardwareLoop.startsWith("Yes", ignoreCase = true) -> "TRUE"
        hardwareLoop.startsWith("No", ignoreCase = true) -> "FALSE"
        else -> firstOrDefault("FALSE", """^- Uses real firmware: (.+)$""")
    }

    return LatencyBenchmarkMetadata(
        commit = requireFirst(
            """^- Git commit: ([0-9a-fA-F]+)(?: \((?:dirty|clean)\))?$""",
            """^- Repository commit: `([^`]+)`$"""
        ),
        generatedUtc = requireFirst(
            """^- Generated at \(UTC\): (.+)$""",
            """^- Seeded at \(UTC\): (.+)$"""
        ),
        runner = firstOrDefault("unknown", """^- Runner: (.+)$"""),
        usesRealFirmware = usesRealFirmware,
        firmware = firstOrDefault("unknown", """^- Firmware: (.+)$"""),
    )
}

private fun parseLatencyBenchmarkSummary(text: String): LatencyBenchmarkSummary {
    val iterations = Regex("""^#{1,3} Benchmark Results \((\d+) iterations\)$""", RegexOption.MULTILINE)
        .find(text)
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Failed to parse benchmark iteration count from latest.md")
    val successRate = Regex("""^Success Rate: ([0-9.]+)% \(\d+/\d+\)$""", RegexOption.MULTILINE)
        .find(text)
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Failed to parse benchmark success rate from latest.md")
    val totalRow = Regex(
        """^\| Total RTT\s+\| ([0-9.]+)\s+\| ([0-9.]+)\s+\| ([0-9.]+)\s+\| ([0-9.]+)\s+\|$""",
        RegexOption.MULTILINE
    ).find(text)?.groupValues ?: throw GradleException("Failed to parse Total RTT row from latest.md")

    return LatencyBenchmarkSummary(
        measuredIterations = iterations,
        successRate = successRate,
        meanRtt = totalRow[1],
        p95Rtt = totalRow[4],
        maxRtt = totalRow[3],
    )
}

private const val LATENCY_BENCHMARK_HISTORY_HEADER = "commit,generated_utc,runner,uses_real_firmware,firmware,measured_iterations,success_rate,mean_rtt_ms,p95_rtt_ms,max_rtt_ms"
