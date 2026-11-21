package io.github.ilievi187.pingmap.net

import io.github.ilievi187.pingmap.model.ServerTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun customServersFile(): File {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, ".config/pingmap")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "custom_servers.json")
}

actual suspend fun probeTcpLatency(host: String, port: Int, timeoutMs: Int): Double? =
    withContext(Dispatchers.IO) {
        try {
            val startNs = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsedNs = System.nanoTime() - startNs
            elapsedNs / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

actual fun loadCustomServers(): List<ServerTarget> {
    val file = customServersFile()
    if (!file.isFile) return emptyList()
    return try {
        json.decodeFromString<List<ServerTarget>>(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }
}

actual fun saveCustomServers(servers: List<ServerTarget>) {
    try {
        customServersFile().writeText(json.encodeToString(servers), Charsets.UTF_8)
    } catch (_: Exception) {
        // Ignore storage errors cleanly
    }
}

actual fun openExternalUrl(url: String) {
    try {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        when {
            os.contains("linux") -> ProcessBuilder("xdg-open", url).start()
            Desktop.isDesktopSupported() -> Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
        // Ignore browser open failure
    }
}

actual fun platformName(): String {
    val os = System.getProperty("os.name") ?: "Desktop"
    return when {
        os.contains("Linux", ignoreCase = true) -> "Linux"
        os.contains("Windows", ignoreCase = true) -> "Windows"
        os.contains("Mac", ignoreCase = true) -> "macOS"
        else -> os
    }
}
