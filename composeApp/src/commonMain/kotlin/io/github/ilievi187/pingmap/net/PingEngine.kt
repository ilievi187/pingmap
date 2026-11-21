package io.github.ilievi187.pingmap.net

import io.github.ilievi187.pingmap.model.ServerTarget

data class DnsLookupResult(
    val host: String,
    val addresses: List<String>,
    val latencyMs: Double?,
    val error: String? = null,
)

/**
 * Measures real TCP connect round-trip latency in milliseconds.
 * Works across Linux, Windows, and Android without requiring root/ICMP permissions.
 * Returns null if the connection times out or fails (counted as packet loss).
 */
expect suspend fun probeTcpLatency(host: String, port: Int, timeoutMs: Int = 1800): Double?

/**
 * Resolves a hostname via system DNS and returns resolved IP addresses plus lookup duration in ms.
 */
expect suspend fun resolveDnsHost(hostname: String): DnsLookupResult

/**
 * Exports a text/CSV telemetry report to local storage and returns the saved file path, or null on failure.
 */
expect fun exportTextReport(fileName: String, content: String): String?

expect fun loadCustomServers(): List<ServerTarget>

expect fun saveCustomServers(servers: List<ServerTarget>)

expect fun openExternalUrl(url: String)

expect fun platformName(): String
