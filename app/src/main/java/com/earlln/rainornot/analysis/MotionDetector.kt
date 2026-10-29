package com.earlln.rainornot.analysis

import kotlin.math.abs

/** 축소된 흑백(휘도 0~255) 프레임 */
class LumaFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }
    val mean: Double by lazy { pixels.average() }
}

/**
 * 여러 프레임 사이의 움직임 결과.
 * @param reliable false 이면 움직임 정보를 쓸 수 없음 (프레임 1장, 동일 프레임 반복, 카메라 흔들림/장면 전환)
 */
class MotionResult(
    val reliable: Boolean,
    val boxes: List<Box>,
    val changedFraction: Double,
    private val mask: BooleanArray?,
    private val maskW: Int,
    private val maskH: Int,
    val reason: String,
) {
    /** 박스 영역 중 움직임 픽셀 비율 (0~1) */
    fun overlapRatio(box: Box): Double {
        val m = mask ?: return 0.0
        val x0 = (box.xmin * maskW / Box.SCALE).coerceIn(0, maskW - 1)
        val x1 = (box.xmax * maskW / Box.SCALE).coerceIn(x0, maskW - 1)
        val y0 = (box.ymin * maskH / Box.SCALE).coerceIn(0, maskH - 1)
        val y1 = (box.ymax * maskH / Box.SCALE).coerceIn(y0, maskH - 1)
        var hit = 0
        var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            total++
            if (m[y * maskW + x]) hit++
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    companion object {
        fun unavailable(reason: String) = MotionResult(false, emptyList(), 0.0, null, 0, 0, reason)
    }
}

/**
 * 프레임 차분 기반 움직임 검출.
 * - 프레임 평균 밝기 차이를 보정해 카메라 자동노출 변화를 상쇄
 * - 고립 픽셀 제거 → 팽창 → 연결요소로 움직임 영역 박스를 구한다
 */
object MotionDetector {
    private const val DIFF_THRESHOLD = 22
    private const val IDENTICAL_MEAN_DIFF = 0.6
    private const val MAX_CHANGED_FRACTION = 0.35
    private const val MAX_BOX_AREA_FRACTION = 0.25

    fun detect(frames: List<LumaFrame>): MotionResult {
        if (frames.size < 2) return MotionResult.unavailable("프레임 1장")
        val w = frames[0].width
        val h = frames[0].height
        if (frames.any { it.width != w || it.height != h }) return MotionResult.unavailable("프레임 크기 불일치")

        val n = w * h
        val raw = BooleanArray(n)
        var distinctPairs = 0
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            val offset = b.mean - a.mean
            var sumAbs = 0.0
            for (p in 0 until n) sumAbs += abs(b.pixels[p] - a.pixels[p] - offset)
            if (sumAbs / n < IDENTICAL_MEAN_DIFF) continue // 같은 이미지(정지영상 미갱신)
            distinctPairs++
            for (p in 0 until n) {
                if (abs(b.pixels[p] - a.pixels[p] - offset) > DIFF_THRESHOLD) raw[p] = true
            }
        }
        if (distinctPairs == 0) return MotionResult.unavailable("모든 프레임이 동일 (정지영상 미갱신)")

        val changed = raw.count { it }.toDouble() / n
        if (changed > MAX_CHANGED_FRACTION) {
            return MotionResult(false, emptyList(), changed, null, 0, 0, "화면 전체 변화 (카메라 이동/장면 전환)")
        }

        val cleaned = removeIsolated(raw, w, h)
        val dilated = dilate(dilate(cleaned, w, h), w, h)
        val boxes = components(dilated, w, h)
            .filter { it.area <= MAX_BOX_AREA_FRACTION * Box.SCALE * Box.SCALE }
        return MotionResult(true, boxes, changed, dilated, w, h, "프레임 ${frames.size}장 비교")
    }

    private fun removeIsolated(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            var nb = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && m[yy * w + xx]) nb++
            }
            out[y * w + x] = nb >= 2
        }
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) out[yy * w + xx] = true
            }
        }
        return out
    }

    private fun components(m: BooleanArray, w: Int, h: Int, minPixels: Int = 6): List<Box> {
        val seen = BooleanArray(m.size)
        val boxes = ArrayList<Box>()
        val stack = IntArray(m.size)
        var sp = 0
        fun visit(p: Int) {
            if (m[p] && !seen[p]) {
                seen[p] = true
                stack[sp++] = p
            }
        }
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            visit(start)
            var count = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (sp > 0) {
                val p = stack[--sp]
                count++
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) visit(p - 1)
                if (x < w - 1) visit(p + 1)
                if (y > 0) visit(p - w)
                if (y < h - 1) visit(p + w)
            }
            if (count < minPixels) continue
            boxes += Box(
                minY * Box.SCALE / h, minX * Box.SCALE / w,
                (maxY + 1) * Box.SCALE / h, (maxX + 1) * Box.SCALE / w,
            )
        }
        return boxes
    }
}
