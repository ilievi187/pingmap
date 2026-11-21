package io.github.ilievi187.pingmap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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

enum class SortMode(val label: String) {
    PING("Lowest Ping"),
    LOSS("Packet Loss"),
    NAME("Name"),
}

@Composable
fun PingMapApp() {
    val scope = rememberCoroutineScope()

    var customServers by remember { mutableStateOf(loadCustomServers()) }
    val allTargets = remember(customServers) { DefaultServers.preset + customServers }

    var statsMap by remember {
        mutableStateOf(allTargets.associate { it.id to ServerStats(it) })
    }

    // Keep statsMap synced when custom servers are added/removed
    LaunchedEffect(allTargets) {
        val next = LinkedHashMap<String, ServerStats>()
        for (t in allTargets) {
            next[t.id] = statsMap[t.id] ?: ServerStats(t)
        }
        statsMap = next
    }

    var selectedCategory by remember { mutableStateOf(GameCategory.ALL) }
    var sortMode by remember { mutableStateOf(SortMode.PING) }
    var intervalSec by remember { mutableStateOf(2) }
    var isRunning by remember { mutableStateOf(true) }
    var isBursting by remember { mutableStateOf(false) }
    var selectedServerId by remember { mutableStateOf(DefaultServers.preset.first().id) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredStats = remember(statsMap, selectedCategory, sortMode) {
        val list = statsMap.values.filter {
            selectedCategory == GameCategory.ALL || it.target.category == selectedCategory
        }
        when (sortMode) {
            SortMode.PING -> list.sortedBy { it.avgMs ?: Double.MAX_VALUE }
            SortMode.LOSS -> list.sortedByDescending { it.lossPercent }
            SortMode.NAME -> list.sortedBy { it.target.name.lowercase() }
        }
    }

    val bestServer = remember(filteredStats) {
        filteredStats
            .filter { it.avgMs != null }
            .minWithOrNull(compareBy<ServerStats> { it.lossPercent > 1.0 }.thenBy { it.avgMs ?: Double.MAX_VALUE })
    }

    val selectedStats = statsMap[selectedServerId] ?: filteredStats.firstOrNull()

    suspend fun probeVisibleOnce() {
        val targets = allTargets.filter {
            selectedCategory == GameCategory.ALL || it.target.category == selectedCategory
        }
        if (targets.isEmpty()) return
        val results = coroutineScope {
            targets.map { t ->
                async { t.id to probeTcpLatency(t.host, t.port, timeoutMs = 1800) }
            }.awaitAll()
        }
        val updated = statsMap.toMutableMap()
        for ((id, rtt) in results) {
            val cur = updated[id] ?: continue
            updated[id] = cur.appendSample(rtt)
        }
        statsMap = updated
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
            color = PingPalette.Bg,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                HeaderBar(
                    isRunning = isRunning,
                    isBursting = isBursting,
                    onToggleRun = { isRunning = !isRunning },
                    onBurst = {
                        if (!isBursting) {
                            scope.launch {
                                isBursting = true
                                repeat(5) {
                                    probeVisibleOnce()
                                    delay(250L)
                                }
                                isBursting = false
                            }
                        }
                    },
                    onAddCustom = { showAddDialog = true },
                    onReset = {
                        statsMap = allTargets.associate { it.id to ServerStats(it) }
                    },
                )

                // Best Server Recommendation Banner
                BestServerBanner(
                    best = bestServer,
                    category = selectedCategory,
                    onSelect = { selectedServerId = it.target.id },
                )

                // Category Chips + Sort/Interval Controls
                FilterAndControlRow(
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    sortMode = sortMode,
                    onSort = { sortMode = it },
                    intervalSec = intervalSec,
                    onInterval = { intervalSec = it },
                )

                // Selected Server Telemetry Graph Card
                if (selectedStats != null) {
                    SelectedTelemetryCard(stats = selectedStats)
                }

                // Server Cards List
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(filteredStats, key = { it.target.id }) { stats ->
                        ServerCard(
                            stats = stats,
                            isSelected = stats.target.id == selectedStats?.target?.id,
                            onClick = { selectedServerId = stats.target.id },
                            onDelete = if (stats.target.isCustom) {
                                {
                                    val nextCustom = customServers.filterNot { it.id == stats.target.id }
                                    customServers = nextCustom
                                    saveCustomServers(nextCustom)
                                }
                            } else null,
                        )
                    }
                }
            }

            if (showAddDialog) {
                AddCustomServerDialog(
                    onDismiss = { showAddDialog = false },
                    onAdd = { name, region, host, port ->
                        val newTarget = ServerTarget(
                            id = "custom-${host.lowercase()}-$port-${customServers.size + 1}",
                            name = name.trim(),
                            region = region.trim().ifEmpty { "Custom" },
                            host = host.trim(),
                            port = port,
                            category = GameCategory.CUSTOM,
                            isCustom = true,
                        )
                        val next = customServers + newTarget
                        customServers = next
                        saveCustomServers(next)
                        selectedCategory = GameCategory.CUSTOM
                        selectedServerId = newTarget.id
                        showAddDialog = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(
    isRunning: Boolean,
    isBursting: Boolean,
    onToggleRun: () -> Unit,
    onBurst: () -> Unit,
    onAddCustom: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PingPalette.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PingPalette.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.NetworkCheck,
                contentDescription = "PingMap",
                tint = PingPalette.Accent,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PingMap",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PingPalette.Text,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isRunning) PingPalette.Excellent.copy(alpha = 0.18f)
                            else PingPalette.Muted.copy(alpha = 0.16f)
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (isRunning) "LIVE" else "PAUSED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) PingPalette.Excellent else PingPalette.Muted,
                    )
                }
            }
            Text(
                text = "Gaming & Network Latency Monitor · ${platformName()}",
                fontSize = 11.sp,
                color = PingPalette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        FilledTonalButton(
            onClick = onToggleRun,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isRunning) PingPalette.Card else PingPalette.Accent,
                contentColor = if (isRunning) PingPalette.Text else Color(0xFF04131F),
            ),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (isRunning) "Pause" else "Start", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = onBurst,
            enabled = !isBursting,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            border = BorderStroke(1.dp, PingPalette.Border),
        ) {
            Text(if (isBursting) "5×…" else "5× Burst", fontSize = 12.sp, color = PingPalette.Accent)
        }

        IconButton(onClick = onAddCustom) {
            Icon(Icons.Default.AddCircleOutline, contentDescription = "Add Custom Server", tint = PingPalette.Accent)
        }
        IconButton(onClick = onReset) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset Stats", tint = PingPalette.Muted)
        }
        IconButton(onClick = { openExternalUrl("https://buymeacoffee.com/aurorauap") }) {
            Icon(Icons.Default.LocalCafe, contentDescription = "Support", tint = PingPalette.Fair)
        }
    }
}

@Composable
private fun BestServerBanner(
    best: ServerStats?,
    category: GameCategory,
    onSelect: (ServerStats) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = best != null) { best?.let(onSelect) },
        color = PingPalette.Card,
        border = BorderStroke(1.dp, (best?.grade?.color() ?: PingPalette.Border).copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = best?.grade?.color() ?: PingPalette.Accent,
                modifier = Modifier.size(20.dp),
            )
            if (best == null) {
                Text(
                    text = "Measuring ${category.label} servers…",
                    fontSize = 13.sp,
                    color = PingPalette.Muted,
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Best right now: ${best.target.name} (${best.target.category.label})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PingPalette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${best.target.region} · Avg ${fmtMs(best.avgMs)} · Jitter ${fmtMs(best.jitterMs)} · Loss ${best.lossPercent.roundToInt()}%",
                        fontSize = 11.sp,
                        color = PingPalette.Muted,
                    )
                }
                GradeBadge(best.grade)
            }
        }
    }
}

@Composable
private fun FilterAndControlRow(
    selectedCategory: GameCategory,
    onCategory: (GameCategory) -> Unit,
    sortMode: SortMode,
    onSort: (SortMode) -> Unit,
    intervalSec: Int,
    onInterval: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (cat in GameCategory.entries) {
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onCategory(cat) },
                    label = { Text(cat.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PingPalette.Accent.copy(alpha = 0.22f),
                        selectedLabelColor = PingPalette.Accent,
                    ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Sort:", fontSize = 11.sp, color = PingPalette.Muted)
            for (mode in SortMode.entries) {
                AssistChip(
                    onClick = { onSort(mode) },
                    label = {
                        Text(
                            mode.label,
                            fontSize = 11.sp,
                            color = if (sortMode == mode) PingPalette.Accent else PingPalette.Muted,
                        )
                    },
                    border = BorderStroke(
                        1.dp,
                        if (sortMode == mode) PingPalette.Accent else PingPalette.Border,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Rate:", fontSize = 11.sp, color = PingPalette.Muted)
            for (sec in listOf(1, 2, 5)) {
                AssistChip(
                    onClick = { onInterval(sec) },
                    label = {
                        Text(
                            "${sec}s",
                            fontSize = 11.sp,
                            color = if (intervalSec == sec) PingPalette.Excellent else PingPalette.Muted,
                        )
                    },
                    border = BorderStroke(
                        1.dp,
                        if (intervalSec == sec) PingPalette.Excellent else PingPalette.Border,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SelectedTelemetryCard(stats: ServerStats) {
    val accent = stats.grade.color()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PingPalette.Surface,
        border = BorderStroke(1.dp, PingPalette.Border),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stats.target.name} · ${stats.target.category.label}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PingPalette.Text,
                    )
                    Text(
                        text = "${stats.target.host}:${stats.target.port} (${stats.target.region})",
                        fontSize = 11.sp,
                        color = PingPalette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = fmtMs(stats.currentMs),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                )
            }

            LatencyGraph(
                samples = stats.samples,
                lineColor = accent,
                modifier = Modifier.fillMaxWidth().height(84.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                KpiItem("MIN", fmtMs(stats.minMs))
                KpiItem("AVG", fmtMs(stats.avgMs))
                KpiItem("MAX", fmtMs(stats.maxMs))
                KpiItem("JITTER", fmtMs(stats.jitterMs))
                KpiItem(
                    "LOSS",
                    "${stats.lossPercent.roundToInt()}%",
                    valueColor = if (stats.lossPercent > 0.0) PingPalette.Poor else PingPalette.Excellent,
                )
            }
        }
    }
}

@Composable
private fun KpiItem(label: String, value: String, valueColor: Color = PingPalette.Text) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = PingPalette.Muted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ServerCard(
    stats: ServerStats,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val gradeColor = stats.grade.color()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) PingPalette.CardSelected else PingPalette.Card,
        border = BorderStroke(1.dp, if (isSelected) PingPalette.Accent else PingPalette.Border),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(gradeColor),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stats.target.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PingPalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stats.target.category.label} · ${stats.target.region}",
                    fontSize = 11.sp,
                    color = PingPalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LatencyGraph(
                samples = stats.samples,
                lineColor = gradeColor,
                modifier = Modifier.width(78.dp).height(30.dp),
            )

            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(74.dp)) {
                Text(
                    text = fmtMs(stats.currentMs),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = gradeColor,
                )
                Text(
                    text = "J:${fmtShortMs(stats.jitterMs)} L:${stats.lossPercent.roundToInt()}%",
                    fontSize = 10.sp,
                    color = if (stats.lossPercent > 0.0) PingPalette.Poor else PingPalette.Muted,
                )
            }

            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Custom Server",
                        tint = PingPalette.Poor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyGraph(
    samples: List<PingSample>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (samples.isEmpty() || w <= 0f || h <= 0f) return@Canvas

        // Subtle baseline
        drawLine(
            color = PingPalette.Border.copy(alpha = 0.5f),
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1f,
        )

        val valid = samples.mapNotNull { it.rttMs }
        val maxVal = ((valid.maxOrNull() ?: 50.0) * 1.2).coerceAtLeast(30.0)
        val stepX = if (samples.size > 1) w / (samples.size - 1) else w

        val linePath = Path()
        val fillPath = Path()
        var started = false
        var lastX = 0f

        samples.forEachIndexed { idx, sample ->
            val x = idx * stepX
            val rtt = sample.rttMs
            if (rtt == null) {
                // Draw red vertical drop marker for packet loss
                drawLine(
                    color = PingPalette.Poor.copy(alpha = 0.7f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 2f,
                )
            } else {
                val y = (h - ((rtt / maxVal).toFloat().coerceIn(0f, 1f) * (h - 6f)) - 3f)
                if (!started) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                    started = true
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                lastX = x
            }
        }

        if (started) {
            fillPath.lineTo(lastX, h)
            fillPath.close()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
                ),
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
private fun GradeBadge(grade: QualityGrade) {
    val color = grade.color()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = grade.label,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
    }
}

@Composable
private fun AddCustomServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, region: String, host: String, port: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PingPalette.Surface,
        title = { Text("Add Custom Server / IP", color = PingPalette.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Label (e.g. Home Router / My VPS)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host or IP (e.g. 1.1.1.1 or game.server.com)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("TCP Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it },
                        label = { Text("Region (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                if (error != null) {
                    Text(error!!, color = PingPalette.Poor, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = portText.toIntOrNull()
                when {
                    name.isBlank() -> error = "Enter a label"
                    host.isBlank() -> error = "Enter a host or IP"
                    p == null || p !in 1..65535 -> error = "Port must be 1..65535"
                    else -> onAdd(name, region, host, p)
                }
            }) {
                Text("Add Target")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun QualityGrade.color(): Color = when (this) {
    QualityGrade.EXCELLENT -> PingPalette.Excellent
    QualityGrade.GOOD -> PingPalette.Good
    QualityGrade.FAIR -> PingPalette.Fair
    QualityGrade.POOR -> PingPalette.Poor
    QualityGrade.WAITING -> PingPalette.Muted
}

private fun fmtMs(value: Double?): String =
    if (value == null) "—" else "${(value * 10.0).roundToInt() / 10.0} ms"

private fun fmtShortMs(value: Double?): String =
    if (value == null) "—" else "${value.roundToInt()}ms"
