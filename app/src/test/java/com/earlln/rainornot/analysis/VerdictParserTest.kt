package com.earlln.rainornot.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerdictParserTest {
    @Test
    fun parsesFencedJson() {
        val text = """
            ```json
            {"pedestrians":[{"box_2d":[10,20,30,40],"motion":"moving","umbrella":"open_overhead","confidence":0.8}],
             "scene":{"wet_road_reflection":true,"rain_streaks_or_drops":false,"splashes_or_spray":false,
                      "lens_droplets":false,"sunny_hard_shadows":false,"night":false,"visibility":"good"},
             "rain_judgement":"raining","rain_confidence":0.7,"summary":"우산 보임"}
            ```
        """.trimIndent()
        val v = VerdictParser.parse(text)!!
        assertEquals(1, v.pedestrians!!.size)
        assertEquals("open_overhead", v.pedestrians!![0].umbrella)
        assertEquals(Box(10, 20, 30, 40), Box.fromList(v.pedestrians!![0].box2d))
        assertEquals(true, v.scene!!.wetRoad)
        assertEquals("raining", v.rainJudgement)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(VerdictParser.parse("no json here"))
        assertNull(VerdictParser.parse("{broken"))
    }

    @Test
    fun boxNormalizesOrderAndClamps() {
        assertEquals(Box(10, 20, 30, 1000), Box.fromList(listOf(30, 1200, 10, 20)))
        assertNull(Box.fromList(listOf(1, 2, 3)))
        assertNull(Box.fromList(listOf(10, 10, 10, 20)))
    }
}
