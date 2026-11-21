package io.github.ilievi187.pingmap

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ilievi187.pingmap.model.DefaultServers
import io.github.ilievi187.pingmap.ui.PingMapDashboardApp

fun main(args: Array<String>) {
    if ("--smoke-test" in args) {
        println("PingMap 1.1.0 smoke-test OK (${DefaultServers.preset.size} built-in servers)")
        return
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "PingMap — Live Gaming & Network Latency Monitor",
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        ) {
            PingMapDashboardApp()
        }
    }
}
