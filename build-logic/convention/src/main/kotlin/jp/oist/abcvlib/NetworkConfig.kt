package jp.oist.abcvlib

import groovy.json.JsonSlurper
import java.io.File

data class NetworkConfig(
    val ip: String,
    val port: Int
)

/**
 * Load network configuration from config.json or config.template.json
 */
fun loadNetworkConfig(rootDir: File): NetworkConfig {
    var jsonFile = File(rootDir, "config.json")
    if (!jsonFile.exists()) {
        jsonFile = File(rootDir, "config.template.json")
    }

    val json = JsonSlurper().parseText(jsonFile.readText()) as Map<*, *>
    val custom = json["CUSTOM"] as? Map<*, *>

    val networkConfig = if (custom != null) {
        NetworkConfig(
            ip = custom["ip"] as String,
            port = (custom["port"] as Number).toInt()
        )
    } else {
        val default = json["DEFAULT"] as Map<*, *>
        NetworkConfig(
            ip = default["ip"] as String,
            port = (default["port"] as Number).toInt()
        )
    }
    println("networkConfig - ip: ${networkConfig.ip}, port: ${networkConfig.port}")
    return networkConfig
}
