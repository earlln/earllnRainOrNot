package com.earlln.rainornot.analysis

/**
 * Gemini 의 보행자 목록 + 앱의 움직임 분석을 합쳐 최종 결과를 만든다.
 * 비율 계산과 비 판정을 모델에게 맡기지 않고 여기서 규칙으로 수행해 결과를 일관되게 한다.
 */
object ResultAggregator {
    const val MIN_CONFIDENCE = 0.35
    private const val DEDUPE_IOU = 0.45
    /** 움직임이 전혀 없고 이 확신도 미만이면 광고판·마네킹 같은 정지 형상으로 보고 제외 */
    private const val STATIC_FIGURE_MAX_CONFIDENCE = 0.6

    data class Person(
        val box: Box?,
        val moving: Boolean,
        val sheltered: Boolean,
        val umbrella: String,
        val confidence: Double,
    )

    /**
     * @param excludeSheltered true 면 지붕·처마·정류장 아래나 실내/차 안 사람을 비율에서 제외한다.
     *   걷는 사람뿐 아니라 신호를 기다리며 서 있는 사람도 비를 맞는 위치라면 집계한다.
     */
    fun aggregate(
        verdict: GeminiVerdict,
        motion: MotionResult,
        frameCount: Int,
        excludeSheltered: Boolean,
        model: String = "",
    ): AnalysisResult {
        val people = dedupe(
            verdict.pedestrians.orEmpty()
                .filter { (it.confidence ?: 1.0) >= MIN_CONFIDENCE }
                .map { obs ->
                    val box = Box.fromList(obs.box2d)
                    Person(
                        box, isMoving(obs, box, motion), obs.sheltered == true,
                        obs.umbrella ?: "unclear", obs.confidence ?: 1.0,
                    )
                }
        ).filterNot { isStaticFigure(it, motion) }

        val sheltered = people.count { it.sheltered }
        val population = if (excludeSheltered) people.filterNot { it.sheltered } else people
        val judged = population.filter { it.umbrella != "unclear" }
        val open = judged.count { it.umbrella == "open_overhead" }

        val scene = verdict.scene
        val evidence = ArrayList<String>()
        var envScore = 0
        if (scene?.rainStreaks == true) { envScore += 2; evidence += "빗줄기" }
        if (scene?.lensDroplets == true) { envScore += 2; evidence += "렌즈 물방울" }
        if (scene?.wetRoad == true) { envScore += 1; evidence += "젖은 노면" }
        if (scene?.splashes == true) { envScore += 1; evidence += "물보라" }
        val sunny = scene?.sunnyShadows == true
        if (sunny) envScore -= 2

        var umbrellaScore = 0
        if (judged.isNotEmpty()) {
            val ratio = open.toDouble() / judged.size
            umbrellaScore = when {
                judged.size >= 2 && ratio >= 0.5 -> 3
                judged.size >= 2 && ratio >= 0.25 -> 2
                open >= 1 -> 1
                judged.size >= 3 -> -1 // 여러 명이 있는데 아무도 우산이 없음
                else -> 0
            }
            if (open > 0) evidence += "우산 ${open}/${judged.size}명"
        }
        // 해가 떠 있으면(진한 햇빛 그림자 + 낮 + 비 단서 없음) 우산 판단 없이 '화창'으로 안내한다.
        // 이때 보이는 우산은 양산으로 본다.
        val isSunny = sunny && scene?.night != true && scene?.wetRoad != true &&
            scene?.rainStreaks != true && scene?.lensDroplets != true && scene?.splashes != true

        val modelScore = when (verdict.rainJudgement) {
            "raining" -> 2
            "not_raining" -> -2
            else -> 0
        }
        val isRaining = !isSunny && envScore + umbrellaScore + modelScore >= 2

        return AnalysisResult(
            total = judged.size,
            umbrella = open,
            isRaining = isRaining,
            allPedestrians = people.size,
            movingCount = people.count { it.moving },
            shelteredExcluded = if (excludeSheltered) sheltered else 0,
            motionAvailable = motion.reliable,
            unclearCount = population.size - judged.size,
            frameCount = frameCount,
            evidence = evidence,
            isSunny = isSunny,
            summary = verdict.summary,
            model = model,
        )
    }

    /** 이동 여부는 참고 정보(상세 표시, 정지 형상 판별)로만 쓴다. */
    internal fun isMoving(obs: PedestrianObs, box: Box?, motion: MotionResult): Boolean {
        if (obs.motion == "moving") return true
        if (!motion.reliable || box == null) return false
        return motion.overlapRatio(box) >= 0.2
    }

    /**
     * 여러 프레임을 비교했는데 전혀 움직임이 없고 확신도도 낮으면 사람이 아닐 가능성이 높다.
     * (신호 대기자는 몸을 조금이라도 움직이므로 보통 차분에 걸린다)
     */
    internal fun isStaticFigure(p: Person, motion: MotionResult): Boolean {
        if (!motion.reliable || p.moving || p.box == null) return false
        return motion.overlapRatio(p.box) == 0.0 && p.confidence < STATIC_FIGURE_MAX_CONFIDENCE
    }

    /** 확대본과 전체 화면에서 중복 기록된 동일 인물을 하나로 합친다. */
    internal fun dedupe(people: List<Person>): List<Person> {
        val result = ArrayList<Person>()
        for (p in people.sortedByDescending { it.confidence }) {
            val dupIdx = if (p.box == null) -1 else result.indexOfFirst { q ->
                q.box != null && (q.box.iou(p.box) >= DEDUPE_IOU ||
                    (q.box.contains(p.box.centerX, p.box.centerY) && p.box.contains(q.box.centerX, q.box.centerY)))
            }
            if (dupIdx < 0) {
                result += p
            } else {
                val q = result[dupIdx]
                // 우산 판정은 더 구체적인 쪽을 채택 (unclear < 그 외)
                val umbrella = if (q.umbrella == "unclear") p.umbrella else q.umbrella
                result[dupIdx] = q.copy(moving = q.moving || p.moving, umbrella = umbrella)
            }
        }
        return result
    }
}
