package com.earlln.rainornot.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultAggregatorTest {
    private val noMotion = MotionResult.unavailable("정지영상")

    private fun ped(box: List<Double>, motion: String, umbrella: String, conf: Double = 0.9) =
        PedestrianObs(box, motion, umbrella, conf)

    private val dry = SceneObs(false, false, false, false, false, false, "good")
    private val wet = SceneObs(true, false, true, false, false, false, "fair")

    @Test
    fun countsOnlyMovingPedestriansWhenMotionAvailable() {
        val motion = MotionResult(true, listOf(Box(100, 100, 200, 150)), 0.02, null, 0, 0, "test")
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "open_overhead"),
                ped(listOf(100.0, 600.0, 200.0, 650.0), "moving", "none"),
                ped(listOf(700.0, 700.0, 800.0, 750.0), "stationary", "none"), // 버스정류장 대기자
            ),
            scene = wet, rainJudgement = "raining",
        )
        val r = ResultAggregator.aggregate(v, motion, 3, movingOnly = true)
        assertTrue(r.movingBasis)
        assertEquals(3, r.allPedestrians)
        assertEquals(2, r.total)
        assertEquals(1, r.umbrella)
        assertEquals(50, r.ratioPercent)
        assertTrue(r.isRaining)
    }

    @Test
    fun fallsBackToAllPedestriansWithoutMotion() {
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "unknown", "open_overhead"),
                ped(listOf(100.0, 600.0, 200.0, 650.0), "unknown", "open_overhead"),
            ),
            scene = wet, rainJudgement = "uncertain",
        )
        val r = ResultAggregator.aggregate(v, noMotion, 1, movingOnly = true)
        assertFalse(r.movingBasis)
        assertEquals(2, r.total)
        assertEquals(2, r.umbrella)
        assertTrue(r.isRaining)
    }

    @Test
    fun unclearAndLowConfidenceAreExcluded() {
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "none"),
                ped(listOf(300.0, 300.0, 400.0, 350.0), "moving", "unclear"),
                ped(listOf(500.0, 500.0, 600.0, 550.0), "moving", "open_overhead", conf = 0.1),
            ),
            scene = dry, rainJudgement = "not_raining",
        )
        val r = ResultAggregator.aggregate(v, noMotion, 1, movingOnly = true)
        assertEquals(2, r.allPedestrians)
        assertEquals(1, r.total)
        assertEquals(1, r.unclearCount)
        assertEquals(0, r.umbrella)
        assertFalse(r.isRaining)
    }

    @Test
    fun duplicatesFromZoomViewsAreMerged() {
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "unclear", 0.6),
                ped(listOf(105.0, 102.0, 198.0, 152.0), "moving", "open_overhead", 0.9),
            ),
            scene = wet, rainJudgement = "raining",
        )
        val r = ResultAggregator.aggregate(v, noMotion, 1, movingOnly = false)
        assertEquals(1, r.allPedestrians)
        assertEquals(1, r.umbrella)
    }

    @Test
    fun parasolOnSunnyDryDayDoesNotMeanRain() {
        val sunny = SceneObs(false, false, false, false, true, false, "good")
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "open_overhead"),
                ped(listOf(100.0, 600.0, 200.0, 650.0), "moving", "open_overhead"),
            ),
            scene = sunny, rainJudgement = "uncertain",
        )
        val r = ResultAggregator.aggregate(v, noMotion, 1, movingOnly = true)
        assertTrue(r.parasolSuspected)
        assertFalse(r.isRaining)
    }

    @Test
    fun noPeopleUsesEnvironment() {
        val rainy = SceneObs(true, true, true, false, false, false, "fair")
        val r = ResultAggregator.aggregate(GeminiVerdict(emptyList(), rainy, "raining", 0.8), noMotion, 1, true)
        assertEquals(0, r.total)
        assertTrue(r.isRaining)
        assertEquals("☔ 비 오는 중 (도로/환경 분석)", r.toDisplayText())
    }
}
