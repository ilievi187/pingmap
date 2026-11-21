package io.github.ilievi187.pingmap

import io.github.ilievi187.pingmap.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PingModelsTest {
    @Test
    fun calculatesAverageJitterAndPacketLoss() {
        val target = ServerTarget("t1", "Frankfurt", "EU", "1.1.1.1", 443, GameCategory.CS2_FACEIT)
        var stats = ServerStats(target)
        stats = stats.appendSample(20.0)
        stats = stats.appendSample(24.0)
        stats = stats.appendSample(null)
        stats = stats.appendSample(22.0)

        assertEquals(20.0, stats.minMs)
        assertEquals(24.0, stats.maxMs)
        assertNotNull(stats.avgMs)
        assertTrue(stats.avgMs!! in 21.9..22.1)
        assertNotNull(stats.jitterMs)
        assertTrue(stats.jitterMs!! in 2.9..3.1)
        assertEquals(25.0, stats.lossPercent)
        assertEquals(QualityGrade.POOR, stats.grade) // 25% loss -> POOR
    }

    @Test
    fun gradesLowPingZeroLossAsExcellent() {
        val target = ServerTarget("t2", "Cloudflare", "Anycast", "1.1.1.1", 443, GameCategory.DNS_CORE)
        val stats = ServerStats(target)
            .appendSample(12.0)
            .appendSample(14.0)
            .appendSample(13.0)
        assertEquals(QualityGrade.EXCELLENT, stats.grade)
        assertEquals(0.0, stats.lossPercent)
    }

    @Test
    fun presetsCoverMultipleGamesAndDns() {
        val categories = DefaultServers.preset.map { it.category }.toSet()
        assertTrue(GameCategory.CS2_FACEIT in categories)
        assertTrue(GameCategory.VALORANT in categories)
        assertTrue(GameCategory.LOL in categories)
        assertTrue(GameCategory.FORTNITE_APEX in categories)
        assertTrue(GameCategory.DNS_CORE in categories)
    }
}
