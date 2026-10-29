package com.earlln.rainornot.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    private val w = 100
    private val h = 80

    private fun frame(bg: Int = 100, blob: Triple<Int, Int, Int>? = null): LumaFrame {
        val px = IntArray(w * h) { bg }
        blob?.let { (x0, y0, size) ->
            for (y in y0 until y0 + size * 2) for (x in x0 until x0 + size) px[y * w + x] = 230
        }
        return LumaFrame(w, h, px)
    }

    @Test
    fun singleFrameIsUnavailable() {
        assertFalse(MotionDetector.detect(listOf(frame())).reliable)
    }

    @Test
    fun identicalFramesAreUnavailable() {
        val r = MotionDetector.detect(listOf(frame(blob = Triple(10, 10, 4)), frame(blob = Triple(10, 10, 4))))
        assertFalse(r.reliable)
    }

    @Test
    fun movingBlobIsDetected() {
        val r = MotionDetector.detect(listOf(frame(blob = Triple(10, 20, 4)), frame(blob = Triple(18, 20, 4))))
        assertTrue(r.reliable)
        assertEquals(1, r.boxes.size)
        val box = r.boxes[0]
        // 블롭이 지나간 영역(x 10~22, y 20~28)을 포함해야 한다
        assertTrue(box.xmin <= 100 && box.xmax >= 220)
        assertTrue(r.overlapRatio(Box(250, 100, 350, 220)) > 0.3)
        assertTrue(r.overlapRatio(Box(800, 800, 900, 900)) == 0.0)
    }

    @Test
    fun globalExposureChangeIsIgnored() {
        // 전체 밝기만 바뀌고(자동노출) 물체는 그대로
        val r = MotionDetector.detect(listOf(frame(bg = 100), frame(bg = 140)))
        // 평균 차이를 보정하므로 '동일 프레임'으로 취급되거나, 움직임이 없어야 한다
        assertTrue(r.boxes.isEmpty())
    }

    @Test
    fun wholeSceneChangeIsUnreliable() {
        val a = LumaFrame(w, h, IntArray(w * h) { if ((it / w + it % w) % 2 == 0) 0 else 255 })
        val b = LumaFrame(w, h, IntArray(w * h) { if ((it / w + it % w) % 2 == 0) 255 else 0 })
        assertFalse(MotionDetector.detect(listOf(a, b)).reliable)
    }
}
