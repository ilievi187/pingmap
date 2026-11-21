package io.github.ilievi187.pingmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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

    // Shared smooth radar pulse animation for LIVE indicators & graph live dot
    val pulseTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseProgress by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseProgress",
    )

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
            selectedCategory == GameCategory.ALL || it.category == selectedCategory
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
                HeaderBar(
                    isRunning = isRunning,
                    isBursting = isBursting,
                    pulseProgress = pulseProgress,
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

                BestServerBanner(
                    best = bestServer,
                    category = selectedCategory,
                    isRunning = isRunning,
                    pulseProgress = pulseProgress,
                    onSelect = { selectedServerId = it.target.id },
                )

                FilterAndControlRow(
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    sortMode = sortMode,
                    onSort = { sortMode = it },
                    intervalSec = intervalSec,
                    onInterval = { intervalSec = it },
                )

                if (selectedStats != null) {
                    SelectedTelemetryCard(
                        stats = selectedStats,
                        isRunning = isRunning,
                        pulseProgress = pulseProgress,
                    )
                }

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
private fun GlowIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.12f else 1f, tween(140), label = "iconScale")
    val bg by animateColorAsState(
        if (hovered) tint.copy(alpha = 0.18f) else Color.Transparent,
        tween(150),
        label = "iconBg",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HeaderBar(
    isRunning: Boolean,
    isBursting: Boolean,
    pulseProgress: Float,
    onToggleRun: () -> Unit,
    onBurst: () -> Unit,
    onAddCustom: () -> Unit,
    onReset: () -> Unit,
) {
    val toggleHover = remember { MutableInteractionSource() }
    val toggleIsHovered by toggleHover.collectIsHoveredAsState()
    val toggleScale by animateFloatAsState(if (toggleIsHovered) 1.04f else 1f, tween(140), label = "toggleScale")

    val burstHover = remember { MutableInteractionSource() }
    val burstIsHovered by burstHover.collectIsHoveredAsState()
    val burstScale by animateFloatAsState(if (burstIsHovered) 1.04f else 1f, tween(140), label = "burstScale")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, PingPalette.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF132036),
                            PingPalette.Surface,
                            Color(0xFF101B2D),
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PingPalette.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val r = size.minDimension * (0.22f + pulseProgress * 0.30f)
                        drawCircle(
                            color = PingPalette.Accent.copy(alpha = (1f - pulseProgress) * 0.55f),
                            radius = r,
                            style = Stroke(width = 2f),
                        )
                    }
                }
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
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isRunning) PingPalette.Excellent.copy(alpha = 0.18f)
                                else PingPalette.Muted.copy(alpha = 0.16f)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Canvas(modifier = Modifier.size(7.dp)) {
                            if (isRunning) {
                                drawCircle(
                                    color = PingPalette.Excellent.copy(alpha = (1f - pulseProgress) * 0.7f),
                                    radius = size.minDimension * (0.4f + pulseProgress * 0.6f),
                                )
                            }
                            drawCircle(
                                color = if (isRunning) PingPalette.Excellent else PingPalette.Muted,
                                radius = size.minDimension * 0.42f,
                            )
                        }
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
                modifier = Modifier.scale(toggleScale).hoverable(toggleHover),
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
                modifier = Modifier.scale(burstScale).hoverable(burstHover),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                border = BorderStroke(
                    1.dp,
                    if (burstIsHovered || isBursting) PingPalette.Accent else PingPalette.Border,
                ),
            ) {
                Text(if (isBursting) "5×…" else "5× Burst", fontSize = 12.sp, color = PingPalette.Accent)
            }

            GlowIconButton(Icons.Default.AddCircleOutline, "Add Custom Server", PingPalette.Accent, onAddCustom)
            GlowIconButton(Icons.Default.Refresh, "Reset Stats", PingPalette.Muted, onReset)
            GlowIconButton(Icons.Default.LocalCafe, "Support", PingPalette.Fair) {
                openExternalUrl("https://buymeacoffee.com/aurorauap")
            }
        }
    }
}

@Composable
private fun BestServerBanner(
    best: ServerStats?,
    category: GameCategory,
    isRunning: Boolean,
    pulseProgress: Float,
    onSelect: (ServerStats) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rawAccent = best?.grade?.color() ?: PingPalette.Accent
    val accent by animateColorAsState(rawAccent, tween(300), label = "bestAccent")
    val borderAlpha by animateFloatAsState(if (hovered) 0.85f else 0.45f, tween(160), label = "bestBorder")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(enabled = best != null) { best?.let(onSelect) },
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = borderAlpha)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.14f), PingPalette.Card, PingPalette.Card)
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (isRunning && best != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = accent.copy(alpha = (1f - pulseProgress) * 0.45f),
                            radius = size.minDimension * (0.3f + pulseProgress * 0.45f),
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
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
private fun SelectedTelemetryCard(
    stats: ServerStats,
    isRunning: Boolean,
    pulseProgress: Float,
) {
    val accent by animateColorAsState(stats.grade.color(), tween(320), label = "selAccent")
    val animatedRtt by animateFloatAsState(
        targetValue = (stats.currentMs ?: 0.0).toFloat(),
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "animatedRtt",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PingPalette.Surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
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
                    text = if (stats.currentMs == null) "—" else fmtMs(animatedRtt.toDouble()),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                )
            }

            LatencyGraph(
                samples = stats.samples,
                lineColor = accent,
                showGrid = true,
                livePulse = if (isRunning) pulseProgress else null,
                modifier = Modifier.fillMaxWidth().height(88.dp),
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val gradeColor by animateColorAsState(stats.grade.color(), tween(280), label = "cardGrade")
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> PingPalette.CardSelected
            hovered -> Color(0xFF19263B)
            else -> PingPalette.Card
        },
        animationSpec = tween(150),
        label = "cardBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> PingPalette.Accent
            hovered -> gradeColor.copy(alpha = 0.65f)
            else -> PingPalette.Border
        },
        animationSpec = tween(150),
        label = "cardBorder",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (hovered) 1.008f else 1f,
        animationSpec = tween(140),
        label = "cardScale",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick),
        color = bgColor,
        border = BorderStroke(if (isSelected || hovered) 1.4.dp else 1.dp, borderColor),
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
                showGrid = false,
                livePulse = null,
                modifier = Modifier.width(82.dp).height(30.dp),
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
                GlowIconButton(Icons.Default.DeleteOutline, "Delete Custom Server", PingPalette.Poor, onDelete)
            }
        }
    }
}

@Composable
private fun LatencyGraph(
    samples: List<PingSample>,
    lineColor: Color,
    showGrid: Boolean = false,
    livePulse: Float? = null,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (samples.isEmpty() || w <= 0f || h <= 0f) return@Canvas

        if (showGrid) {
            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(
                    color = PingPalette.Border.copy(alpha = 0.35f),
                    start = Offset(0f, h * frac),
                    end = Offset(w, h * frac),
                    strokeWidth = 1f,
                )
            }
        }
        drawLine(
            color = PingPalette.Border.copy(alpha = 0.55f),
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
        var lastY = h / 2f

        samples.forEachIndexed { idx, sample ->
            val x = idx * stepX
            val rtt = sample.rttMs
            if (rtt == null) {
                drawLine(
                    color = PingPalette.Poor.copy(alpha = 0.75f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 2f,
                )
            } else {
                val y = (h - ((rtt / maxVal).toFloat().coerceIn(0f, 1f) * (h - 8f)) - 4f)
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
                lastY = y
            }
        }

        if (started) {
            fillPath.lineTo(lastX, h)
            fillPath.close()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.30f), Color.Transparent),
                ),
            )
            // Soft outer neon glow stroke
            drawPath(
                path = linePath,
                color = lineColor.copy(alpha = 0.25f),
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Pulsing live endpoint dot on the main telemetry chart
            if (livePulse != null) {
                drawCircle(
                    color = lineColor.copy(alpha = (1f - livePulse) * 0.65f),
                    radius = 4f + livePulse * 8f,
                    center = Offset(lastX, lastY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.2f,
                    center = Offset(lastX, lastY),
                )
            }
        }
    }
}

@Composable
private fun GradeBadge(grade: QualityGrade) {
    val color by animateColorAsState(grade.color(), tween(280), label = "badgeColor")
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
