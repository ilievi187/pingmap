package io.github.ilievi187.pingmap.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class GameCategory(val label: String) {
    ALL("All"),
    CS2_FACEIT("CS2 / FACEIT"),
    VALORANT("Valorant"),
    LOL("League of Legends"),
    FORTNITE_APEX("Fortnite / Apex"),
    DNS_CORE("DNS / Core"),
    CUSTOM("Custom"),
}

@Serializable
data class ServerTarget(
    val id: String,
    val name: String,
    val region: String,
    val host: String,
    val port: Int = 443,
    val category: GameCategory,
    val isCustom: Boolean = false,
)

enum class QualityGrade(val label: String) {
    EXCELLENT("EXCELLENT"),
    GOOD("GOOD"),
    FAIR("FAIR"),
    POOR("POOR"),
    WAITING("WAITING"),
}

data class PingSample(
    val rttMs: Double?,
    val sequence: Int,
)

data class ServerStats(
    val target: ServerTarget,
    val samples: List<PingSample> = emptyList(),
) {
    val validRtts: List<Double> get() = samples.mapNotNull { it.rttMs }
    val currentMs: Double? get() = samples.lastOrNull()?.rttMs
    val minMs: Double? get() = validRtts.minOrNull()
    val maxMs: Double? get() = validRtts.maxOrNull()
    val avgMs: Double? get() = if (validRtts.isEmpty()) null else validRtts.average()

    val jitterMs: Double?
        get() {
            val rtts = validRtts
            if (rtts.size < 2) return null
            var sum = 0.0
            for (i in 1 until rtts.size) {
                sum += abs(rtts[i] - rtts[i - 1])
            }
            return sum / (rtts.size - 1)
        }

    val lossPercent: Double
        get() {
            if (samples.isEmpty()) return 0.0
            val dropped = samples.count { it.rttMs == null }
            return dropped * 100.0 / samples.size
        }

    fun percentileMs(p: Double): Double? {
        val sorted = validRtts.sorted()
        if (sorted.isEmpty()) return null
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    val p50Ms: Double? get() = percentileMs(50.0)
    val p95Ms: Double? get() = percentileMs(95.0)
    val p99Ms: Double? get() = percentileMs(99.0)

    fun spikeCount(thresholdMs: Double? = null): Int {
        val avg = avgMs ?: return 0
        val dynamicLimit = (avg * 1.55).coerceAtLeast(avg + 15.0)
        val limit = thresholdMs?.let { minOf(it, dynamicLimit) } ?: dynamicLimit
        return validRtts.count { it > limit }
    }

    val stabilityScore: Int
        get() {
            val avg = avgMs ?: return 0
            val jitter = jitterMs ?: 0.0
            val lossPenalty = lossPercent * 4.5
            val jitterPenalty = jitter * 1.6
            val latencyPenalty = ((avg - 25.0).coerceAtLeast(0.0)) * 0.45
            return (100.0 - lossPenalty - jitterPenalty - latencyPenalty).toInt().coerceIn(0, 100)
        }

    val grade: QualityGrade
        get() {
            val avg = avgMs ?: return if (samples.isEmpty()) QualityGrade.WAITING else QualityGrade.POOR
            val loss = lossPercent
            return when {
                loss >= 5.0 || avg >= 100.0 -> QualityGrade.POOR
                loss >= 1.0 || avg >= 60.0 -> QualityGrade.FAIR
                avg <= 30.0 -> QualityGrade.EXCELLENT
                else -> QualityGrade.GOOD
            }
        }

    fun appendSample(rttMs: Double?, maxHistory: Int = 30): ServerStats {
        val nextSeq = (samples.lastOrNull()?.sequence ?: 0) + 1
        val updated = (samples + PingSample(rttMs, nextSeq)).takeLast(maxHistory)
        return copy(samples = updated)
    }
}

object DefaultServers {
    val preset: List<ServerTarget> = listOf(
        // CS2 / FACEIT (European & Global gaming PoPs via AWS/Valve regional datacenters)
        ServerTarget("cs2-fra", "Frankfurt", "EU Central · DE", "dynamodb.eu-central-1.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-ams", "Amsterdam", "EU West · NL", "dynamodb.eu-west-1.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-vie", "Vienna / Zurich", "EU Central · CH/AT", "dynamodb.eu-central-2.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-sto", "Stockholm", "EU North · SE", "dynamodb.eu-north-1.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-lon", "London", "EU West · UK", "dynamodb.eu-west-2.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-par", "Paris", "EU West · FR", "dynamodb.eu-west-3.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-mad", "Madrid", "EU South · ES", "dynamodb.eu-south-2.amazonaws.com", 443, GameCategory.CS2_FACEIT),
        ServerTarget("cs2-use", "US East (Virginia)", "NA East · US", "dynamodb.us-east-1.amazonaws.com", 443, GameCategory.CS2_FACEIT),

        // Valorant (Riot regional hubs)
        ServerTarget("val-fra", "Frankfurt 1", "EU Central · Riot", "ec2.eu-central-1.amazonaws.com", 443, GameCategory.VALORANT),
        ServerTarget("val-par", "Paris 1", "EU West · Riot", "ec2.eu-west-3.amazonaws.com", 443, GameCategory.VALORANT),
        ServerTarget("val-lon", "London", "EU West · Riot", "ec2.eu-west-2.amazonaws.com", 443, GameCategory.VALORANT),
        ServerTarget("val-sto", "Stockholm", "EU North · Riot", "ec2.eu-north-1.amazonaws.com", 443, GameCategory.VALORANT),
        ServerTarget("val-mil", "Milan / South EU", "EU South · IT", "ec2.eu-south-1.amazonaws.com", 443, GameCategory.VALORANT),
        ServerTarget("val-tok", "Tokyo", "APAC · JP", "ec2.ap-northeast-1.amazonaws.com", 443, GameCategory.VALORANT),

        // League of Legends
        ServerTarget("lol-euw", "EUW (Amsterdam)", "Europe West", "s3.eu-west-1.amazonaws.com", 443, GameCategory.LOL),
        ServerTarget("lol-eune", "EUNE (Frankfurt)", "Europe Nordic & East", "s3.eu-central-1.amazonaws.com", 443, GameCategory.LOL),
        ServerTarget("lol-na", "NA (US Central)", "North America", "s3.us-east-2.amazonaws.com", 443, GameCategory.LOL),
        ServerTarget("lol-kr", "KR (Seoul)", "Korea", "s3.ap-northeast-2.amazonaws.com", 443, GameCategory.LOL),

        // Fortnite / Apex Legends / Cloud Gaming
        ServerTarget("fn-eu", "Europe (Frankfurt)", "Fortnite / Apex EU", "s3.eu-central-1.amazonaws.com", 443, GameCategory.FORTNITE_APEX),
        ServerTarget("fn-uk", "UK / Ireland", "Fortnite / Apex EUW", "s3.eu-west-2.amazonaws.com", 443, GameCategory.FORTNITE_APEX),
        ServerTarget("fn-na-e", "NA East", "Fortnite / Apex NAE", "s3.us-east-1.amazonaws.com", 443, GameCategory.FORTNITE_APEX),
        ServerTarget("fn-na-w", "NA West", "Fortnite / Apex NAW", "s3.us-west-2.amazonaws.com", 443, GameCategory.FORTNITE_APEX),
        ServerTarget("fn-asia", "Asia (Singapore)", "Fortnite / Apex SEA", "s3.ap-southeast-1.amazonaws.com", 443, GameCategory.FORTNITE_APEX),

        // DNS & Core Internet Backbone
        ServerTarget("dns-cf", "Cloudflare DNS", "Anycast · 1.1.1.1", "1.1.1.1", 443, GameCategory.DNS_CORE),
        ServerTarget("dns-gg", "Google DNS", "Anycast · 8.8.8.8", "8.8.8.8", 443, GameCategory.DNS_CORE),
        ServerTarget("dns-q9", "Quad9 DNS", "Anycast · 9.9.9.9", "9.9.9.9", 443, GameCategory.DNS_CORE),
        ServerTarget("dns-od", "OpenDNS", "Anycast · 208.67.222.222", "208.67.222.222", 53, GameCategory.DNS_CORE),
    )
}
