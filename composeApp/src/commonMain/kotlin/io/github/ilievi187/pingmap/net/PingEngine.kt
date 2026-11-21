package io.github.ilievi187.pingmap.net

import io.github.ilievi187.pingmap.model.ServerTarget

/**
 * Measures real TCP connect round-trip latency in milliseconds.
 * Works across Linux, Windows, and Android without requiring root/ICMP permissions.
 * Returns null if the connection times out or fails (counted as packet loss).
 */
expect suspend fun probeTcpLatency(host: String, port: Int, timeoutMs: Int = 1800): Double?

expect fun loadCustomServers(): List<ServerTarget>

expect fun saveCustomServers(servers: List<ServerTarget>)

expect fun openExternalUrl(url: String)

expect fun platformName(): String
