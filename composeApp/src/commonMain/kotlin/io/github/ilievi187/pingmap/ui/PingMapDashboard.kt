package io.github.ilievi187.pingmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ilievi187.pingmap.model.*
import io.github.ilievi187.pingmap.net.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PingMapDashboardApp() {
    val scope = rememberCoroutineScope()
    var customServers by remember { mutableStateOf(loadCustomServers()) }
    val allTargets = remember(customServers) { DefaultServers.preset + customServers }
    var statsMap by remember { mutableStateOf(allTargets.associate { it.id to ServerStats(it) }) }
    var selectedCategory by remember { mutableStateOf(GameCategory.CS2_FACEIT) }
    var selectedServerId by remember { mutableStateOf(DefaultServers.preset.first().id) }
    var isRunning by remember { mutableStateOf(true) }
    var intervalSec by remember { mutableStateOf(2) }
    var search by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var maintenanceFeature by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(allTargets) {
        statsMap = allTargets.associate { target ->
            target.id to (statsMap[target.id] ?: ServerStats(target))
        }
    }

    val pulse = rememberInfiniteTransition(label = "dashboardPulse")
    val pulseValue by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing)
        ),
        label = "pulse"
    )

    val visibleStats = remember(statsMap, selectedCategory, search) {
        statsMap.values
            .filter { selectedCategory == GameCategory.ALL || it.target.category == selectedCategory }
            .filter {
                search.isBlank() ||
                    it.target.name.contains(search, ignoreCase = true) ||
                    it.target.region.contains(search, ignoreCase = true) ||
                    it.target.host.contains(search, ignoreCase = true)
            }
            .sortedWith(compareBy<ServerStats> { it.avgMs == null }.thenBy { it.avgMs ?: Double.MAX_VALUE })
    }

    val selectedStats = statsMap[selectedServerId] ?: visibleStats.firstOrNull()
    val bestServer = visibleStats
        .filter { it.avgMs != null }
        .minWithOrNull(compareBy<ServerStats> { it.lossPercent > 1.0 }.thenBy { it.avgMs ?: Double.MAX_VALUE })

    suspend fun probeVisibleOnce() {
        val targets = allTargets.filter {
            selectedCategory == GameCategory.ALL || it.category == selectedCategory
        }
        if (targets.isEmpty()) return
        val results = coroutineScope {
            targets.map { t ->
                async { t.id to probeTcpLatency(t.host, t.port, timeoutMs = 1800) }
            }.awaitAll()
        }
        val next = statsMap.toMutableMap()
        for ((id, rtt) in results) {
            val current = next[id] ?: continue
            next[id] = current.appendSample(rtt, maxHistory = 48)
        }
        statsMap = next
    }

    LaunchedEffect(isRunning, selectedCategory, intervalSec, allTargets) {
        if (!isRunning) return@LaunchedEffect
        while (isRunning) {
            probeVisibleOnce()
            delay(intervalSec * 1000L)
        }
    }

    PingMapTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF070C13)
        ) {
            Row(Modifier.fillMaxSize()) {
                DashboardRail(
                    isRunning = isRunning,
                    pulse = pulseValue,
                    onAdd = { showAddDialog = true },
                    onMaintenance = { maintenanceFeature = it }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardHeader(
                        isRunning = isRunning,
                        intervalSec = intervalSec,
                        onToggleRun = { isRunning = !isRunning },
                        onRefresh = { scope.launch { probeVisibleOnce() } },
                        onInterval = { intervalSec = it }
                    )

                    BestSignalBanner(bestServer, pulseValue) {
                        selectedServerId = it.target.id
                    }

                    CategoryTabs(
                        selected = selectedCategory,
                        onSelect = {
                            selectedCategory = it
                            statsMap.values.firstOrNull { s ->
                                it == GameCategory.ALL || s.target.category == it
                            }?.let { s -> selectedServerId = s.target.id }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ServerListPanel(
                            modifier = Modifier.width(286.dp).fillMaxHeight(),
                            stats = visibleStats,
                            selectedId = selectedStats?.target?.id,
                            search = search,
                            onSearch = { search = it },
                            onSelect = { selectedServerId = it.target.id },
                            onDelete = { stats ->
                                val next = customServers.filterNot { it.id == stats.target.id }
                                customServers = next
                                saveCustomServers(next)
                            }
                        )

                        MainTelemetryPanel(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            stats = selectedStats,
                            pulse = pulseValue
                        )

                        SideInsightsPanel(
                            modifier = Modifier.width(304.dp).fillMaxHeight(),
                            stats = visibleStats,
                            selected = selectedStats,
                            onSelect = { selectedServerId = it.target.id }
                        )
                    }
                }
            }
        }
    }

    maintenanceFeature?.let { feature ->
        MaintenanceDialog(
            feature = feature,
            onDismiss = { maintenanceFeature = null }
        )
    }

    if (showAddDialog) {
        DashboardAddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, region, host, port ->
                val target = ServerTarget(
                    id = "custom-${host.lowercase()}-$port-${customServers.size + 1}",
                    name = name.trim(),
                    region = region.trim().ifBlank { "Custom" },
                    host = host.trim(),
                    port = port,
                    category = GameCategory.CUSTOM,
                    isCustom = true
                )
                val next = customServers + target
                customServers = next
                saveCustomServers(next)
                selectedCategory = GameCategory.CUSTOM
                selectedServerId = target.id
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun DashboardRail(
    isRunning: Boolean,
    pulse: Float,
    onAdd: () -> Unit,
    onMaintenance: (String) -> Unit
) {
    Surface(
        modifier = Modifier.width(58.dp).fillMaxHeight(),
        color = Color(0xFF09111C),
        border = BorderStroke(1.dp, Color(0xFF172538))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PingPalette.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (isRunning) {
                        drawCircle(
                            color = PingPalette.Excellent.copy(alpha = (1f - pulse) * 0.22f),
                            radius = size.minDimension * (0.34f + pulse * 0.28f)
                        )
                    }
                }
                Icon(Icons.Default.WifiTethering, null, tint = PingPalette.Accent, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.height(4.dp))
            RailIcon(Icons.Default.Home, false, maintenance = true) { onMaintenance("Overview") }
            RailIcon(Icons.Default.Speed, true) {}
            RailIcon(Icons.Default.Dns, false, maintenance = true) { onMaintenance("DNS tools") }
            RailIcon(Icons.Default.Insights, false, maintenance = true) { onMaintenance("Analytics") }
            RailIcon(Icons.Default.Hub, false, maintenance = true) { onMaintenance("Network topology") }
            Spacer(Modifier.weight(1f))
            RailIcon(Icons.Default.Add, false, onClick = onAdd)
            RailIcon(Icons.Default.LocalCafe, false) {
                openExternalUrl("https://buymeacoffee.com/aurorauap")
            }
        }
    }
}

@Composable
private fun RailIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    maintenance: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) PingPalette.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = if (maintenance) "Under maintenance" else null,
            tint = if (active) PingPalette.Accent else Color(0xFF668097),
            modifier = Modifier.size(19.dp)
        )
        if (maintenance) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFB84D)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = "Updates coming",
                    tint = Color(0xFF241604),
                    modifier = Modifier.size(8.dp)
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    isRunning: Boolean,
    intervalSec: Int,
    onToggleRun: () -> Unit,
    onRefresh: () -> Unit,
    onInterval: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0B1521),
        border = BorderStroke(1.dp, Color(0xFF193148))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PingMap", color = PingPalette.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    StatusPill(isRunning)
                }
                Text(
                    "Gaming & Network Latency Monitor · ${platformName()}",
                    color = Color(0xFF7890A6),
                    fontSize = 10.sp
                )
            }

            Text("Probe", color = Color(0xFF7890A6), fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            listOf(1, 2, 5).forEach { sec ->
                SmallControl(
                    text = "${sec}s",
                    active = intervalSec == sec,
                    onClick = { onInterval(sec) }
                )
                Spacer(Modifier.width(4.dp))
            }

            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh", tint = PingPalette.Accent)
            }
            FilledTonalButton(
                onClick = onToggleRun,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isRunning) Color(0xFF132337) else PingPalette.Excellent,
                    contentColor = if (isRunning) PingPalette.Text else Color(0xFF03140E)
                )
            ) {
                Icon(
                    if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (isRunning) "Pause" else "Start", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatusPill(live: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background((if (live) PingPalette.Excellent else PingPalette.Muted).copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (live) PingPalette.Excellent else PingPalette.Muted)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (live) "LIVE" else "PAUSED",
            color = if (live) PingPalette.Excellent else PingPalette.Muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SmallControl(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) PingPalette.Accent.copy(alpha = 0.14f) else Color(0xFF0E1A28))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(text, color = if (active) PingPalette.Accent else Color(0xFF7890A6), fontSize = 10.sp)
    }
}

@Composable
private fun BestSignalBanner(best: ServerStats?, pulse: Float, onClick: (ServerStats) -> Unit) {
    val color = best?.grade?.dashboardColor() ?: PingPalette.Excellent
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = best != null) { best?.let(onClick) },
        shape = RoundedCornerShape(9.dp),
        color = Color(0xFF0C1B1C),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.10f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color.copy(alpha = (1f - pulse) * 0.30f), radius = size.minDimension * (0.3f + pulse * 0.45f))
                    drawCircle(color, radius = 3.5f)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Best signal now:", color = Color(0xFF7890A6), fontSize = 10.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                best?.let { "${it.target.name} / ${it.target.category.label}" } ?: "collecting samples…",
                color = PingPalette.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                best?.let { "${fmtDashboardMs(it.currentMs)} · ${it.grade.label}" } ?: "—",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CategoryTabs(selected: GameCategory, onSelect: (GameCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GameCategory.entries.forEach { category ->
            val active = category == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) PingPalette.Accent.copy(alpha = 0.12f) else Color(0xFF0A131E))
                    .clickable { onSelect(category) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    category.label,
                    color = if (active) PingPalette.Accent else Color(0xFF73889D),
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ServerListPanel(
    modifier: Modifier,
    stats: List<ServerStats>,
    selectedId: String?,
    search: String,
    onSearch: (String) -> Unit,
    onSelect: (ServerStats) -> Unit,
    onDelete: (ServerStats) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0A131E),
        border = BorderStroke(1.dp, Color(0xFF172538))
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("Servers", color = PingPalette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                placeholder = { Text("Search & filter", fontSize = 10.sp, color = Color(0xFF60758A)) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) }
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(stats, key = { it.target.id }) { s ->
                    ServerListRow(
                        stats = s,
                        selected = s.target.id == selectedId,
                        onClick = { onSelect(s) },
                        onDelete = if (s.target.isCustom) ({ onDelete(s) }) else null
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerListRow(
    stats: ServerStats,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val color = stats.grade.dashboardColor()
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0xFF10253A) else Color(0xFF0D1824),
        border = BorderStroke(1.dp, if (selected) PingPalette.Accent.copy(alpha = 0.55f) else Color(0xFF18283A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stats.target.name,
                    color = PingPalette.Text,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stats.target.region,
                    color = Color(0xFF657B90),
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MiniSparkline(stats.samples, color, Modifier.width(52.dp).height(20.dp))
            Spacer(Modifier.width(7.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtDashboardMs(stats.currentMs), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("±${stats.jitterMs?.roundToInt() ?: 0} ms", color = Color(0xFF60758A), fontSize = 8.sp)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Delete", tint = PingPalette.Poor, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun MainTelemetryPanel(modifier: Modifier, stats: ServerStats?, pulse: Float) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0A131E),
        border = BorderStroke(1.dp, Color(0xFF172538))
    ) {
        if (stats == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No server selected", color = PingPalette.Muted)
            }
            return@Surface
        }

        val color by animateColorAsState(stats.grade.dashboardColor(), tween(260), label = "gradeColor")
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = PingPalette.Accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "PingMap · ${stats.target.category.label}",
                        color = PingPalette.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${stats.target.name} · ${stats.target.region}",
                        color = Color(0xFF71869B),
                        fontSize = 9.sp
                    )
                }
                GradeChip(stats.grade, color)
            }

            TargetPathStrip(stats, color)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF08141F), Color(0xFF09111A))
                        )
                    )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width * 0.5f, size.height * 0.33f)
                    drawCircle(
                        color = color.copy(alpha = (1f - pulse) * 0.05f),
                        radius = 72f + pulse * 42f,
                        center = center
                    )
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("LATENCY", color = Color(0xFF5C7185), fontSize = 9.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            stats.currentMs?.let { ((it * 10).roundToInt() / 10.0).toString() } ?: "—",
                            color = color,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("ms", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    Spacer(Modifier.height(10.dp))
                    MetricsRow(stats)
                    Spacer(Modifier.height(14.dp))

                    DashboardLatencyGraph(
                        samples = stats.samples,
                        lineColor = color,
                        pulse = pulse,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetPathStrip(stats: ServerStats, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0C1824),
        border = BorderStroke(1.dp, Color(0xFF18283A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PathNode("YOU", PingPalette.Accent)
            PathLink()
            PathNode("CORE", Color(0xFF4CD3FF))
            PathLink()
            PathNode(stats.target.name.uppercase().take(10), color)
            Spacer(Modifier.weight(1f))
            Text(stats.target.host, color = Color(0xFF61768A), fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PathNode(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RowScope.PathLink() {
    Box(
        Modifier
            .weight(0.25f)
            .padding(horizontal = 6.dp)
            .height(1.dp)
            .background(Color(0xFF24415A))
    )
}

@Composable
private fun MetricsRow(stats: ServerStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Metric("MIN", stats.minMs?.let { "${it.roundToInt()} ms" } ?: "—")
        Metric("AVG", stats.avgMs?.let { "${it.roundToInt()} ms" } ?: "—")
        Metric("MAX", stats.maxMs?.let { "${it.roundToInt()} ms" } ?: "—")
        Metric("JITTER", stats.jitterMs?.let { "${it.roundToInt()} ms" } ?: "—")
        Metric("LOSS", "${stats.lossPercent.roundToInt()}%")
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF566C81), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = PingPalette.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SideInsightsPanel(
    modifier: Modifier,
    stats: List<ServerStats>,
    selected: ServerStats?,
    onSelect: (ServerStats) -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0A131E),
            border = BorderStroke(1.dp, Color(0xFF172538))
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Latency", color = PingPalette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("LIVE", color = PingPalette.Excellent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(stats.take(8), key = { it.target.id }) { s ->
                        RouteCard(s, s.target.id == selected?.target?.id) { onSelect(s) }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0A131E),
            border = BorderStroke(1.dp, Color(0xFF172538))
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, tint = PingPalette.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Session", color = PingPalette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                InfoLine("Selected", selected?.target?.name ?: "—")
                InfoLine("Port", selected?.target?.port?.toString() ?: "—")
                InfoLine("Samples", selected?.samples?.size?.toString() ?: "0")
                InfoLine("Quality", selected?.grade?.label ?: "WAITING")
            }
        }
    }
}

@Composable
private fun RouteCard(stats: ServerStats, selected: Boolean, onClick: () -> Unit) {
    val color = stats.grade.dashboardColor()
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0xFF10253A) else Color(0xFF0D1824),
        border = BorderStroke(1.dp, if (selected) PingPalette.Accent.copy(alpha = 0.5f) else Color(0xFF18283A))
    ) {
        Column(Modifier.padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(
                    stats.target.name,
                    color = PingPalette.Text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(fmtDashboardMs(stats.currentMs), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(5.dp))
            MiniSparkline(stats.samples, color, Modifier.fillMaxWidth().height(26.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF647A90), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(value, color = PingPalette.Text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GradeChip(grade: QualityGrade, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(grade.label, color = color, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun MiniSparkline(samples: List<PingSample>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val values = samples.mapNotNull { it.rttMs }
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val min = values.minOrNull() ?: 0.0
        val range = (max - min).coerceAtLeast(1.0)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (((v - min) / range).toFloat() * (size.height - 4f)) - 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color.copy(alpha = 0.22f), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DashboardLatencyGraph(
    samples: List<PingSample>,
    lineColor: Color,
    pulse: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val grid = Color(0xFF173149)
        for (i in 1..4) {
            val y = size.height * i / 5f
            drawLine(grid.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        for (i in 1..7) {
            val x = size.width * i / 8f
            drawLine(grid.copy(alpha = 0.26f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }

        if (samples.isEmpty()) return@Canvas
        val valid = samples.mapNotNull { it.rttMs }
        if (valid.isEmpty()) return@Canvas
        val maxValue = ((valid.maxOrNull() ?: 50.0) * 1.2).coerceAtLeast(30.0)
        val stepX = if (samples.size > 1) size.width / (samples.size - 1) else size.width
        val path = Path()
        val fill = Path()
        var started = false
        var last = Offset.Zero

        samples.forEachIndexed { index, sample ->
            val value = sample.rttMs ?: return@forEachIndexed
            val x = index * stepX
            val y = size.height - (value / maxValue).toFloat().coerceIn(0f, 1f) * (size.height - 12f) - 6f
            if (!started) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            last = Offset(x, y)
        }

        if (!started) return@Canvas
        fill.lineTo(last.x, size.height)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), Color.Transparent))
        )
        drawPath(path, lineColor.copy(alpha = 0.16f), style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, lineColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor.copy(alpha = (1f - pulse) * 0.45f), radius = 5f + pulse * 7f, center = last)
        drawCircle(Color.White, radius = 2.8f, center = last)
    }
}

@Composable
private fun MaintenanceDialog(
    feature: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E1824),
        icon = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFB84D).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = Color(0xFFFFB84D),
                    modifier = Modifier.size(23.dp)
                )
            }
        },
        title = {
            Text(
                "Under maintenance",
                color = PingPalette.Text,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$feature is still being polished.",
                    color = PingPalette.Text,
                    fontSize = 13.sp
                )
                Text(
                    "Updates coming soon.",
                    color = Color(0xFFFFB84D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = PingPalette.Accent)
            }
        }
    )
}

@Composable
private fun DashboardAddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E1824),
        title = { Text("Add custom target", color = PingPalette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Host / IP") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(0.7f)
                    )
                    OutlinedTextField(
                        region,
                        { region = it },
                        label = { Text("Region") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                }
                error?.let { Text(it, color = PingPalette.Poor, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = port.toIntOrNull()
                when {
                    name.isBlank() -> error = "Enter a name"
                    host.isBlank() -> error = "Enter a host or IP"
                    p == null || p !in 1..65535 -> error = "Port must be 1..65535"
                    else -> onAdd(name, region, host, p)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun QualityGrade.dashboardColor(): Color = when (this) {
    QualityGrade.EXCELLENT -> Color(0xFF35E58C)
    QualityGrade.GOOD -> Color(0xFF32C8FF)
    QualityGrade.FAIR -> Color(0xFFFFB84D)
    QualityGrade.POOR -> Color(0xFFFF5F66)
    QualityGrade.WAITING -> Color(0xFF6D8195)
}

private fun fmtDashboardMs(value: Double?): String =
    if (value == null) "—" else "${(value * 10.0).roundToInt() / 10.0} ms"
