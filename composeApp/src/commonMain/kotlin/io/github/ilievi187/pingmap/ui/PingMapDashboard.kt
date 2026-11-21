package io.github.ilievi187.pingmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ilievi187.pingmap.model.*
import io.github.ilievi187.pingmap.net.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

    // Two lightweight shared infinite animations for the entire desktop dashboard.
    val ambientTransition = rememberInfiniteTransition(label = "dashboardAmbient")
    val pulseValue by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val flowValue by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DashboardPalette.BgTop,
                            DashboardPalette.BgMid,
                            DashboardPalette.BgBottom,
                        )
                    )
                )
        ) {
            // Subtle ambient radial halo behind the workspace for depth.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DashboardPalette.Cyan.copy(alpha = 0.055f),
                            DashboardPalette.MatrixGreen.copy(alpha = 0.02f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.52f, size.height * 0.28f),
                        radius = size.maxDimension * 0.55f,
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DashboardPalette.MatrixGreen.copy(alpha = 0.032f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.12f, size.height * 0.85f),
                        radius = size.maxDimension * 0.42f,
                    )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DashboardRail(
                    isRunning = isRunning,
                    pulse = pulseValue,
                    onAdd = { showAddDialog = true },
                    onMaintenance = { maintenanceFeature = it },
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    DashboardHeader(
                        isRunning = isRunning,
                        intervalSec = intervalSec,
                        pulse = pulseValue,
                        onToggleRun = { isRunning = !isRunning },
                        onRefresh = { scope.launch { probeVisibleOnce() } },
                        onInterval = { intervalSec = it },
                    )

                    BestSignalBanner(
                        best = bestServer,
                        pulse = pulseValue,
                        onClick = { selectedServerId = it.target.id },
                    )

                    CategoryTabs(
                        selected = selectedCategory,
                        onSelect = {
                            selectedCategory = it
                            statsMap.values.firstOrNull { s ->
                                it == GameCategory.ALL || s.target.category == it
                            }?.let { s -> selectedServerId = s.target.id }
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ServerListPanel(
                            modifier = Modifier.width(268.dp).fillMaxHeight(),
                            stats = visibleStats,
                            selectedId = selectedStats?.target?.id,
                            search = search,
                            onSearch = { search = it },
                            onSelect = { selectedServerId = it.target.id },
                            onDelete = { stats ->
                                val next = customServers.filterNot { it.id == stats.target.id }
                                customServers = next
                                saveCustomServers(next)
                            },
                        )

                        MainTelemetryPanel(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            stats = selectedStats,
                            isRunning = isRunning,
                            intervalSec = intervalSec,
                            pulse = pulseValue,
                            flow = flowValue,
                        )

                        SideInsightsPanel(
                            modifier = Modifier.width(282.dp).fillMaxHeight(),
                            stats = visibleStats,
                            selected = selectedStats,
                            isRunning = isRunning,
                            pulse = pulseValue,
                            onSelect = { selectedServerId = it.target.id },
                        )
                    }
                }
            }
        }
    }

    maintenanceFeature?.let { feature ->
        MaintenanceDialog(
            feature = feature,
            onDismiss = { maintenanceFeature = null },
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
                    isCustom = true,
                )
                val next = customServers + target
                customServers = next
                saveCustomServers(next)
                selectedCategory = GameCategory.CUSTOM
                selectedServerId = target.id
                showAddDialog = false
            },
        )
    }
}

// =============================================================================
// Reusable Cyber Style Helpers
// =============================================================================

@Composable
private fun CyberPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = DashboardDimens.PanelRadius,
    topColor: Color = DashboardPalette.PanelTop,
    bottomColor: Color = DashboardPalette.PanelBottom,
    accentGlow: Color? = null,
    elevation: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = if (accentGlow != null) {
        Brush.verticalGradient(
            listOf(
                accentGlow.copy(alpha = 0.55f),
                DashboardPalette.BorderHighlight.copy(alpha = 0.50f),
                DashboardPalette.BorderSubtle.copy(alpha = 0.85f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                DashboardPalette.BorderHighlight.copy(alpha = 0.65f),
                DashboardPalette.BorderSubtle.copy(alpha = 0.85f),
                Color(0xFF0E1B2B),
            )
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = (accentGlow ?: DashboardPalette.Cyan).copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.72f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .drawWithContent {
                drawContent()
                // Crisp subtle inner top-edge specular highlight.
                val inset = cornerRadius.toPx().coerceAtMost(size.width * 0.25f)
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            (accentGlow ?: DashboardPalette.CyanBright).copy(alpha = 0.26f),
                            DashboardPalette.MatrixGreen.copy(alpha = 0.14f),
                            Color.Transparent,
                        )
                    ),
                    start = Offset(inset, 1f),
                    end = Offset(size.width - inset, 1f),
                    strokeWidth = 1.2f,
                )
            },
        content = content,
    )
}

@Composable
private fun CyberSectionHeader(
    title: String,
    accent: Color = DashboardPalette.Cyan,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .drawBehind {
                    drawCircle(accent.copy(alpha = 0.35f), radius = size.minDimension * 1.15f)
                }
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = DashboardPalette.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun CyberStatusPill(
    live: Boolean,
    pulse: Float,
    labelLive: String = "LIVE",
    labelPaused: String = "PAUSED",
) {
    val color = if (live) DashboardPalette.MatrixGreen else DashboardPalette.TextMuted
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(DashboardDimens.PillRadius))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.38f), RoundedCornerShape(DashboardDimens.PillRadius))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (live) {
                    drawCircle(
                        color = color.copy(alpha = (1f - pulse) * 0.55f),
                        radius = size.minDimension * (0.28f + pulse * 0.52f),
                    )
                }
                drawCircle(color = color, radius = 2.6f)
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (live) labelLive else labelPaused,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.0.sp,
        )
    }
}

// =============================================================================
// 1. Floating Premium Sidebar
// =============================================================================

@Composable
private fun DashboardRail(
    isRunning: Boolean,
    pulse: Float,
    onAdd: () -> Unit,
    onMaintenance: (String) -> Unit,
) {
    CyberPanel(
        modifier = Modifier.width(64.dp).fillMaxHeight(),
        cornerRadius = DashboardDimens.RailRadius,
        topColor = DashboardPalette.RailTop,
        bottomColor = DashboardPalette.RailBottom,
        elevation = 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Glowing brand beacon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    DashboardPalette.Cyan.copy(alpha = if (isRunning) 0.24f else 0.10f),
                                    Color.Transparent,
                                )
                            ),
                            radius = size.minDimension * 0.92f,
                        )
                    }
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                DashboardPalette.Cyan.copy(alpha = 0.20f),
                                DashboardPalette.MatrixGreen.copy(alpha = 0.14f),
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                DashboardPalette.Cyan.copy(alpha = 0.55f),
                                DashboardPalette.MatrixGreen.copy(alpha = 0.32f),
                            )
                        ),
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (isRunning) {
                        drawCircle(
                            color = DashboardPalette.MatrixGreen.copy(alpha = (1f - pulse) * 0.34f),
                            radius = size.minDimension * (0.26f + pulse * 0.30f),
                            style = Stroke(width = 1.4f),
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = "PingMap",
                    tint = DashboardPalette.CyanBright,
                    modifier = Modifier.size(21.dp),
                )
            }

            // Subtle divider
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, DashboardPalette.BorderHighlight, Color.Transparent)
                        )
                    )
            )

            RailIcon(
                icon = Icons.Default.Home,
                contentDescription = "Overview",
                active = false,
                maintenance = true,
                onClick = { onMaintenance("Overview") },
            )
            RailIcon(
                icon = Icons.Default.Speed,
                contentDescription = "Live Latency",
                active = true,
                onClick = {},
            )
            RailIcon(
                icon = Icons.Default.Dns,
                contentDescription = "DNS tools",
                active = false,
                maintenance = true,
                onClick = { onMaintenance("DNS tools") },
            )
            RailIcon(
                icon = Icons.Default.Insights,
                contentDescription = "Analytics",
                active = false,
                maintenance = true,
                onClick = { onMaintenance("Analytics") },
            )
            RailIcon(
                icon = Icons.Default.Hub,
                contentDescription = "Network topology",
                active = false,
                maintenance = true,
                onClick = { onMaintenance("Network topology") },
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, DashboardPalette.BorderHighlight.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
            )

            RailIcon(
                icon = Icons.Default.Add,
                contentDescription = "Add custom target",
                active = false,
                accentTint = DashboardPalette.MatrixGreen,
                onClick = onAdd,
            )
            RailIcon(
                icon = Icons.Default.LocalCafe,
                contentDescription = "Support",
                active = false,
                accentTint = DashboardPalette.Amber,
                onClick = { openExternalUrl("https://buymeacoffee.com/aurorauap") },
            )
        }
    }
}

@Composable
private fun RailIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    maintenance: Boolean = false,
    accentTint: Color = DashboardPalette.Cyan,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (hovered && !active) 1.07f else 1f,
        animationSpec = tween(150),
        label = "railScale",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            active -> DashboardPalette.CyanBright
            hovered -> accentTint
            maintenance -> Color(0xFF5D7690)
            else -> Color(0xFF7B95B0)
        },
        animationSpec = tween(160),
        label = "railTint",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            active -> DashboardPalette.Cyan.copy(alpha = 0.65f)
            hovered -> accentTint.copy(alpha = 0.42f)
            else -> Color(0xFF142436)
        },
        animationSpec = tween(160),
        label = "railBorder",
    )

    Box(
        modifier = Modifier.fillMaxWidth().height(42.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Luminous active left indicator bar
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(22.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = DashboardPalette.Cyan.copy(alpha = 0.45f),
                            topLeft = Offset(-2f, -3f),
                            size = Size(size.width + 6f, size.height + 6f),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(DashboardPalette.CyanBright, DashboardPalette.MatrixGreen)
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .scale(scale)
                .drawBehind {
                    if (active || hovered) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    (if (active) DashboardPalette.Cyan else accentTint).copy(
                                        alpha = if (active) 0.24f else 0.16f
                                    ),
                                    Color.Transparent,
                                )
                            ),
                            radius = size.minDimension * 0.85f,
                        )
                    }
                }
                .clip(RoundedCornerShape(13.dp))
                .background(
                    when {
                        active -> Brush.linearGradient(
                            listOf(
                                DashboardPalette.Cyan.copy(alpha = 0.22f),
                                DashboardPalette.MatrixGreen.copy(alpha = 0.11f),
                            )
                        )
                        hovered -> Brush.linearGradient(
                            listOf(
                                accentTint.copy(alpha = 0.14f),
                                DashboardPalette.CardHover,
                            )
                        )
                        else -> Brush.verticalGradient(
                            listOf(Color(0xFF0B1624), Color(0xFF08111C))
                        )
                    }
                )
                .border(1.dp, borderColor, RoundedCornerShape(13.dp))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (maintenance) "$contentDescription (Under maintenance)" else contentDescription,
                tint = iconColor,
                modifier = Modifier.size(19.dp),
            )

            if (maintenance) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(DashboardPalette.RailBottom)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFFCA6B), Color(0xFFE89B2D))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Hardware,
                        contentDescription = "Updates coming",
                        tint = Color(0xFF1F1303),
                        modifier = Modifier.size(8.5.dp),
                    )
                }
            }
        }
    }
}

// =============================================================================
// Top Header, Best Signal Banner, & Category Tabs
// =============================================================================

@Composable
private fun DashboardHeader(
    isRunning: Boolean,
    intervalSec: Int,
    pulse: Float,
    onToggleRun: () -> Unit,
    onRefresh: () -> Unit,
    onInterval: (Int) -> Unit,
) {
    val refreshInteraction = remember { MutableInteractionSource() }
    val refreshHovered by refreshInteraction.collectIsHoveredAsState()
    val refreshScale by animateFloatAsState(if (refreshHovered) 1.07f else 1f, tween(140), label = "refScale")

    val toggleInteraction = remember { MutableInteractionSource() }
    val toggleHovered by toggleInteraction.collectIsHoveredAsState()
    val toggleScale by animateFloatAsState(if (toggleHovered) 1.03f else 1f, tween(140), label = "togScale")

    CyberPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = DashboardDimens.PanelRadius,
        elevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = "PingMap",
                        color = DashboardPalette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.3.sp,
                    )
                    CyberStatusPill(live = isRunning, pulse = pulse)
                    Text(
                        text = "NET.HUD",
                        color = DashboardPalette.MatrixGreen.copy(alpha = 0.65f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                    )
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "Gaming & Network Latency Command Center · ${platformName()}",
                    color = DashboardPalette.TextMuted,
                    fontSize = 10.sp,
                )
            }

            // Segmented probe rate control
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(DashboardPalette.PanelRecessed)
                    .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(11.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "PROBE",
                    color = DashboardPalette.TextDim,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    modifier = Modifier.padding(horizontal = 5.dp),
                )
                listOf(1, 2, 5).forEach { sec ->
                    SmallControl(
                        text = "${sec}s",
                        active = intervalSec == sec,
                        onClick = { onInterval(sec) },
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Refresh icon button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .scale(refreshScale)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (refreshHovered) DashboardPalette.Cyan.copy(alpha = 0.16f)
                        else DashboardPalette.PanelRecessed
                    )
                    .border(
                        1.dp,
                        if (refreshHovered) DashboardPalette.Cyan.copy(alpha = 0.55f)
                        else DashboardPalette.BorderSubtle,
                        RoundedCornerShape(11.dp),
                    )
                    .hoverable(refreshInteraction)
                    .clickable(interactionSource = refreshInteraction, indication = null, onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = if (refreshHovered) DashboardPalette.CyanBright else DashboardPalette.Cyan,
                    modifier = Modifier.size(17.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Pause / Start pill button
            val btnAccent = if (isRunning) DashboardPalette.Cyan else DashboardPalette.MatrixGreen
            Box(
                modifier = Modifier
                    .scale(toggleScale)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isRunning) {
                            Brush.horizontalGradient(
                                listOf(Color(0xFF122336), Color(0xFF0E1C2D))
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(DashboardPalette.MatrixGreen, Color(0xFF14C87E))
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (isRunning) btnAccent.copy(alpha = if (toggleHovered) 0.65f else 0.34f)
                        else DashboardPalette.MatrixGreen.copy(alpha = 0.85f),
                        RoundedCornerShape(11.dp),
                    )
                    .hoverable(toggleInteraction)
                    .clickable(interactionSource = toggleInteraction, indication = null, onClick = onToggleRun)
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isRunning) DashboardPalette.TextPrimary else Color(0xFF03170F),
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (isRunning) "Pause" else "Start",
                        color = if (isRunning) DashboardPalette.TextPrimary else Color(0xFF03170F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallControl(text: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> DashboardPalette.Cyan.copy(alpha = 0.18f)
                    hovered -> Color(0xFF112133)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                if (active) DashboardPalette.Cyan.copy(alpha = 0.45f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = if (active) DashboardPalette.CyanBright else DashboardPalette.TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BestSignalBanner(
    best: ServerStats?,
    pulse: Float,
    onClick: (ServerStats) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rawColor = best?.grade?.dashboardColor() ?: DashboardPalette.MatrixGreen
    val color by animateColorAsState(rawColor, tween(280), label = "bestColor")
    val borderAlpha by animateFloatAsState(if (hovered && best != null) 0.65f else 0.36f, tween(160), label = "bestBorder")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = 0.13f),
                        Color(0xFF0A1622),
                        Color(0xFF08111B),
                    )
                )
            )
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        color.copy(alpha = borderAlpha),
                        DashboardPalette.BorderSubtle,
                        color.copy(alpha = borderAlpha * 0.7f),
                    )
                ),
                RoundedCornerShape(DashboardDimens.InnerRadius),
            )
            .hoverable(interaction)
            .clickable(enabled = best != null) { best?.let(onClick) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = color.copy(alpha = (1f - pulse) * 0.34f),
                        radius = size.minDimension * (0.28f + pulse * 0.48f),
                        style = Stroke(width = 1.4f),
                    )
                    drawCircle(color = color.copy(alpha = 0.25f), radius = 5.5f)
                    drawCircle(color = color, radius = 3.2f)
                }
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = "BEST SIGNAL",
                color = color.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.0.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = best?.let { "${it.target.name}  ·  ${it.target.category.label}" } ?: "Collecting live telemetry…",
                color = DashboardPalette.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (best != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.12f))
                        .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = fmtDashboardMs(best.currentMs),
                        color = color,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = best.grade.label,
                        color = color.copy(alpha = 0.85f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTabs(selected: GameCategory, onSelect: (GameCategory) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
            .background(DashboardPalette.PanelRecessed.copy(alpha = 0.85f))
            .border(1.dp, DashboardPalette.BorderSubtle.copy(alpha = 0.85f), RoundedCornerShape(DashboardDimens.InnerRadius))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        GameCategory.entries.forEach { category ->
            val active = category == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            active -> Brush.horizontalGradient(
                                listOf(
                                    DashboardPalette.Cyan.copy(alpha = 0.18f),
                                    DashboardPalette.MatrixGreen.copy(alpha = 0.11f),
                                )
                            )
                            hovered -> Brush.horizontalGradient(
                                listOf(Color(0xFF0E1C2D), Color(0xFF0B1624))
                            )
                            else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            active -> DashboardPalette.Cyan.copy(alpha = 0.48f)
                            hovered -> DashboardPalette.BorderHighlight.copy(alpha = 0.45f)
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(10.dp),
                    )
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(category) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    text = category.label,
                    color = when {
                        active -> DashboardPalette.CyanBright
                        hovered -> DashboardPalette.TextPrimary
                        else -> DashboardPalette.TextSecondary
                    },
                    fontSize = 10.5.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

// =============================================================================
// 3. Left Column — Server List Panel
// =============================================================================

@Composable
private fun ServerListPanel(
    modifier: Modifier,
    stats: List<ServerStats>,
    selectedId: String?,
    search: String,
    onSearch: (String) -> Unit,
    onSelect: (ServerStats) -> Unit,
    onDelete: (ServerStats) -> Unit,
) {
    CyberPanel(
        modifier = modifier,
        cornerRadius = DashboardDimens.PanelRadius,
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            CyberSectionHeader(
                title = "Servers",
                accent = DashboardPalette.Cyan,
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(DashboardPalette.PanelRecessed)
                            .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${stats.size} NODES",
                            color = DashboardPalette.TextMuted,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.7.sp,
                        )
                    }
                },
            )

            Spacer(Modifier.height(10.dp))

            // Sleek recessed search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(DashboardPalette.PanelRecessed)
                    .border(
                        1.dp,
                        if (search.isNotEmpty()) DashboardPalette.Cyan.copy(alpha = 0.45f)
                        else DashboardPalette.BorderSubtle,
                        RoundedCornerShape(11.dp),
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (search.isNotEmpty()) DashboardPalette.Cyan else DashboardPalette.TextMuted,
                    modifier = Modifier.size(15.dp),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (search.isEmpty()) {
                        Text(
                            text = "Filter servers or regions…",
                            color = DashboardPalette.TextDim,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = search,
                        onValueChange = onSearch,
                        singleLine = true,
                        cursorBrush = SolidColor(DashboardPalette.Cyan),
                        textStyle = TextStyle(
                            color = DashboardPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (search.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = DashboardPalette.TextMuted,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onSearch("") },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(stats, key = { it.target.id }) { s ->
                    ServerListRow(
                        stats = s,
                        selected = s.target.id == selectedId,
                        onClick = { onSelect(s) },
                        onDelete = if (s.target.isCustom) ({ onDelete(s) }) else null,
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
    onDelete: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(stats.grade.dashboardColor(), tween(240), label = "rowGrade")

    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> DashboardPalette.CardSelected
            hovered -> DashboardPalette.CardHover
            else -> DashboardPalette.CardBase
        },
        animationSpec = tween(150),
        label = "rowBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> DashboardPalette.Cyan.copy(alpha = 0.62f)
            hovered -> color.copy(alpha = 0.42f)
            else -> DashboardPalette.BorderSubtle.copy(alpha = 0.85f)
        },
        animationSpec = tween(150),
        label = "rowBorder",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DashboardDimens.CardRadius))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(DashboardDimens.CardRadius))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.5.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(listOf(DashboardPalette.CyanBright, color))
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .drawBehind {
                        drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension * 0.95f)
                    }
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stats.target.name,
                    color = DashboardPalette.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stats.target.region,
                    color = DashboardPalette.TextMuted,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MiniSparkline(
                samples = stats.samples,
                color = color,
                modifier = Modifier.width(50.dp).height(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = fmtDashboardMs(stats.currentMs),
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "±${stats.jitterMs?.roundToInt() ?: 0}ms",
                    color = DashboardPalette.TextDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = DashboardPalette.Coral,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// =============================================================================
// 2. Center Telemetry Panel, Route Strip, Cinematic Hero & Graph
// =============================================================================

@Composable
private fun MainTelemetryPanel(
    modifier: Modifier,
    stats: ServerStats?,
    isRunning: Boolean,
    intervalSec: Int,
    pulse: Float,
    flow: Float,
) {
    val accent by animateColorAsState(
        targetValue = stats?.grade?.dashboardColor() ?: DashboardPalette.Cyan,
        animationSpec = tween(300),
        label = "mainAccent",
    )

    CyberPanel(
        modifier = modifier,
        cornerRadius = DashboardDimens.PanelRadius,
        topColor = Color(0xFF0A1422),
        bottomColor = Color(0xFF060D16),
        accentGlow = accent.copy(alpha = 0.7f),
        elevation = 14.dp,
    ) {
        if (stats == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No server selected", color = DashboardPalette.TextMuted, fontSize = 12.sp)
            }
            return@CyberPanel
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top server title bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.20f), DashboardPalette.Cyan.copy(alpha = 0.08f))
                            )
                        )
                        .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${stats.target.name}  ·  ${stats.target.category.label}",
                        color = DashboardPalette.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${stats.target.region}  ·  TCP/${stats.target.port}",
                        color = DashboardPalette.TextMuted,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                GradeChip(stats.grade, accent)
            }

            TargetPathStrip(stats = stats, color = accent, isRunning = isRunning, flow = flow)

            LatencyHero(
                stats = stats,
                color = accent,
                isRunning = isRunning,
                intervalSec = intervalSec,
                pulse = pulse,
                flow = flow,
                modifier = Modifier.fillMaxWidth().height(250.dp),
            )

            // Latency Timeline Graph Panel
            CyberPanel(
                modifier = Modifier.fillMaxWidth().weight(1f),
                cornerRadius = DashboardDimens.InnerRadius,
                topColor = Color(0xFF08121E),
                bottomColor = Color(0xFF050B13),
                elevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "LATENCY TIMELINE",
                            color = DashboardPalette.TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        stats.avgMs?.let { avg ->
                            Text(
                                text = "AVG ${avg.roundToInt()}ms",
                                color = DashboardPalette.TextDim,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        CyberStatusPill(live = isRunning, pulse = pulse)
                    }
                    DashboardLatencyGraph(
                        samples = stats.samples,
                        lineColor = accent,
                        avgMs = stats.avgMs,
                        pulse = pulse,
                        isRunning = isRunning,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetPathStrip(
    stats: ServerStats,
    color: Color,
    isRunning: Boolean,
    flow: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF091523), Color(0xFF07111C), Color(0xFF091523))
                )
            )
            .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(DashboardDimens.InnerRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathNode("YOU", DashboardPalette.Cyan)
        PathLink(
            startColor = DashboardPalette.Cyan,
            endColor = Color(0xFF4CD3FF),
            isRunning = isRunning,
            flow = flow,
            phaseOffset = 0f,
        )
        PathNode("CORE", Color(0xFF4CD3FF))
        PathLink(
            startColor = Color(0xFF4CD3FF),
            endColor = color,
            isRunning = isRunning,
            flow = flow,
            phaseOffset = 0.5f,
        )
        PathNode(stats.target.name.uppercase().take(12), color)

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(DashboardPalette.PanelRecessed)
                .border(1.dp, DashboardPalette.BorderSubtle.copy(alpha = 0.8f), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "${stats.target.host}:${stats.target.port}",
                color = DashboardPalette.TextMuted,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PathNode(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .drawBehind {
                    drawCircle(color.copy(alpha = 0.40f), radius = size.minDimension * 1.05f)
                }
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            color = color,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RowScope.PathLink(
    startColor: Color,
    endColor: Color,
    isRunning: Boolean,
    flow: Float,
    phaseOffset: Float,
) {
    Canvas(
        modifier = Modifier
            .weight(0.28f)
            .padding(horizontal = 6.dp)
            .height(10.dp),
    ) {
        val cy = size.height * 0.5f
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(startColor.copy(alpha = 0.35f), endColor.copy(alpha = 0.35f))
            ),
            start = Offset(0f, cy),
            end = Offset(size.width, cy),
            strokeWidth = 1.4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), phase = 0f),
        )
        if (isRunning && size.width > 4f) {
            val t = (flow + phaseOffset) % 1f
            val px = t * size.width
            drawCircle(
                color = endColor.copy(alpha = 0.32f),
                radius = 4.8f,
                center = Offset(px, cy),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.92f),
                radius = 1.9f,
                center = Offset(px, cy),
            )
        }
    }
}

@Composable
private fun LatencyHero(
    stats: ServerStats,
    color: Color,
    isRunning: Boolean,
    intervalSec: Int,
    pulse: Float,
    flow: Float,
    modifier: Modifier = Modifier,
) {
    val animatedRtt by animateFloatAsState(
        targetValue = (stats.currentMs ?: 0.0).toFloat(),
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "heroRtt",
    )
    // Gentle breathing intensity for the ping glow
    val breath = 0.5f + 0.5f * sin(pulse * 2f * PI.toFloat())

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DashboardDimens.PanelRadius))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071320), Color(0xFF050E18), Color(0xFF060E17))
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        color.copy(alpha = 0.38f),
                        DashboardPalette.BorderSubtle.copy(alpha = 0.9f),
                        Color(0xFF0E1D2E),
                    )
                ),
                RoundedCornerShape(DashboardDimens.PanelRadius),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.43f

            // Deep cinematic radial spotlight behind the latency number
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = 0.14f + breath * 0.05f),
                        DashboardPalette.Cyan.copy(alpha = 0.04f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = w * 0.44f,
                )
            )

            // Subtle coordinate grid
            val gridColor = Color(0xFF1A3650)
            for (i in 1..5) {
                val y = h * i / 6f
                drawLine(
                    color = gridColor.copy(alpha = 0.18f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
            }
            for (i in 1..9) {
                val x = w * i / 10f
                drawLine(
                    color = gridColor.copy(alpha = 0.10f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
            }

            // Ultra-faint scanline ambience (Matrix / cyber HUD texture)
            var scanY = 6f
            while (scanY < h) {
                drawLine(
                    color = DashboardPalette.MatrixGreen.copy(alpha = 0.025f),
                    start = Offset(0f, scanY),
                    end = Offset(w, scanY),
                    strokeWidth = 1f,
                )
                scanY += 6f
            }

            // Soft moving scan beam
            if (isRunning) {
                val beamY = flow * h
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            DashboardPalette.MatrixGreen.copy(alpha = 0.045f),
                            Color.Transparent,
                        ),
                        startY = beamY - 22f,
                        endY = beamY + 22f,
                    ),
                    topLeft = Offset(0f, (beamY - 22f).coerceAtLeast(0f)),
                    size = Size(w, 44f),
                )
            }

            // Concentric animated radar pulse waves
            if (isRunning) {
                val p1 = pulse
                val p2 = (pulse + 0.5f) % 1f
                listOf(p1, p2).forEach { p ->
                    drawCircle(
                        color = color.copy(alpha = (1f - p) * 0.22f),
                        radius = 48f + p * 110f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.4f),
                    )
                }
            }

            // Calibrated inner orbital ring with micro-ticks
            val ringRadius = 74f
            drawCircle(
                color = color.copy(alpha = 0.11f),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), phase = flow * 24f),
                ),
            )
            for (deg in 0 until 360 step 30) {
                val rad = deg * PI.toFloat() / 180f
                val inner = ringRadius + 4f
                val outer = ringRadius + if (deg % 90 == 0) 11f else 7f
                drawLine(
                    color = color.copy(alpha = if (deg % 90 == 0) 0.30f else 0.14f),
                    start = Offset(cx + cos(rad) * inner, cy + sin(rad) * inner),
                    end = Offset(cx + cos(rad) * outer, cy + sin(rad) * outer),
                    strokeWidth = 1.2f,
                )
            }

            // Faint digital trails / sweeping network signal paths
            val offsets = listOf(-50f, -24f, 4f, 30f, 54f)
            offsets.forEachIndexed { index, offset ->
                val drift = sin((flow * 2f * PI + index * 0.9).toFloat()) * (6f + index * 1.2f)
                val path = Path().apply {
                    moveTo(-24f, cy + offset + drift)
                    cubicTo(
                        w * 0.22f, cy + offset * 0.18f - 30f,
                        w * 0.38f, cy - offset * 0.16f + 24f,
                        cx, cy + offset * 0.04f,
                    )
                    cubicTo(
                        w * 0.64f, cy + offset * 0.12f - 22f,
                        w * 0.80f, cy - offset * 0.15f + 30f,
                        w + 24f, cy + offset - drift,
                    )
                }
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.032f),
                    style = Stroke(width = 10f - index * 1.1f, cap = StrokeCap.Round),
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.13f + index * 0.02f),
                    style = Stroke(width = 1.15f + (index % 2) * 0.35f, cap = StrokeCap.Round),
                )
            }

            // Subtle traveling packet sparks on left/right trails
            if (isRunning) {
                val t1 = flow
                val t2 = (flow + 0.55f) % 1f
                val spark1 = Offset(w * (0.08f + t1 * 0.38f), cy - 22f + sin(t1 * PI.toFloat()) * 14f)
                val spark2 = Offset(w * (0.54f + t2 * 0.38f), cy + 18f - sin(t2 * PI.toFloat()) * 14f)
                listOf(spark1, spark2).forEach { pt ->
                    drawCircle(color.copy(alpha = 0.36f), radius = 5f, center = pt)
                    drawCircle(Color.White.copy(alpha = 0.88f), radius = 1.8f, center = pt)
                }
            }

            // Subtle cyber corner reticle brackets
            val bLen = 12f
            val pad = 10f
            val bracketColor = color.copy(alpha = 0.34f)
            // Top-left
            drawLine(bracketColor, Offset(pad, pad), Offset(pad + bLen, pad), 1.4f)
            drawLine(bracketColor, Offset(pad, pad), Offset(pad, pad + bLen), 1.4f)
            // Top-right
            drawLine(bracketColor, Offset(w - pad, pad), Offset(w - pad - bLen, pad), 1.4f)
            drawLine(bracketColor, Offset(w - pad, pad), Offset(w - pad, pad + bLen), 1.4f)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top HUD telemetry micro-strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LIVE LATENCY",
                    color = DashboardPalette.TextMuted,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "// SYNC ${intervalSec}000ms",
                    color = DashboardPalette.MatrixGreen.copy(alpha = 0.52f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stats.target.host,
                    color = DashboardPalette.TextDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.weight(1f))

            // Large glowing ping number
            val pingText = if (stats.currentMs == null) {
                "—"
            } else {
                val rounded = (animatedRtt * 10f).roundToInt() / 10.0
                rounded.toString()
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = pingText,
                    style = TextStyle(
                        color = color,
                        fontSize = 60.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = (-1.5).sp,
                        shadow = Shadow(
                            color = color.copy(alpha = 0.45f + breath * 0.25f),
                            offset = Offset.Zero,
                            blurRadius = 22f + breath * 10f,
                        ),
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ms",
                    style = TextStyle(
                        color = color.copy(alpha = 0.88f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        shadow = Shadow(
                            color = color.copy(alpha = 0.35f),
                            offset = Offset.Zero,
                            blurRadius = 10f,
                        ),
                    ),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            Spacer(Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.12f))
                    .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 2.5.dp),
            ) {
                Text(
                    text = stats.grade.label,
                    color = color,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            // Glass metrics dock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
                    .background(DashboardPalette.PanelRecessed.copy(alpha = 0.88f))
                    .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(DashboardDimens.InnerRadius))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MetricsRow(stats)
            }
        }
    }
}

@Composable
private fun MetricsRow(stats: ServerStats) {
    val lossColor = if (stats.lossPercent > 0.0) DashboardPalette.Coral else DashboardPalette.MatrixGreen
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Metric("MIN", stats.minMs?.let { "${it.roundToInt()} ms" } ?: "—", modifier = Modifier.weight(1f))
        MetricDivider()
        Metric("AVG", stats.avgMs?.let { "${it.roundToInt()} ms" } ?: "—", modifier = Modifier.weight(1f))
        MetricDivider()
        Metric("MAX", stats.maxMs?.let { "${it.roundToInt()} ms" } ?: "—", modifier = Modifier.weight(1f))
        MetricDivider()
        Metric("JITTER", stats.jitterMs?.let { "${it.roundToInt()} ms" } ?: "—", modifier = Modifier.weight(1f))
        MetricDivider()
        Metric("LOSS", "${stats.lossPercent.roundToInt()}%", valueColor = lossColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(DashboardPalette.Divider)
    )
}

@Composable
private fun Metric(
    label: String,
    value: String,
    valueColor: Color = DashboardPalette.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = DashboardPalette.TextMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// =============================================================================
// 3. Right Column — Side Insights & Session Panels
// =============================================================================

@Composable
private fun SideInsightsPanel(
    modifier: Modifier,
    stats: List<ServerStats>,
    selected: ServerStats?,
    isRunning: Boolean,
    pulse: Float,
    onSelect: (ServerStats) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CyberPanel(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cornerRadius = DashboardDimens.PanelRadius,
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                CyberSectionHeader(
                    title = "Latency",
                    accent = DashboardPalette.MatrixGreen,
                    trailing = {
                        CyberStatusPill(live = isRunning, pulse = pulse)
                    },
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(stats.take(8), key = { it.target.id }) { s ->
                        RouteCard(
                            stats = s,
                            selected = s.target.id == selected?.target?.id,
                            onClick = { onSelect(s) },
                        )
                    }
                }
            }
        }

        CyberPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = DashboardDimens.PanelRadius,
        ) {
            Column(Modifier.padding(12.dp)) {
                CyberSectionHeader(
                    title = "Session",
                    accent = DashboardPalette.Cyan,
                    trailing = {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = DashboardPalette.Cyan,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                )
                Spacer(Modifier.height(9.dp))
                InfoLine("Selected", selected?.target?.name ?: "—")
                InfoDivider()
                InfoLine("Port", selected?.target?.port?.let { "TCP / $it" } ?: "—", mono = true)
                InfoDivider()
                InfoLine("Samples", "${selected?.samples?.size ?: 0} / 48", mono = true)
                InfoDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Quality",
                        color = DashboardPalette.TextMuted,
                        fontSize = 9.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    val grade = selected?.grade ?: QualityGrade.WAITING
                    GradeChip(grade, grade.dashboardColor())
                }
            }
        }
    }
}

@Composable
private fun RouteCard(stats: ServerStats, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(stats.grade.dashboardColor(), tween(240), label = "routeGrade")

    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> DashboardPalette.CardSelected
            hovered -> DashboardPalette.CardHover
            else -> DashboardPalette.CardBase
        },
        animationSpec = tween(150),
        label = "routeBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> DashboardPalette.Cyan.copy(alpha = 0.58f)
            hovered -> color.copy(alpha = 0.42f)
            else -> DashboardPalette.BorderSubtle.copy(alpha = 0.85f)
        },
        animationSpec = tween(150),
        label = "routeBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DashboardDimens.CardRadius))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(DashboardDimens.CardRadius))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .drawBehind {
                        drawCircle(color.copy(alpha = 0.36f), radius = size.minDimension)
                    }
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stats.target.name,
                color = DashboardPalette.TextPrimary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fmtDashboardMs(stats.currentMs),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        MiniSparkline(
            samples = stats.samples,
            color = color,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DashboardPalette.TextMuted,
            fontSize = 9.5.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = DashboardPalette.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DashboardPalette.Divider.copy(alpha = 0.75f))
    )
}

@Composable
private fun GradeChip(grade: QualityGrade, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.36f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
    ) {
        Text(
            text = grade.label,
            color = color,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.9.sp,
        )
    }
}

// =============================================================================
// 4. Upgraded Smooth Graphs & Sparklines
// =============================================================================

@Composable
private fun MiniSparkline(
    samples: List<PingSample>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val values = samples.mapNotNull { it.rttMs }
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas

        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val min = values.minOrNull() ?: 0.0
        val range = (max - min).coerceAtLeast(1.0)
        val step = size.width / (values.size - 1)

        val points = values.mapIndexed { i, v ->
            val x = i * step
            val y = size.height - (((v - min) / range).toFloat() * (size.height - 6f)) - 3f
            Offset(x, y)
        }

        val linePath = buildSmoothPath(points)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.22f), Color.Transparent)
            ),
        )
        drawPath(
            path = linePath,
            color = color.copy(alpha = 0.24f),
            style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 1.8f,
            center = points.last(),
        )
    }
}

@Composable
private fun DashboardLatencyGraph(
    samples: List<PingSample>,
    lineColor: Color,
    avgMs: Double?,
    pulse: Float,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val grid = Color(0xFF1A3650)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f), 0f)

        // Subtle horizontal dashed gridlines
        for (i in 1..4) {
            val y = size.height * i / 5f
            drawLine(
                color = grid.copy(alpha = 0.48f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )
        }
        // Subtle vertical time gridlines
        for (i in 1..7) {
            val x = size.width * i / 8f
            drawLine(
                color = grid.copy(alpha = 0.20f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
        }
        // Baseline
        drawLine(
            color = grid.copy(alpha = 0.65f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )

        if (samples.isEmpty()) return@Canvas
        val valid = samples.mapNotNull { it.rttMs }
        if (valid.isEmpty()) return@Canvas

        val maxValue = ((valid.maxOrNull() ?: 50.0) * 1.22).coerceAtLeast(30.0)
        val stepX = if (samples.size > 1) size.width / (samples.size - 1) else size.width

        // Subtle average reference line
        avgMs?.let { avg ->
            val avgY = size.height - (avg / maxValue).toFloat().coerceIn(0f, 1f) * (size.height - 14f) - 7f
            drawLine(
                color = lineColor.copy(alpha = 0.28f),
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
            )
        }

        val canvasHeight = size.height
        val points = buildList {
            samples.forEachIndexed { index, sample ->
                val value = sample.rttMs ?: return@forEachIndexed
                val x = index * stepX
                val y = canvasHeight - (value / maxValue).toFloat().coerceIn(0f, 1f) * (canvasHeight - 14f) - 7f
                add(Offset(x, y))
            }
        }
        if (points.isEmpty()) return@Canvas

        val linePath = buildSmoothPath(points)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }

        // Multi-stop soft vertical gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to lineColor.copy(alpha = 0.30f),
                    0.55f to lineColor.copy(alpha = 0.09f),
                    1.0f to Color.Transparent,
                )
            ),
        )

        // Multi-pass neon glow around active line
        drawPath(
            path = linePath,
            color = lineColor.copy(alpha = 0.11f),
            style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = linePath,
            color = lineColor.copy(alpha = 0.26f),
            style = Stroke(width = 5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Pulsing live endpoint beacon
        val last = points.last()
        if (isRunning) {
            drawCircle(
                color = lineColor.copy(alpha = (1f - pulse) * 0.45f),
                radius = 5f + pulse * 9f,
                center = last,
            )
        }
        drawCircle(color = lineColor.copy(alpha = 0.45f), radius = 5.2f, center = last)
        drawCircle(color = Color.White, radius = 2.6f, center = last)
    }
}

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path
    for (i in 0 until points.lastIndex) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val midX = (p0.x + p1.x) * 0.5f
        path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
    }
    return path
}

// =============================================================================
// Dialogs
// =============================================================================

@Composable
private fun MaintenanceDialog(
    feature: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DashboardDimens.PanelRadius),
        containerColor = Color(0xFF0B1522),
        icon = {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DashboardPalette.Amber.copy(alpha = 0.14f))
                    .border(1.dp, DashboardPalette.Amber.copy(alpha = 0.42f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Hardware,
                    contentDescription = null,
                    tint = DashboardPalette.Amber,
                    modifier = Modifier.size(23.dp),
                )
            }
        },
        title = {
            Text(
                text = "Under maintenance",
                color = DashboardPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "$feature is still being engineered.",
                    color = DashboardPalette.TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    text = "Updates coming soon.",
                    color = DashboardPalette.Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = DashboardPalette.CyanBright, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun DashboardAddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DashboardDimens.PanelRadius),
        containerColor = Color(0xFF0B1522),
        title = {
            Text(
                text = "Add custom target",
                color = DashboardPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
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
                        modifier = Modifier.weight(0.7f),
                    )
                    OutlinedTextField(
                        region,
                        { region = it },
                        label = { Text("Region") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                    )
                }
                error?.let { Text(it, color = DashboardPalette.Coral, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = port.toIntOrNull()
                    when {
                        name.isBlank() -> error = "Enter a name"
                        host.isBlank() -> error = "Enter a host or IP"
                        p == null || p !in 1..65535 -> error = "Port must be 1..65535"
                        else -> onAdd(name, region, host, p)
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun QualityGrade.dashboardColor(): Color = when (this) {
    QualityGrade.EXCELLENT -> DashboardPalette.MatrixGreen
    QualityGrade.GOOD -> DashboardPalette.Cyan
    QualityGrade.FAIR -> DashboardPalette.Amber
    QualityGrade.POOR -> DashboardPalette.Coral
    QualityGrade.WAITING -> Color(0xFF6D8399)
}

private fun fmtDashboardMs(value: Double?): String =
    if (value == null) "—" else "${(value * 10.0).roundToInt() / 10.0} ms"
