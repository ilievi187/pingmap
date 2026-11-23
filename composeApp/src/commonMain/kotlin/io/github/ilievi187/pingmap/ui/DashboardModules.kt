package io.github.ilievi187.pingmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ilievi187.pingmap.model.GameCategory
import io.github.ilievi187.pingmap.model.QualityGrade
import io.github.ilievi187.pingmap.model.ServerStats
import io.github.ilievi187.pingmap.net.DnsLookupResult
import io.github.ilievi187.pingmap.net.probeTcpLatency
import io.github.ilievi187.pingmap.net.resolveDnsHost
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class DashboardSection(val title: String) {
    OVERVIEW("Overview"),
    LIVE_LATENCY("Live Latency"),
    DNS_TOOLS("DNS Tools"),
    ANALYTICS("Analytics"),
    TOPOLOGY("Network Topology"),
}

internal enum class EventSeverity { INFO, SUCCESS, WARN, ALERT }

internal data class TelemetryEvent(
    val id: Int,
    val tag: String,
    val message: String,
    val severity: EventSeverity,
)

internal data class DnsResolverTarget(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val note: String,
)

internal data class DnsBenchmarkRow(
    val target: DnsResolverTarget,
    val samples: List<Double?> = emptyList(),
) {
    val valid: List<Double> get() = samples.filterNotNull()
    val avgMs: Double? get() = if (valid.isEmpty()) null else valid.average()
    val minMs: Double? get() = valid.minOrNull()
    val lossPct: Double get() = if (samples.isEmpty()) 0.0 else samples.count { it == null } * 100.0 / samples.size
}

internal val DefaultDnsResolvers = listOf(
    DnsResolverTarget("cf-1", "Cloudflare", "1.1.1.1", 443, "Anycast · Ultra-low latency"),
    DnsResolverTarget("cf-sec", "Cloudflare Malware", "1.1.1.2", 443, "Anycast · Security filtered"),
    DnsResolverTarget("gg-1", "Google Public DNS", "8.8.8.8", 443, "Global Anycast backbone"),
    DnsResolverTarget("q9-1", "Quad9", "9.9.9.9", 443, "Threat-blocking · Swiss privacy"),
    DnsResolverTarget("od-1", "OpenDNS", "208.67.222.222", 53, "Cisco Umbrella Anycast"),
    DnsResolverTarget("ag-1", "AdGuard DNS", "94.140.14.14", 443, "Ad & tracker filtering"),
    DnsResolverTarget("cd-1", "Control D", "76.76.2.0", 443, "Global low-latency resolver"),
    DnsResolverTarget("nx-1", "NextDNS", "45.90.28.0", 443, "Anycast cloud resolver"),
)

internal fun buildCsvReport(stats: Collection<ServerStats>): String {
    val sb = StringBuilder()
    sb.appendLine("id,name,category,region,host,port,samples,current_ms,min_ms,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,jitter_ms,loss_pct,stability_score,grade")
    stats.sortedBy { it.avgMs ?: Double.MAX_VALUE }.forEach { s ->
        fun num(v: Double?): String = v?.let { ((it * 10.0).roundToInt() / 10.0).toString() } ?: ""
        val safeName = s.target.name.replace(",", " ")
        val safeRegion = s.target.region.replace(",", " ")
        sb.appendLine(
            listOf(
                s.target.id,
                safeName,
                s.target.category.label,
                safeRegion,
                s.target.host,
                s.target.port.toString(),
                s.samples.size.toString(),
                num(s.currentMs),
                num(s.minMs),
                num(s.avgMs),
                num(s.p50Ms),
                num(s.p95Ms),
                num(s.p99Ms),
                num(s.maxMs),
                num(s.jitterMs),
                num(s.lossPercent),
                s.stabilityScore.toString(),
                s.grade.label,
            ).joinToString(",")
        )
    }
    return sb.toString()
}

// =============================================================================
// 1. OVERVIEW — Global Command Center
// =============================================================================

@Composable
internal fun OverviewScreen(
    statsMap: Map<String, ServerStats>,
    events: List<TelemetryEvent>,
    alertThresholdMs: Int?,
    isBursting: Boolean,
    isProbingAll: Boolean,
    pulse: Float,
    onJumpToServer: (GameCategory, String) -> Unit,
    onProbeAll: () -> Unit,
    onBurst: () -> Unit,
    onExportCsv: () -> Unit,
) {
    val allStats = remember(statsMap) { statsMap.values.toList() }
    val measured = remember(allStats) { allStats.filter { it.avgMs != null } }
    val healthScore = remember(measured) {
        if (measured.isEmpty()) 0 else measured.map { it.stabilityScore }.average().roundToInt()
    }
    val globalAvg = remember(measured) {
        if (measured.isEmpty()) null else measured.mapNotNull { it.avgMs }.average()
    }
    val fastest = remember(measured) {
        measured.minWithOrNull(compareBy<ServerStats> { it.lossPercent > 1.0 }.thenBy { it.avgMs ?: Double.MAX_VALUE })
    }
    val alertCount = remember(measured, alertThresholdMs) {
        measured.count { s ->
            s.lossPercent > 0.0 || (alertThresholdMs != null && (s.currentMs ?: 0.0) > alertThresholdMs)
        }
    }

    val healthColor = when {
        measured.isEmpty() -> DashboardPalette.TextMuted
        healthScore >= 82 -> DashboardPalette.MatrixGreen
        healthScore >= 62 -> DashboardPalette.Cyan
        healthScore >= 40 -> DashboardPalette.Amber
        else -> DashboardPalette.Coral
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top KPI Row
        Row(
            modifier = Modifier.fillMaxWidth().height(126.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Radial Network Health Score Card
            CyberPanel(
                modifier = Modifier.weight(1.25f).fillMaxHeight(),
                accentGlow = healthColor.copy(alpha = 0.7f),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = Stroke(width = 7.5f, cap = StrokeCap.Round)
                            drawArc(
                                color = DashboardPalette.BorderSubtle,
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = stroke,
                            )
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(DashboardPalette.Cyan, healthColor, DashboardPalette.MatrixGreen)
                                ),
                                startAngle = 135f,
                                sweepAngle = 270f * (healthScore / 100f),
                                useCenter = false,
                                style = stroke,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (measured.isEmpty()) "—" else "$healthScore",
                                color = healthColor,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "HEALTH",
                                color = DashboardPalette.TextDim,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "NETWORK READINESS",
                            color = DashboardPalette.TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                        Text(
                            text = when {
                                measured.isEmpty() -> "Collecting baseline…"
                                healthScore >= 82 -> "Tournament Ready"
                                healthScore >= 62 -> "Stable Connection"
                                healthScore >= 40 -> "Moderate Jitter"
                                else -> "Degraded Link"
                            },
                            color = DashboardPalette.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${measured.size} / ${allStats.size} nodes active",
                            color = DashboardPalette.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            OverviewKpiTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "FASTEST NODE",
                value = fastest?.let { fmtDashboardMs(it.avgMs) } ?: "—",
                subtext = fastest?.let { "${it.target.name} · ${it.target.category.label}" } ?: "Waiting for probe…",
                accent = DashboardPalette.MatrixGreen,
                icon = Icons.Default.Bolt,
            )
            OverviewKpiTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "GLOBAL AVERAGE",
                value = fmtDashboardMs(globalAvg),
                subtext = "Across ${measured.size} measured endpoints",
                accent = DashboardPalette.Cyan,
                icon = Icons.Default.Public,
            )
            OverviewKpiTile(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "ACTIVE ALERTS",
                value = "$alertCount",
                subtext = if (alertThresholdMs != null) "Guard > ${alertThresholdMs}ms or loss" else "Loss alerts active",
                accent = if (alertCount > 0) DashboardPalette.Coral else DashboardPalette.MatrixGreen,
                icon = Icons.Default.NotificationsActive,
            )
        }

        // Main Split: Game Readiness Cards + Event Feed & Quick Actions
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CyberPanel(
                modifier = Modifier.weight(1.55f).fillMaxHeight(),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    CyberSectionHeader(
                        title = "Best Server by Game Category",
                        accent = DashboardPalette.Cyan,
                        trailing = {
                            CyberActionChip(
                                label = if (isProbingAll) "Scanning All…" else "Scan All Games",
                                icon = Icons.Default.Radar,
                                accent = DashboardPalette.MatrixGreen,
                                enabled = !isProbingAll,
                                onClick = onProbeAll,
                            )
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    val categories = GameCategory.entries.filter { it != GameCategory.ALL }
                    val rows = categories.chunked(2)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rows) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                pair.forEach { cat ->
                                    val catStats = allStats.filter { it.target.category == cat }
                                    val best = catStats
                                        .filter { it.avgMs != null }
                                        .minWithOrNull(
                                            compareBy<ServerStats> { it.lossPercent > 1.0 }
                                                .thenBy { it.avgMs ?: Double.MAX_VALUE }
                                        )
                                    CategoryReadinessCard(
                                        modifier = Modifier.weight(1f),
                                        category = cat,
                                        best = best,
                                        totalCount = catStats.size,
                                        onClick = {
                                            val targetId = best?.target?.id ?: catStats.firstOrNull()?.target?.id
                                            if (targetId != null) onJumpToServer(cat, targetId)
                                        },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Right column: Quick Actions + Live Telemetry Feed
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CyberPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        CyberSectionHeader(title = "Command Actions", accent = DashboardPalette.MatrixGreen)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CyberActionChip(
                                label = if (isBursting) "5× Burst…" else "5× Burst Test",
                                icon = Icons.Default.FlashOn,
                                accent = DashboardPalette.Cyan,
                                enabled = !isBursting,
                                modifier = Modifier.weight(1f),
                                onClick = onBurst,
                            )
                            CyberActionChip(
                                label = "Export CSV",
                                icon = Icons.Default.Download,
                                accent = DashboardPalette.MatrixGreen,
                                modifier = Modifier.weight(1f),
                                onClick = onExportCsv,
                            )
                        }
                    }
                }

                CyberPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        CyberSectionHeader(
                            title = "Telemetry Event Log",
                            accent = DashboardPalette.Cyan,
                            trailing = { CyberStatusPill(live = true, pulse = pulse, labelLive = "${events.size} EVENTS") },
                        )
                        Spacer(Modifier.height(10.dp))
                        if (events.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No anomalies detected. Network nominal.",
                                    color = DashboardPalette.TextDim,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(events, key = { it.id }) { ev ->
                                    EventFeedRow(ev)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewKpiTile(
    modifier: Modifier,
    label: String,
    value: String,
    subtext: String,
    accent: Color,
    icon: ImageVector,
) {
    CyberPanel(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = DashboardPalette.TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.34f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                text = value,
                color = accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                text = subtext,
                color = DashboardPalette.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryReadinessCard(
    modifier: Modifier,
    category: GameCategory,
    best: ServerStats?,
    totalCount: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = best?.grade?.dashboardColor() ?: DashboardPalette.TextMuted
    val borderColor by animateColorAsState(
        if (hovered) DashboardPalette.Cyan.copy(alpha = 0.58f) else DashboardPalette.BorderSubtle,
        tween(150),
        label = "catCardBorder",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
            .background(if (hovered) DashboardPalette.CardHover else DashboardPalette.CardBase)
            .border(1.dp, borderColor, RoundedCornerShape(DashboardDimens.InnerRadius))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = category.label,
                color = DashboardPalette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (best != null) {
                GradeChip(best.grade, color)
            } else {
                Text(
                    text = "$totalCount nodes",
                    color = DashboardPalette.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        if (best == null) {
            Text(
                text = if (totalCount == 0) "No custom servers yet" else "Click or Scan All to measure",
                color = DashboardPalette.TextMuted,
                fontSize = 10.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = best.target.name,
                        color = DashboardPalette.TextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Jitter ${fmtDashboardMs(best.jitterMs)} · Score ${best.stabilityScore}",
                        color = DashboardPalette.TextDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = fmtDashboardMs(best.avgMs),
                    color = color,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            MiniSparkline(best.samples, color, Modifier.fillMaxWidth().height(24.dp))
        }
    }
}

@Composable
private fun EventFeedRow(event: TelemetryEvent) {
    val color = when (event.severity) {
        EventSeverity.INFO -> DashboardPalette.Cyan
        EventSeverity.SUCCESS -> DashboardPalette.MatrixGreen
        EventSeverity.WARN -> DashboardPalette.Amber
        EventSeverity.ALERT -> DashboardPalette.Coral
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DashboardPalette.PanelRecessed)
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = event.tag,
            color = color,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = event.message,
            color = DashboardPalette.TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// =============================================================================
// 2. DNS TOOLS — Live DNS Benchmark & Domain Lookup Lab
// =============================================================================

@Composable
internal fun DnsToolsScreen(
    rows: List<DnsBenchmarkRow>,
    isBenchmarking: Boolean,
    onRunBenchmark: () -> Unit,
    onLogEvent: (String, String, EventSeverity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lookupHost by remember { mutableStateOf("steampowered.com") }
    var isLookingUp by remember { mutableStateOf(false) }
    var lookupResult by remember { mutableStateOf<DnsLookupResult?>(null) }

    val ranked = remember(rows) {
        rows.sortedWith(compareBy<DnsBenchmarkRow> { it.avgMs == null }.thenBy { it.avgMs ?: Double.MAX_VALUE })
    }
    val maxAvg = remember(ranked) {
        (ranked.mapNotNull { it.avgMs }.maxOrNull() ?: 60.0).coerceAtLeast(25.0)
    }
    val bestTwo = remember(ranked) { ranked.filter { it.avgMs != null }.take(2) }

    fun triggerLookup(domain: String) {
        lookupHost = domain
        scope.launch {
            isLookingUp = true
            val res = resolveDnsHost(domain)
            lookupResult = res
            isLookingUp = false
            if (res.error == null) {
                onLogEvent("DNS", "Resolved ${res.host} (${res.addresses.firstOrNull() ?: "—"}) in ${fmtDashboardMs(res.latencyMs)}", EventSeverity.INFO)
            } else {
                onLogEvent("DNS", "Lookup failed for ${res.host}: ${res.error}", EventSeverity.WARN)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left: DNS Resolver Benchmark
        CyberPanel(
            modifier = Modifier.weight(1.45f).fillMaxHeight(),
            accentGlow = DashboardPalette.Cyan.copy(alpha = 0.55f),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                CyberSectionHeader(
                    title = "DNS Resolver Benchmark",
                    accent = DashboardPalette.MatrixGreen,
                    trailing = {
                        CyberActionChip(
                            label = if (isBenchmarking) "Benchmarking…" else "Run 3× Benchmark",
                            icon = Icons.Default.Speed,
                            accent = DashboardPalette.MatrixGreen,
                            enabled = !isBenchmarking,
                            onClick = onRunBenchmark,
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Measures real TCP handshake latency to major gaming & privacy Anycast DNS resolvers.",
                    color = DashboardPalette.TextMuted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(12.dp))

                // Recommended DNS Banner
                if (bestTwo.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(DashboardPalette.MatrixGreen.copy(alpha = 0.14f), DashboardPalette.PanelRecessed)
                                )
                            )
                            .border(1.dp, DashboardPalette.MatrixGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Verified, null, tint = DashboardPalette.MatrixGreen, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "RECOMMENDED GAMING DNS PAIR",
                                color = DashboardPalette.MatrixGreen,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                            )
                            val primary = bestTwo[0]
                            val secondary = bestTwo.getOrNull(1)
                            Text(
                                text = buildString {
                                    append("Primary: ${primary.target.ip} (${primary.target.name})")
                                    if (secondary != null) append("   ·   Secondary: ${secondary.target.ip} (${secondary.target.name})")
                                },
                                color = DashboardPalette.TextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(ranked, key = { it.target.id }) { row ->
                        val rank = ranked.indexOf(row) + 1
                        val isWinner = rank == 1 && row.avgMs != null
                        val barFraction = ((row.avgMs ?: 0.0) / maxAvg).toFloat().coerceIn(0f, 1f)
                        val barColor = when {
                            row.avgMs == null -> DashboardPalette.TextMuted
                            row.avgMs!! <= 22.0 -> DashboardPalette.MatrixGreen
                            row.avgMs!! <= 45.0 -> DashboardPalette.Cyan
                            row.avgMs!! <= 80.0 -> DashboardPalette.Amber
                            else -> DashboardPalette.Coral
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(DashboardDimens.CardRadius))
                                .background(if (isWinner) DashboardPalette.CardSelected else DashboardPalette.CardBase)
                                .border(
                                    1.dp,
                                    if (isWinner) DashboardPalette.MatrixGreen.copy(alpha = 0.55f)
                                    else DashboardPalette.BorderSubtle,
                                    RoundedCornerShape(DashboardDimens.CardRadius),
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "#$rank",
                                    color = if (isWinner) DashboardPalette.MatrixGreen else DashboardPalette.TextDim,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(24.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text(
                                            text = row.target.name,
                                            color = DashboardPalette.TextPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = row.target.ip,
                                            color = DashboardPalette.Cyan,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    Text(
                                        text = row.target.note,
                                        color = DashboardPalette.TextMuted,
                                        fontSize = 9.sp,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = fmtDashboardMs(row.avgMs),
                                        color = barColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = if (row.samples.isEmpty()) "Not tested"
                                        else "Min ${fmtDashboardMs(row.minMs)} · Loss ${row.lossPct.roundToInt()}%",
                                        color = DashboardPalette.TextDim,
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            // Neon progress bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(DashboardPalette.PanelRecessed),
                            ) {
                                if (row.avgMs != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(barFraction.coerceAtLeast(0.06f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(barColor.copy(alpha = 0.65f), barColor)
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right: Live Domain Resolution Tester
        CyberPanel(
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CyberSectionHeader(title = "Domain Lookup Tester", accent = DashboardPalette.Cyan)
                Text(
                    text = "Test real system DNS resolution latency and inspect resolved IP endpoints.",
                    color = DashboardPalette.TextMuted,
                    fontSize = 10.sp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(DashboardPalette.PanelRecessed)
                            .border(1.dp, DashboardPalette.BorderHighlight.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = lookupHost,
                            onValueChange = { lookupHost = it },
                            singleLine = true,
                            cursorBrush = SolidColor(DashboardPalette.Cyan),
                            textStyle = TextStyle(
                                color = DashboardPalette.TextPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CyberActionChip(
                        label = if (isLookingUp) "Resolving…" else "Resolve",
                        icon = Icons.Default.Search,
                        accent = DashboardPalette.Cyan,
                        enabled = !isLookingUp,
                        onClick = { triggerLookup(lookupHost) },
                    )
                }

                // Quick preset domain chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("steampowered.com", "riotgames.com", "faceit.com", "cloudflare.com", "discord.com").forEach { d ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DashboardPalette.CardBase)
                                .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(8.dp))
                                .clickable { triggerLookup(d) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(d, color = DashboardPalette.TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Result Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
                        .background(DashboardPalette.PanelRecessed)
                        .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(DashboardDimens.InnerRadius))
                        .padding(14.dp),
                ) {
                    val res = lookupResult
                    if (res == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Click Resolve or pick a preset domain above.",
                                color = DashboardPalette.TextDim,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else if (res.error != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("RESOLUTION FAILED", color = DashboardPalette.Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(res.error, color = DashboardPalette.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("RESOLVED HOST", color = DashboardPalette.TextDim, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                    Text(res.host, color = DashboardPalette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(DashboardPalette.MatrixGreen.copy(alpha = 0.14f))
                                        .border(1.dp, DashboardPalette.MatrixGreen.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = fmtDashboardMs(res.latencyMs),
                                        color = DashboardPalette.MatrixGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            Text(
                                text = "IP ADDRESSES (${res.addresses.size})",
                                color = DashboardPalette.TextMuted,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                items(res.addresses) { ip ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DashboardPalette.CardBase)
                                            .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(DashboardPalette.Cyan))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ip, color = DashboardPalette.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// 3. ANALYTICS — Percentiles, Distribution Histogram & Head-to-Head Comparison
// =============================================================================

@Composable
internal fun AnalyticsScreen(
    stats: List<ServerStats>,
    selected: ServerStats?,
    alertThresholdMs: Int?,
    onSelect: (ServerStats) -> Unit,
    onExportCsv: () -> Unit,
) {
    val focus = selected ?: stats.firstOrNull()
    val focusColor = focus?.grade?.dashboardColor() ?: DashboardPalette.Cyan

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top Percentile & Stability KPI Strip
        Row(
            modifier = Modifier.fillMaxWidth().height(92.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AnalyticsStatCard("P50 MEDIAN", fmtDashboardMs(focus?.p50Ms), DashboardPalette.Cyan, Modifier.weight(1f))
            AnalyticsStatCard("P95 TAIL", fmtDashboardMs(focus?.p95Ms), DashboardPalette.CyanBright, Modifier.weight(1f))
            AnalyticsStatCard("P99 PEAK", fmtDashboardMs(focus?.p99Ms), DashboardPalette.Amber, Modifier.weight(1f))
            AnalyticsStatCard(
                "STABILITY",
                if (focus?.avgMs == null) "—" else "${focus.stabilityScore}/100",
                if ((focus?.stabilityScore ?: 0) >= 75) DashboardPalette.MatrixGreen else DashboardPalette.Amber,
                Modifier.weight(1f),
            )
            AnalyticsStatCard(
                "SPIKES",
                "${focus?.spikeCount(alertThresholdMs?.toDouble()) ?: 0}",
                if ((focus?.spikeCount(alertThresholdMs?.toDouble()) ?: 0) > 0) DashboardPalette.Coral else DashboardPalette.MatrixGreen,
                Modifier.weight(1f),
            )
            AnalyticsStatCard(
                "PACKET LOSS",
                "${focus?.lossPercent?.roundToInt() ?: 0}%",
                if ((focus?.lossPercent ?: 0.0) > 0.0) DashboardPalette.Coral else DashboardPalette.MatrixGreen,
                Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Left: Latency Distribution Histogram
            CyberPanel(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                accentGlow = focusColor.copy(alpha = 0.55f),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    CyberSectionHeader(
                        title = "Latency Distribution · ${focus?.target?.name ?: "—"}",
                        accent = focusColor,
                        trailing = {
                            CyberActionChip(
                                label = "Export CSV",
                                icon = Icons.Default.Download,
                                accent = DashboardPalette.MatrixGreen,
                                onClick = onExportCsv,
                            )
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    val rtts = focus?.validRtts ?: emptyList()
                    val buckets = remember(rtts) {
                        listOf(
                            Triple("<20ms", rtts.count { it < 20.0 }, DashboardPalette.MatrixGreen),
                            Triple("20–40ms", rtts.count { it >= 20.0 && it < 40.0 }, DashboardPalette.Cyan),
                            Triple("40–60ms", rtts.count { it >= 40.0 && it < 60.0 }, DashboardPalette.CyanBright),
                            Triple("60–100ms", rtts.count { it >= 60.0 && it < 100.0 }, DashboardPalette.Amber),
                            Triple("100ms+", rtts.count { it >= 100.0 }, DashboardPalette.Coral),
                        )
                    }
                    val maxBucket = (buckets.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)

                    if (rtts.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for latency samples…", color = DashboardPalette.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
                                .background(DashboardPalette.PanelRecessed)
                                .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(DashboardDimens.InnerRadius))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            buckets.forEach { (label, count, barColor) ->
                                val pct = (count * 100.0 / rtts.size).roundToInt()
                                val frac = (count.toFloat() / maxBucket.toFloat()).coerceIn(0f, 1f)
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                ) {
                                    Text(
                                        text = "$pct%",
                                        color = if (count > 0) barColor else DashboardPalette.TextDim,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.68f)
                                                .fillMaxHeight(frac.coerceAtLeast(0.03f))
                                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(barColor, barColor.copy(alpha = 0.25f))
                                                    )
                                                )
                                                .border(
                                                    1.dp,
                                                    barColor.copy(alpha = 0.55f),
                                                    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                                                )
                                        )
                                    }
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        text = label,
                                        color = DashboardPalette.TextSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "$count×",
                                        color = DashboardPalette.TextDim,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right: Head-to-Head Server Comparison
            CyberPanel(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    CyberSectionHeader(
                        title = "Server Comparison",
                        accent = DashboardPalette.Cyan,
                        trailing = {
                            Text(
                                text = "AVG / P95 / SCORE",
                                color = DashboardPalette.TextDim,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                    Spacer(Modifier.height(10.dp))

                    val maxAvg = remember(stats) {
                        (stats.mapNotNull { it.avgMs }.maxOrNull() ?: 80.0).coerceAtLeast(30.0)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(stats, key = { it.target.id }) { s ->
                            val isSel = s.target.id == focus?.target?.id
                            val c = s.grade.dashboardColor()
                            val frac = ((s.avgMs ?: 0.0) / maxAvg).toFloat().coerceIn(0f, 1f)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(DashboardDimens.CardRadius))
                                    .background(if (isSel) DashboardPalette.CardSelected else DashboardPalette.CardBase)
                                    .border(
                                        1.dp,
                                        if (isSel) DashboardPalette.Cyan.copy(alpha = 0.6f) else DashboardPalette.BorderSubtle,
                                        RoundedCornerShape(DashboardDimens.CardRadius),
                                    )
                                    .clickable { onSelect(s) }
                                    .padding(horizontal = 11.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = s.target.name,
                                        color = DashboardPalette.TextPrimary,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${fmtDashboardMs(s.avgMs)}  ·  P95 ${fmtDashboardMs(s.p95Ms)}  ·  ${s.stabilityScore}",
                                        color = c,
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.5.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DashboardPalette.PanelRecessed),
                                ) {
                                    if (s.avgMs != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(frac.coerceAtLeast(0.06f))
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), c)))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsStatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    CyberPanel(modifier = modifier.fillMaxHeight(), cornerRadius = DashboardDimens.InnerRadius, elevation = 6.dp) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = DashboardPalette.TextMuted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                text = value,
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

// =============================================================================
// 4. NETWORK TOPOLOGY — Interactive Cyber Map + Instant Host:Port Tester
// =============================================================================

@Composable
internal fun TopologyScreen(
    stats: List<ServerStats>,
    selected: ServerStats?,
    isRunning: Boolean,
    pulse: Float,
    flow: Float,
    onSelect: (ServerStats) -> Unit,
    onOpenLive: (ServerStats) -> Unit,
    onLogEvent: (String, String, EventSeverity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = selected ?: stats.firstOrNull()
    val displayedNodes = remember(stats) { stats.take(8) }

    var customHost by remember(focus?.target?.id) { mutableStateOf(focus?.target?.host ?: "1.1.1.1") }
    var customPort by remember(focus?.target?.id) { mutableStateOf((focus?.target?.port ?: 443).toString()) }
    var isProbingPort by remember { mutableStateOf(false) }
    var portProbeResult by remember { mutableStateOf<Pair<String, Double?>?>(null) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left: Interactive Cyber Topology Map
        CyberPanel(
            modifier = Modifier.weight(1.55f).fillMaxHeight(),
            accentGlow = DashboardPalette.Cyan.copy(alpha = 0.55f),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                CyberSectionHeader(
                    title = "Live Network Topology",
                    accent = DashboardPalette.Cyan,
                    trailing = { CyberStatusPill(live = isRunning, pulse = pulse, labelLive = "${displayedNodes.size} LINKS") },
                )
                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(DashboardDimens.InnerRadius))
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF0A192A), Color(0xFF050C15))
                            )
                        )
                        .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(DashboardDimens.InnerRadius)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (w <= 0f || h <= 0f) return@Canvas

                        // Faint tactical grid
                        val gridColor = Color(0xFF173149)
                        for (i in 1..6) {
                            drawLine(gridColor.copy(alpha = 0.16f), Offset(0f, h * i / 7f), Offset(w, h * i / 7f), 1f)
                            drawLine(gridColor.copy(alpha = 0.12f), Offset(w * i / 7f, 0f), Offset(w * i / 7f, h), 1f)
                        }

                        val youPos = Offset(w * 0.12f, h * 0.50f)
                        val corePos = Offset(w * 0.36f, h * 0.50f)

                        // YOU -> CORE backbone link
                        drawLine(
                            brush = Brush.horizontalGradient(listOf(DashboardPalette.Cyan, DashboardPalette.MatrixGreen)),
                            start = youPos,
                            end = corePos,
                            strokeWidth = 3f,
                        )
                        if (isRunning) {
                            val px = youPos.x + (corePos.x - youPos.x) * flow
                            drawCircle(DashboardPalette.CyanBright.copy(alpha = 0.35f), 7f, Offset(px, youPos.y))
                            drawCircle(Color.White, 2.5f, Offset(px, youPos.y))
                        }

                        // CORE -> Regional Gaming PoPs
                        val count = displayedNodes.size.coerceAtLeast(1)
                        displayedNodes.forEachIndexed { idx, node ->
                            val frac = if (count == 1) 0.5f else idx.toFloat() / (count - 1).toFloat()
                            val angle = (-58.0 + frac * 116.0) * PI / 180.0
                            val radiusX = w * 0.48f
                            val radiusY = h * 0.42f
                            val nodePos = Offset(
                                x = (corePos.x + cos(angle).toFloat() * radiusX).coerceAtMost(w - 42f),
                                y = (corePos.y + sin(angle).toFloat() * radiusY).coerceIn(32f, h - 32f),
                            )

                            val nodeColor = node.grade.dashboardColor()
                            val isSel = node.target.id == focus?.target?.id

                            if (isSel) {
                                drawLine(
                                    color = nodeColor.copy(alpha = 0.25f),
                                    start = corePos,
                                    end = nodePos,
                                    strokeWidth = 9f,
                                    cap = StrokeCap.Round,
                                )
                            }
                            drawLine(
                                color = nodeColor.copy(alpha = if (isSel) 0.92f else 0.36f),
                                start = corePos,
                                end = nodePos,
                                strokeWidth = if (isSel) 2.4f else 1.4f,
                                pathEffect = if (isSel) null else PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                            )

                            // Animated packet dot traveling along link
                            if (isRunning) {
                                val speedFactor = (1.4f - ((node.avgMs ?: 60.0) / 140.0).toFloat()).coerceIn(0.45f, 1.35f)
                                val t = ((flow * speedFactor) + idx * 0.17f) % 1f
                                val pt = Offset(
                                    corePos.x + (nodePos.x - corePos.x) * t,
                                    corePos.y + (nodePos.y - corePos.y) * t,
                                )
                                drawCircle(nodeColor.copy(alpha = 0.35f), if (isSel) 6f else 4.2f, pt)
                                drawCircle(Color.White.copy(alpha = 0.9f), if (isSel) 2.2f else 1.6f, pt)
                            }

                            // Node circle
                            if (isSel && isRunning) {
                                drawCircle(
                                    color = nodeColor.copy(alpha = (1f - pulse) * 0.42f),
                                    radius = 12f + pulse * 14f,
                                    center = nodePos,
                                    style = Stroke(width = 1.6f),
                                )
                            }
                            drawCircle(nodeColor.copy(alpha = 0.22f), if (isSel) 15f else 11f, nodePos)
                            drawCircle(Color(0xFF07121E), if (isSel) 9.5f else 7.5f, nodePos)
                            drawCircle(nodeColor, if (isSel) 5.2f else 3.8f, nodePos)
                        }

                        // Draw YOU and CORE hubs on top
                        listOf(
                            Triple(youPos, DashboardPalette.Cyan, 16f),
                            Triple(corePos, DashboardPalette.MatrixGreen, 18f),
                        ).forEach { (pos, col, rad) ->
                            if (isRunning) {
                                drawCircle(
                                    color = col.copy(alpha = (1f - pulse) * 0.32f),
                                    radius = rad + pulse * 16f,
                                    center = pos,
                                    style = Stroke(width = 1.5f),
                                )
                            }
                            drawCircle(col.copy(alpha = 0.20f), rad + 5f, pos)
                            drawCircle(Color(0xFF071320), rad, pos)
                            drawCircle(col, rad, pos, style = Stroke(width = 2f))
                            drawCircle(col, 4.5f, pos)
                        }
                    }

                    // Crisp floating overlay labels
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TopologyLegendTag("YOU", DashboardPalette.Cyan)
                        TopologyLegendTag("CORE", DashboardPalette.MatrixGreen)
                        focus?.let { TopologyLegendTag("TARGET: ${it.target.name.uppercase()}", it.grade.dashboardColor()) }
                    }

                    // Bottom clickable node selector strip
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        displayedNodes.forEach { node ->
                            val isSel = node.target.id == focus?.target?.id
                            val c = node.grade.dashboardColor()
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (isSel) DashboardPalette.CardSelected else DashboardPalette.PanelRecessed.copy(alpha = 0.92f))
                                    .border(1.dp, if (isSel) c.copy(alpha = 0.7f) else DashboardPalette.BorderSubtle, RoundedCornerShape(9.dp))
                                    .clickable { onSelect(node) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                                Text(node.target.name, color = DashboardPalette.TextPrimary, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(fmtDashboardMs(node.currentMs), color = c, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Right: Route Hop Inspector + Instant Host:Port Tester
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CyberPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CyberSectionHeader(
                        title = "Route Inspector",
                        accent = focus?.grade?.dashboardColor() ?: DashboardPalette.Cyan,
                    )
                    if (focus == null) {
                        Text("Select a server to inspect route.", color = DashboardPalette.TextDim, fontSize = 11.sp)
                    } else {
                        val rtt = focus.avgMs ?: focus.currentMs
                        val hop1 = rtt?.let { (it * 0.08).coerceAtLeast(0.8) }
                        val hop2 = rtt?.let { (it * 0.28).coerceAtLeast(2.5) }
                        val hop3 = rtt?.let { (it * 0.68).coerceAtLeast(6.0) }
                        HopRow("01", "Local Gateway", "LAN Edge", hop1, DashboardPalette.Cyan)
                        HopRow("02", "ISP Core", "Backbone Transit", hop2, Color(0xFF4CD3FF))
                        HopRow("03", "Regional IXP", focus.target.region, hop3, DashboardPalette.MatrixGreen)
                        HopRow("04", focus.target.name, "${focus.target.host}:${focus.target.port}", rtt, focus.grade.dashboardColor())

                        Spacer(Modifier.weight(1f))
                        CyberActionChip(
                            label = "Inspect in Live Latency",
                            icon = Icons.Default.Speed,
                            accent = DashboardPalette.Cyan,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenLive(focus) },
                        )
                    }
                }
            }

            // Instant Port / Host Reachability Probe
            CyberPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    CyberSectionHeader(title = "Instant TCP Port Probe", accent = DashboardPalette.MatrixGreen)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1.6f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DashboardPalette.PanelRecessed)
                                .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = customHost,
                                onValueChange = { customHost = it },
                                singleLine = true,
                                cursorBrush = SolidColor(DashboardPalette.Cyan),
                                textStyle = TextStyle(color = DashboardPalette.TextPrimary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DashboardPalette.PanelRecessed)
                                .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = customPort,
                                onValueChange = { customPort = it.filter(Char::isDigit).take(5) },
                                singleLine = true,
                                cursorBrush = SolidColor(DashboardPalette.Cyan),
                                textStyle = TextStyle(color = DashboardPalette.CyanBright, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        CyberActionChip(
                            label = if (isProbingPort) "…" else "Probe",
                            icon = Icons.Default.Radar,
                            accent = DashboardPalette.MatrixGreen,
                            enabled = !isProbingPort,
                            onClick = {
                                val h = customHost.trim()
                                val p = customPort.toIntOrNull()
                                if (h.isNotEmpty() && p != null && p in 1..65535) {
                                    scope.launch {
                                        isProbingPort = true
                                        val rtt = probeTcpLatency(h, p, timeoutMs = 2000)
                                        portProbeResult = "$h:$p" to rtt
                                        isProbingPort = false
                                        onLogEvent(
                                            "PROBE",
                                            if (rtt != null) "$h:$p reachable in ${fmtDashboardMs(rtt)}" else "$h:$p timed out",
                                            if (rtt != null) EventSeverity.SUCCESS else EventSeverity.WARN,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    portProbeResult?.let { (endpoint, rtt) ->
                        val ok = rtt != null
                        val c = if (ok) DashboardPalette.MatrixGreen else DashboardPalette.Coral
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.copy(alpha = 0.10f))
                                .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = endpoint,
                                color = DashboardPalette.TextPrimary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (ok) "OPEN · ${fmtDashboardMs(rtt)}" else "TIMEOUT",
                                color = c,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyLegendTag(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DashboardPalette.PanelRecessed.copy(alpha = 0.88f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HopRow(
    hopNum: String,
    title: String,
    subtitle: String,
    rttMs: Double?,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DashboardPalette.CardBase)
            .border(1.dp, DashboardPalette.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = hopNum,
            color = color,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = DashboardPalette.TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DashboardPalette.TextDim, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = fmtDashboardMs(rttMs),
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// =============================================================================
// Shared Action Chip Helper
// =============================================================================

@Composable
internal fun CyberActionChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> DashboardPalette.PanelRecessed
                    hovered -> accent.copy(alpha = 0.22f)
                    else -> accent.copy(alpha = 0.12f)
                }
            )
            .border(
                1.dp,
                if (enabled) accent.copy(alpha = if (hovered) 0.72f else 0.42f) else DashboardPalette.BorderSubtle,
                RoundedCornerShape(10.dp),
            )
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accent else DashboardPalette.TextDim,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (enabled) DashboardPalette.TextPrimary else DashboardPalette.TextDim,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
