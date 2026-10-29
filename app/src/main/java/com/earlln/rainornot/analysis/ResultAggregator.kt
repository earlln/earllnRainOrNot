package com.earlln.rainornot.analysis

/**
 * Gemini 의 보행자 목록 + 앱의 움직임 분석을 합쳐 최종 결과를 만든다.
 * 비율 계산과 비 판정을 모델에게 맡기지 않고 여기서 규칙으로 수행해 결과를 일관되게 한다.
 */
object ResultAggregator {
    const val MIN_CONFIDENCE = 0.35
    private const val DEDUPE_IOU = 0.45

    data class Person(val box: Box?, val moving: Boolean, val umbrella: String, val confidence: Double)

    fun aggregate(
        verdict: GeminiVerdict,
        motion: MotionResult,
        frameCount: Int,
        movingOnly: Boolean,
        model: String = "",
    ): AnalysisResult {
        val people = dedupe(
            verdict.pedestrians.orEmpty()
                .filter { (it.confidence ?: 1.0) >= MIN_CONFIDENCE }
                .map { obs ->
                    val box = Box.fromList(obs.box2d)
                    Person(box, isMoving(obs, box, motion), obs.umbrella ?: "unclear", obs.confidence ?: 1.0)
                }
        )

        val motionAvailable = motion.reliable
        val moving = people.filter { it.moving }
        // 움직임 판별이 가능하고 실제로 움직이는 사람이 있을 때만 '움직이는 사람' 기준으로 집계
        val movingBasis = movingOnly && motionAvailable && moving.isNotEmpty()
        val population = if (movingBasis) moving else people
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
        // 맑은 날 그림자 + 마른 노면에서의 우산은 양산일 가능성
        val parasolSuspected = open > 0 && sunny && scene?.wetRoad != true && scene?.rainStreaks != true
        if (parasolSuspected) umbrellaScore = minOf(umbrellaScore, 1)

        val modelScore = when (verdict.rainJudgement) {
            "raining" -> 2
            "not_raining" -> -2
            else -> 0
        }
        val isRaining = envScore + umbrellaScore + modelScore >= 2

        return AnalysisResult(
            total = judged.size,
            umbrella = open,
            isRaining = isRaining,
            allPedestrians = people.size,
            movingCount = moving.size,
            movingBasis = movingBasis,
            motionAvailable = motionAvailable,
            unclearCount = population.size - judged.size,
            frameCount = frameCount,
            evidence = evidence,
            parasolSuspected = parasolSuspected,
            summary = verdict.summary,
            model = model,
        )
    }

    /** 모델의 판단과 프레임 차분 결과를 함께 사용한다. 작은 보행자는 차분이 더 정확한 경우가 많다. */
    internal fun isMoving(obs: PedestrianObs, box: Box?, motion: MotionResult): Boolean {
        if (!motion.reliable) return obs.motion == "moving"
        val overlap = if (box != null) motion.overlapRatio(box) else 0.0
        return when (obs.motion) {
            "moving" -> true
            "stationary" -> overlap >= 0.5
            else -> overlap >= 0.2
        }
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
