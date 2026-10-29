package com.earlln.rainornot.analysis

/**
 * Gemini 에 함께 보낼 확대(줌) 영역을 고른다.
 * CCTV 속 보행자는 수십 픽셀에 불과해 전체 화면만으로는 우산 여부를 놓치기 쉽다.
 * - 움직임이 있으면 보행자 형태(세로로 긴) 움직임 영역 위주로 확대
 * - 움직임 정보가 없으면 화면을 2x2 로 겹치게 나눠 모두 확대
 */
object CropPlanner {
    private const val MIN_CROP = 260
    private const val MAX_CROP = 520

    fun tiles(): List<Box> = listOf(
        Box(0, 0, 560, 560),
        Box(0, 440, 560, 1000),
        Box(440, 0, 1000, 560),
        Box(440, 440, 1000, 1000),
    )

    fun plan(motion: MotionResult, maxCrops: Int = 4): List<Box> {
        if (!motion.reliable || motion.boxes.isEmpty()) return tiles()

        // 세로로 긴 영역(보행자 형태)을 앞에, 같은 조건이면 작은 영역(먼 보행자) 우선
        val ordered = motion.boxes.sortedWith(
            compareByDescending<Box> { it.height >= it.width }.thenBy { it.area }
        )
        val windows = ArrayList<Box>()
        for (b in ordered) {
            val win = b.pad(0.5).ensureMinSize(MIN_CROP, MIN_CROP)
            val mergeIdx = windows.indexOfFirst { it.intersection(win) > 0 && it.union(win).let { u -> u.width <= MAX_CROP && u.height <= MAX_CROP } }
            if (mergeIdx >= 0) {
                windows[mergeIdx] = windows[mergeIdx].union(win)
            } else if (windows.none { it.intersection(win) >= win.area * 0.7 }) {
                windows += win
            }
            if (windows.size >= maxCrops) break
        }
        return windows.ifEmpty { tiles() }
    }
}
