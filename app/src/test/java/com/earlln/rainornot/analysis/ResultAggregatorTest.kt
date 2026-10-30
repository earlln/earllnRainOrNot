package com.earlln.rainornot.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultAggregatorTest {
    private val noMotion = MotionResult.unavailable("정지영상")

    private fun ped(
        box: List<Double>, motion: String, umbrella: String,
        conf: Double = 0.9, sheltered: Boolean = false,
    ) = PedestrianObs(box, motion, umbrella, sheltered, conf)

    private val dry = SceneObs(false, false, false, false, false, false, "good")
    private val wet = SceneObs(true, false, true, false, false, false, "fair")

    /** 움직임 마스크: 전체 화면에서 주어진 박스 영역만 움직임 */
    private fun motionAt(vararg boxes: Box): MotionResult {
        val w = 100; val h = 100
        val mask = BooleanArray(w * h)
        for (b in boxes) for (y in b.ymin / 10 until b.ymax / 10) for (x in b.xmin / 10 until b.xmax / 10) mask[y * w + x] = true
        return MotionResult(true, boxes.toList(), 0.02, mask, w, h, "test")
    }

    @Test
    fun waitingPedestriansAreCountedButShelteredAreNot() {
        val motion = motionAt(Box(100, 100, 200, 150))
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "open_overhead"),
                ped(listOf(100.0, 600.0, 200.0, 650.0), "stationary", "open_overhead"), // 신호 대기 (확신도 높음)
                ped(listOf(400.0, 300.0, 500.0, 350.0), "stationary", "none"),           // 신호 대기
                ped(listOf(700.0, 700.0, 800.0, 750.0), "stationary", "none", sheltered = true), // 버스정류장 지붕 아래
            ),
            scene = wet, rainJudgement = "raining",
        )
        val r = ResultAggregator.aggregate(v, motion, 3, excludeSheltered = true)
        assertEquals(4, r.allPedestrians)
        assertEquals(1, r.shelteredExcluded)
        assertEquals(3, r.total)
        assertEquals(2, r.umbrella)
        assertEquals(1, r.movingCount)
        assertTrue(r.isRaining)
    }

    @Test
    fun shelteredIncludedWhenSettingOff() {
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "unknown", "open_overhead"),
                ped(listOf(700.0, 700.0, 800.0, 750.0), "unknown", "none", sheltered = true),
            ),
            scene = wet, rainJudgement = "uncertain",
        )
        val r = ResultAggregator.aggregate(v, noMotion, 1, excludeSheltered = false)
        assertEquals(2, r.total)
        assertEquals(0, r.shelteredExcluded)
    }

    @Test
    fun motionlessLowConfidenceFigureIsDropped() {
        // 여러 프레임에서 전혀 안 움직이고 확신도도 낮음 → 광고판/마네킹 가능성
        val motion = motionAt(Box(100, 100, 200, 150))
        val v = GeminiVerdict(
            pedestrians = listOf(
                ped(listOf(100.0, 100.0, 200.0, 150.0), "moving", "none"),
                ped(listOf(600.0, 600.0, 700.0, 650.0), "stationary", "open_overhead", conf = 0.5),
            ),
            scene = dry, rainJudgement = "not_raining",
        )
        val r = ResultAggregator.aggregate(v, motion, 3, excludeSheltered = true)
        assertEquals(1, r.allPedestrians)
        assertEquals(0, r.umbrella)
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
        val r = ResultAggregator.aggregate(v, noMotion, 1, excludeSheltered = true)
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
        val r = ResultAggregator.aggregate(v, noMotion, 1, excludeSheltered = true)
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
        val r = ResultAggregator.aggregate(v, noMotion, 1, excludeSheltered = true)
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
