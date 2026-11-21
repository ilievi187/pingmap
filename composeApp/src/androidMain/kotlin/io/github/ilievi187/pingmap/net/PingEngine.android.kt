package io.github.ilievi187.pingmap.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ilievi187.pingmap.model.ServerTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object AndroidAppContext {
    @Volatile
    var appContext: Context? = null
}

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun customServersFile(): File? {
    val ctx = AndroidAppContext.appContext ?: return null
    return File(ctx.filesDir, "custom_servers.json")
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

actual suspend fun resolveDnsHost(hostname: String): DnsLookupResult =
    withContext(Dispatchers.IO) {
        val clean = hostname.trim()
        if (clean.isEmpty()) {
            return@withContext DnsLookupResult(clean, emptyList(), null, "Enter a hostname")
        }
        try {
            val startNs = System.nanoTime()
            val addrs = InetAddress.getAllByName(clean).mapNotNull { it.hostAddress }.distinct()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            DnsLookupResult(clean, addrs, elapsedMs, null)
        } catch (e: Exception) {
            DnsLookupResult(clean, emptyList(), null, e.message ?: "Resolution failed")
        }
    }

actual fun exportTextReport(fileName: String, content: String): String? {
    val ctx = AndroidAppContext.appContext ?: return null
    return try {
        val dir = File(ctx.filesDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

actual fun loadCustomServers(): List<ServerTarget> {
    val file = customServersFile() ?: return emptyList()
    if (!file.isFile) return emptyList()
    return try {
        json.decodeFromString<List<ServerTarget>>(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }
}

actual fun saveCustomServers(servers: List<ServerTarget>) {
    val file = customServersFile() ?: return
    try {
        file.writeText(json.encodeToString(servers), Charsets.UTF_8)
    } catch (_: Exception) {
        // Ignore storage errors cleanly
    }
}

actual fun openExternalUrl(url: String) {
    val ctx = AndroidAppContext.appContext ?: return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {
        // Ignore if no browser available
    }
}

actual fun platformName(): String = "Android"
