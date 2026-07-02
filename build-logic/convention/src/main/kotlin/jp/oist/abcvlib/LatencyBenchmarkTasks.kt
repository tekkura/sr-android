
package jp.oist.abcvlib

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

fun Project.configureLatencyBenchmarkTasks() {
    val devicePath = "/sdcard/Download/benchmark_results.md"
    val latestFile = rootProject.file("docs/benchmarks/latency/latest.md")
    val historyFile = rootProject.file("docs/benchmarks/latency/history.csv")
    val plotFile = rootProject.file("docs/benchmarks/latency/plot.svg")

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
            updateLatencyBenchmarkHistory(latestFile, historyFile)
        }
    }

    tasks.register("generateLatencyBenchmarkPlot") {
        group = "verification"
        description = "Generate docs/benchmarks/latency/plot.svg from docs/benchmarks/latency/history.csv."

        doLast {
            generateLatencyBenchmarkPlot(historyFile, plotFile)
        }
    }

    tasks.register("runLatencyBenchmark") {
        group = "verification"
        description = "Run the latency benchmark and sync docs/benchmarks/latency/latest.md, history.csv, and plot.svg."
        dependsOn("connectedDebugAndroidTest")

        doLast {
            pullLatencyBenchmarkResult(latestFile, devicePath)
            updateLatencyBenchmarkHistory(latestFile, historyFile)
            generateLatencyBenchmarkPlot(historyFile, plotFile)
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

private fun updateLatencyBenchmarkHistory(latestFile: File, historyFile: File) {
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
        return

    historyFile.appendText(latestRow + System.lineSeparator())
}

private fun generateLatencyBenchmarkPlot(historyFile: File, plotFile: File) {
    if (!historyFile.exists()) {
        throw GradleException("Missing benchmark history: ${historyFile.absolutePath}")
    }

    val rows = parseLatencyBenchmarkHistory(historyFile)
    if (rows.isEmpty()) {
        throw GradleException("Benchmark history is empty: ${historyFile.absolutePath}")
    }

    if (!plotFile.parentFile.exists()) {
        plotFile.parentFile.mkdirs()
    }
    plotFile.writeText(renderLatencyBenchmarkPlot(rows))
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

private data class LatencyBenchmarkHistoryRow(
    val commit: String,
    val generatedUtc: String,
    val runner: String,
    val usesRealFirmware: String,
    val firmware: String,
    val measuredIterations: Int,
    val successRate: Double,
    val meanRtt: Double,
    val p95Rtt: Double,
    val maxRtt: Double,
)

private fun parseLatencyBenchmarkHistory(file: File): List<LatencyBenchmarkHistoryRow> {
    val lines = file.readLines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()

    val header = lines.first()
    if (header != LATENCY_BENCHMARK_HISTORY_HEADER) {
        throw GradleException("Unexpected benchmark history header: $header")
    }

    return lines.drop(1).map { line ->
        val columns = line.split(",")
        if (columns.size != 10) {
            throw GradleException("Unexpected benchmark history row: $line")
        }
        LatencyBenchmarkHistoryRow(
            commit = columns[0],
            generatedUtc = columns[1],
            runner = columns[2],
            usesRealFirmware = columns[3],
            firmware = columns[4],
            measuredIterations = columns[5].toInt(),
            successRate = columns[6].toDouble(),
            meanRtt = columns[7].toDouble(),
            p95Rtt = columns[8].toDouble(),
            maxRtt = columns[9].toDouble(),
        )
    }
}

private fun renderLatencyBenchmarkPlot(rows: List<LatencyBenchmarkHistoryRow>): String {
    val width = 1200
    val height = 720
    val leftPad = 90
    val rightPad = 50
    val topPad = 112
    val bottomPad = 130
    val plotWidth = width - leftPad - rightPad
    val plotHeight = height - topPad - bottomPad

    val series = rows.mapIndexed { index, row ->
        SeriesPoint(
            index = index,
            commit = row.commit.take(8),
            generatedUtc = row.generatedUtc,
            meanRtt = row.meanRtt,
            p95Rtt = row.p95Rtt,
        )
    }

    val yValues = series.flatMap { listOf(it.meanRtt, it.p95Rtt) }
    val maxValue = max(5.0, yValues.maxOrNull() ?: 0.0)
    val minValue = 0.0
    val tickStep = chooseTickStep(maxValue)
    val maxTick = kotlin.math.ceil(maxValue / tickStep) * tickStep
    val xStep = if (series.size <= 1) 0.0 else plotWidth.toDouble() / (series.size - 1).toDouble()

    fun xFor(index: Int): Double = if (series.size == 1) leftPad + plotWidth / 2.0 else leftPad + index * xStep
    fun yFor(value: Double): Double {
        val clamped = min(max(value, minValue), maxTick)
        return topPad + plotHeight - (clamped / maxTick) * plotHeight
    }

    val meanPoints = series.joinToString(" ") { "${formatNumber(xFor(it.index))},${formatNumber(yFor(it.meanRtt))}" }
    val p95Points = series.joinToString(" ") { "${formatNumber(xFor(it.index))},${formatNumber(yFor(it.p95Rtt))}" }

    val axes = buildString {
        val tickCount = (maxTick / tickStep).toInt()
        for (tick in 0..tickCount) {
            val value = tick * tickStep
            val y = yFor(value)
            append("""<line x1="$leftPad" y1="${formatNumber(y)}" x2="${width - rightPad}" y2="${formatNumber(y)}" stroke="#1f2937" stroke-width="1"/>""")
            appendLine()
            append("""<text x="${leftPad - 12}" y="${formatNumber(y + 4)}" fill="#94a3b8" font-family="Arial, Helvetica, sans-serif" font-size="12" text-anchor="end">${formatTick(value)}</text>""")
            appendLine()
        }
    }

    val meanDots = series.joinToString("\n") {
        """<circle cx="${formatNumber(xFor(it.index))}" cy="${formatNumber(yFor(it.meanRtt))}" r="5" fill="#38bdf8"/>"""
    }
    val p95Dots = series.joinToString("\n") {
        """<circle cx="${formatNumber(xFor(it.index))}" cy="${formatNumber(yFor(it.p95Rtt))}" r="5" fill="#f97316"/>"""
    }

    val xLabels = buildString {
        series.forEach { point ->
            val x = xFor(point.index)
            append("""<text x="${formatNumber(x)}" y="${height - 104}" fill="#e2e8f0" font-family="Arial, Helvetica, sans-serif" font-size="12" text-anchor="middle">${escapeXml(point.commit)}</text>""")
            appendLine()
            append("""<text x="${formatNumber(x)}" y="${height - 84}" fill="#94a3b8" font-family="Arial, Helvetica, sans-serif" font-size="11" text-anchor="middle">${escapeXml(point.generatedUtc.take(10))}</text>""")
            appendLine()
        }
    }

    return """
<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-labelledby="title desc">
  <title id="title">Latency benchmark trend</title>
  <desc id="desc">A deterministic line chart of mean and p95 total round-trip latency over benchmark commits.</desc>
  <rect width="$width" height="$height" fill="#0f172a"/>
  <rect x="24" y="24" width="${width - 48}" height="${height - 48}" rx="20" fill="#111827" stroke="#334155" stroke-width="2"/>
  <text x="56" y="76" fill="#f8fafc" font-family="Arial, Helvetica, sans-serif" font-size="30" font-weight="700">Latency benchmark trend</text>
  <text x="56" y="104" fill="#cbd5e1" font-family="Arial, Helvetica, sans-serif" font-size="14">Generated from docs/benchmarks/latency/history.csv</text>
  <rect x="${width - 350}" y="48" width="300" height="72" rx="14" fill="#0f172a" stroke="#334155" stroke-width="1.5"/>
  <circle cx="${width - 320}" cy="76" r="6" fill="#38bdf8"/>
  <text x="${width - 300}" y="81" fill="#e2e8f0" font-family="Arial, Helvetica, sans-serif" font-size="13">Mean RTT</text>
  <circle cx="${width - 220}" cy="76" r="6" fill="#f97316"/>
  <text x="${width - 200}" y="81" fill="#e2e8f0" font-family="Arial, Helvetica, sans-serif" font-size="13">P95 RTT</text>

  <line x1="$leftPad" y1="${topPad + plotHeight}" x2="${width - rightPad}" y2="${topPad + plotHeight}" stroke="#64748b" stroke-width="2"/>
  <line x1="$leftPad" y1="$topPad" x2="$leftPad" y2="${topPad + plotHeight}" stroke="#64748b" stroke-width="2"/>
  $axes

  <polyline fill="none" stroke="#38bdf8" stroke-width="4" stroke-linejoin="round" stroke-linecap="round" points="$meanPoints"/>
  <polyline fill="none" stroke="#f97316" stroke-width="4" stroke-linejoin="round" stroke-linecap="round" points="$p95Points"/>
  $meanDots
  $p95Dots

  <text x="${width / 2}" y="${height - 58}" fill="#94a3b8" font-family="Arial, Helvetica, sans-serif" font-size="12" text-anchor="middle">Commits</text>
  <text x="46" y="${topPad + plotHeight / 2}" fill="#94a3b8" font-family="Arial, Helvetica, sans-serif" font-size="12" text-anchor="middle" transform="rotate(-90 46 ${topPad + plotHeight / 2})">RTT ms</text>
  $xLabels
</svg>
""".trimIndent()
}

private data class SeriesPoint(
    val index: Int,
    val commit: String,
    val generatedUtc: String,
    val meanRtt: Double,
    val p95Rtt: Double,
)

private fun chooseTickStep(maxValue: Double): Double {
    val candidates = listOf(1.0, 2.0, 5.0, 10.0, 20.0)
    val targetTicks = 6.0
    return candidates.minBy { kotlin.math.abs((maxValue / it) - targetTicks) }
}

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun formatTick(value: Double): String = String.format(Locale.US, "%.0f", value)
private fun escapeXml(value: String): String = value.replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

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
    ).find(text)?.groupValues
        ?: throw GradleException("Failed to parse Total RTT row from latest.md")

    return LatencyBenchmarkSummary(
        measuredIterations = iterations,
        successRate = successRate,
        meanRtt = totalRow[1],
        p95Rtt = totalRow[4],
        maxRtt = totalRow[3],
    )
}

private const val LATENCY_BENCHMARK_HISTORY_HEADER = "commit,generated_utc,runner,uses_real_firmware,firmware,measured_iterations,success_rate,mean_rtt_ms,p95_rtt_ms,max_rtt_ms"
