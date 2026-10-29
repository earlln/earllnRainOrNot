package com.earlln.rainornot.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropPlannerTest {
    private fun motion(vararg boxes: Box) =
        MotionResult(true, boxes.toList(), 0.01, BooleanArray(1), 1, 1, "test")

    @Test
    fun noMotionUsesTiles() {
        assertEquals(CropPlanner.tiles(), CropPlanner.plan(MotionResult.unavailable("x")))
        assertEquals(CropPlanner.tiles(), CropPlanner.plan(motion()))
    }

    @Test
    fun cropsContainMotionAndRespectBounds() {
        val person = Box(600, 100, 680, 130)
        val crops = CropPlanner.plan(motion(person))
        assertEquals(1, crops.size)
        val c = crops[0]
        assertTrue(c.xmin <= person.xmin && c.xmax >= person.xmax && c.ymin <= person.ymin && c.ymax >= person.ymax)
        assertTrue(c.xmin >= 0 && c.ymin >= 0 && c.xmax <= 1000 && c.ymax <= 1000)
        assertTrue(c.width >= 260 && c.height >= 260)
    }

    @Test
    fun nearbyMotionMergesAndCountIsCapped() {
        val near = CropPlanner.plan(motion(Box(500, 500, 560, 520), Box(520, 540, 580, 560)))
        assertEquals(1, near.size)
        val many = CropPlanner.plan(
            motion(
                Box(0, 0, 50, 20), Box(0, 900, 50, 920), Box(900, 0, 950, 20),
                Box(900, 900, 950, 920), Box(450, 450, 500, 470),
            )
        )
        assertEquals(4, many.size)
    }
}
