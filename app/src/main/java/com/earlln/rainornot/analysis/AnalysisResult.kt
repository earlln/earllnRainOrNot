package com.earlln.rainornot.analysis

import kotlin.math.roundToInt

/**
 * 최종 분석 결과.
 * @param total 우산 비율 계산 대상 인원 (지붕 아래·판단 불가 인원 제외)
 * @param umbrella 그중 펼친 우산을 쓴 인원
 */
data class AnalysisResult(
    val total: Int,
    val umbrella: Int,
    val isRaining: Boolean,
    val allPedestrians: Int = total,
    val movingCount: Int = 0,
    val shelteredExcluded: Int = 0,
    val motionAvailable: Boolean = false,
    val unclearCount: Int = 0,
    val frameCount: Int = 1,
    val evidence: List<String> = emptyList(),
    /** 해가 떠 있어 우산 판단 없이 '화창'으로 안내하는 경우 */
    val isSunny: Boolean = false,
    val summary: String? = null,
    val model: String = "",
) {
    val ratioPercent: Int get() = if (total == 0) 0 else (umbrella * 100.0 / total).roundToInt()

    private val basisLabel = "보행자"

    fun toDisplayText(): String = when {
        isSunny -> "☀️ 날씨가 화창합니다"
        total == 0 && isRaining -> "☔ 비 오는 중 (도로/환경 분석)"
        total == 0 -> "☀️ 비 없음 (도로/환경 분석)"
        umbrella == 0 -> "${if (isRaining) "☔" else "☀️"} $basisLabel ${total}명 / 우산 0명"
        umbrella == total -> "☔ $basisLabel ${total}명 모두 우산"
        else -> "${if (isRaining) "☔" else "🌂"} $basisLabel ${total}명 중 ${umbrella}명 우산 ($ratioPercent%)"
    }

    /** 카드 하단에 보여줄 상세 근거 */
    fun toDetailText(): String = buildList {
        if (isSunny) {
            add("햇빛 그림자와 마른 노면이 보여 우산 판단을 생략했습니다")
            add("프레임 ${frameCount}장 분석${if (model.isNotBlank()) " · $model" else ""}")
            return@buildList
        }
        val people = StringBuilder("전체 보행자 ${allPedestrians}명")
        if (shelteredExcluded > 0) people.append(" · 지붕 아래 ${shelteredExcluded}명 제외")
        if (unclearCount > 0) people.append(" · 판단불가 ${unclearCount}명 제외")
        add(people.toString())
        add(if (motionAvailable) "움직임 확인: 이동 중 ${movingCount}명" else "정지영상이라 움직임 비교 불가")
        if (evidence.isNotEmpty()) add("근거: " + evidence.joinToString(", "))
        add("프레임 ${frameCount}장 분석${if (model.isNotBlank()) " · $model" else ""}")
    }.joinToString("\n")

    fun toSpeech(): String {
        if (isSunny) return "날씨가 화창합니다."
        return when {
            total == 0 && isRaining -> "현재 CCTV 화면에 사람이 없지만 도로나 환경을 보니 비가 오는 것으로 판단됩니다. 우산을 챙기세요."
            total == 0 -> "현재 CCTV 화면에 사람이 없으며 비가 오지 않는 것으로 보입니다."
            umbrella == 0 && !isRaining -> "$basisLabel ${total}명이 있으며 우산을 쓴 사람이 없습니다. 비가 오지 않는 것 같습니다."
            umbrella == 0 -> "$basisLabel ${total}명이 있으며 우산을 쓴 사람은 없지만 도로 상태를 보니 비가 오는 것 같습니다."
            umbrella == total -> "$basisLabel ${total}명 모두 우산을 쓰고 있습니다. 비가 오고 있습니다. 우산을 챙기세요."
            isRaining -> "$basisLabel ${total}명 중 ${umbrella}명이 우산을 쓰고 있습니다. 비가 오는 것 같으니 우산을 챙기세요."
            else -> "$basisLabel ${total}명 중 ${umbrella}명이 우산을 쓰고 있습니다. 비는 오지 않는 것 같습니다."
        }
    }
}
