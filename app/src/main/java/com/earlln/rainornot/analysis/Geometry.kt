package com.earlln.rainornot.analysis

import kotlin.math.max
import kotlin.math.min

/** 화면 정규화 좌표(0~1000) 박스. Gemini box_2d 형식([ymin, xmin, ymax, xmax])과 동일하다. */
data class Box(val ymin: Int, val xmin: Int, val ymax: Int, val xmax: Int) {
    val width: Int get() = (xmax - xmin).coerceAtLeast(0)
    val height: Int get() = (ymax - ymin).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val centerX: Int get() = (xmin + xmax) / 2
    val centerY: Int get() = (ymin + ymax) / 2

    fun intersection(o: Box): Long {
        val w = min(xmax, o.xmax) - max(xmin, o.xmin)
        val h = min(ymax, o.ymax) - max(ymin, o.ymin)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }

    fun iou(o: Box): Double {
        val inter = intersection(o)
        val union = area + o.area - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    fun union(o: Box) = Box(min(ymin, o.ymin), min(xmin, o.xmin), max(ymax, o.ymax), max(xmax, o.xmax))

    fun contains(x: Int, y: Int) = x in xmin..xmax && y in ymin..ymax

    /** 중심을 유지한 채 최소 크기를 보장하고 화면 안으로 밀어 넣는다. */
    fun ensureMinSize(minW: Int, minH: Int): Box {
        val w = max(width, minW).coerceAtMost(SCALE)
        val h = max(height, minH).coerceAtMost(SCALE)
        var x0 = centerX - w / 2
        var y0 = centerY - h / 2
        x0 = x0.coerceIn(0, SCALE - w)
        y0 = y0.coerceIn(0, SCALE - h)
        return Box(y0, x0, y0 + h, x0 + w)
    }

    fun pad(ratio: Double): Box {
        val px = (width * ratio).toInt()
        val py = (height * ratio).toInt()
        return Box(
            (ymin - py).coerceAtLeast(0), (xmin - px).coerceAtLeast(0),
            (ymax + py).coerceAtMost(SCALE), (xmax + px).coerceAtMost(SCALE),
        )
    }

    fun describe(): String =
        "가로 ${xmin / 10}~${xmax / 10}%, 세로 ${ymin / 10}~${ymax / 10}%"

    companion object {
        const val SCALE = 1000
        val FULL = Box(0, 0, SCALE, SCALE)

        fun fromList(v: List<Number>?): Box? {
            if (v == null || v.size != 4) return null
            val (a, b, c, d) = v.map { it.toDouble().toInt().coerceIn(0, SCALE) }
            val b2 = Box(min(a, c), min(b, d), max(a, c), max(b, d))
            return if (b2.width == 0 || b2.height == 0) null else b2
        }
    }
}
