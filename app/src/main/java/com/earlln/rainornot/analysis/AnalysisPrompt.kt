package com.earlln.rainornot.analysis

/**
 * Gemini 프롬프트와 응답 스키마.
 * 숫자만 바로 묻지 않고 보행자를 한 명씩 나열하게 한 뒤 앱에서 직접 센다
 * (LLM 은 "몇 명?"보다 "한 명씩 나열"할 때 개수 정확도가 훨씬 높다).
 */
object AnalysisPrompt {

    /** 이미지 구성 설명. images[0] = 최신 전체 프레임 */
    data class ImageInfo(val kind: Kind, val region: Box? = null) {
        enum class Kind { LATEST_FULL, EARLIER_FULL, ZOOM }
    }

    fun build(images: List<ImageInfo>, motionNote: String): String {
        val lines = images.mapIndexed { i, info ->
            val n = i + 1
            when (info.kind) {
                ImageInfo.Kind.LATEST_FULL -> "- 이미지 $n: 가장 최근 전체 화면 (좌표 기준 이미지)"
                ImageInfo.Kind.EARLIER_FULL -> "- 이미지 $n: 약 1~3초 전 전체 화면 (움직임 비교용)"
                ImageInfo.Kind.ZOOM -> "- 이미지 $n: 이미지 1의 확대본 [${info.region!!.describe()}] — box_2d 는 반드시 이미지 1 전체 기준으로 환산"
            }
        }.joinToString("\n")

        return """
당신은 교통 CCTV 영상에서 보행자의 우산 사용 여부로 비가 오는지 판단하는 분석가입니다.

[입력 이미지]
$lines
[앱의 움직임 분석] $motionNote

[작업 순서]
1. 확대본까지 모두 꼼꼼히 살펴 화면의 모든 보행자(걷는 사람, 신호를 기다리며 서 있는 사람, 자전거·킥보드 탄 사람)를 찾으세요.
   - 차량·버스 안의 사람, 광고판/포스터 속 인물, 마네킹, 표지판은 제외합니다.
   - 확대본과 전체 화면에 같은 사람이 보이면 한 명으로만 기록합니다.
2. 각 보행자마다 기록:
   - box_2d: 이미지 1 기준 [ymin, xmin, ymax, xmax] (0~1000 정규화)
   - motion: 이전 프레임과 비교해 위치가 바뀌었거나 걷는 자세면 "moving", 제자리면 "stationary", 비교 불가면 "unknown"
   - sheltered: 버스정류장 지붕·건물 처마·차양·고가 아래, 실내, 차 안처럼 비를 맞지 않는 위치면 true,
     하늘이 트인 인도·횡단보도(신호 대기 포함)면 false
   - umbrella: 펼친 우산을 머리 위에 쓰고 있으면 "open_overhead", 접은 우산을 들고만 있으면 "closed_or_carried",
     우산이 없으면 "none", 너무 작거나 가려져 판단할 수 없으면 "unclear"
     (CCTV 는 위에서 내려다보므로 펼친 우산은 사람 머리 위의 둥글거나 다각형인 단색 덩어리로 보입니다.
      어두운 머리카락·모자·후드·큰 가방과 혼동하지 마세요. 확신이 없으면 "unclear")
   - confidence: 이 사람이 실제 보행자라는 확신 0~1
3. 환경 단서(scene)를 따로 판단하세요: 노면 젖음/반사, 빗줄기·빗방울, 차량 물보라, 렌즈 물방울, 야간 여부, 시야.
   sunny_hard_shadows 는 햇빛이 직접 비쳐 사람·차·건물의 그림자가 또렷하게 생길 때만 true 입니다
   (흐린 날, 밤, 가로등 그림자는 false). 이때 보이는 우산은 양산일 수 있습니다.
4. 보행자와 환경을 종합해 rain_judgement(raining/not_raining/uncertain)와 rain_confidence(0~1)를 정하고,
   summary 에 한국어 한 문장으로 근거를 쓰세요.

보행자가 없으면 pedestrians 는 빈 배열로 두세요. JSON 외의 텍스트는 출력하지 마세요.
""".trimIndent()
    }

    /** Gemini responseSchema (OpenAPI 3.0 부분집합) */
    val responseSchema: Map<String, Any> = mapOf(
        "type" to "OBJECT",
        "properties" to mapOf(
            "pedestrians" to mapOf(
                "type" to "ARRAY",
                "items" to mapOf(
                    "type" to "OBJECT",
                    "properties" to mapOf(
                        "box_2d" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "INTEGER")),
                        "motion" to mapOf("type" to "STRING", "enum" to listOf("moving", "stationary", "unknown")),
                        "umbrella" to mapOf(
                            "type" to "STRING",
                            "enum" to listOf("open_overhead", "closed_or_carried", "none", "unclear"),
                        ),
                        "sheltered" to mapOf("type" to "BOOLEAN"),
                        "confidence" to mapOf("type" to "NUMBER"),
                    ),
                    "required" to listOf("box_2d", "motion", "umbrella", "sheltered", "confidence"),
                    "propertyOrdering" to listOf("box_2d", "motion", "umbrella", "sheltered", "confidence"),
                ),
            ),
            "scene" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "wet_road_reflection" to mapOf("type" to "BOOLEAN"),
                    "rain_streaks_or_drops" to mapOf("type" to "BOOLEAN"),
                    "splashes_or_spray" to mapOf("type" to "BOOLEAN"),
                    "lens_droplets" to mapOf("type" to "BOOLEAN"),
                    "sunny_hard_shadows" to mapOf("type" to "BOOLEAN"),
                    "night" to mapOf("type" to "BOOLEAN"),
                    "visibility" to mapOf("type" to "STRING", "enum" to listOf("good", "fair", "poor")),
                ),
                "required" to listOf(
                    "wet_road_reflection", "rain_streaks_or_drops", "splashes_or_spray",
                    "lens_droplets", "sunny_hard_shadows", "night", "visibility",
                ),
            ),
            "rain_judgement" to mapOf("type" to "STRING", "enum" to listOf("raining", "not_raining", "uncertain")),
            "rain_confidence" to mapOf("type" to "NUMBER"),
            "summary" to mapOf("type" to "STRING"),
        ),
        "required" to listOf("pedestrians", "scene", "rain_judgement", "rain_confidence", "summary"),
        "propertyOrdering" to listOf("pedestrians", "scene", "rain_judgement", "rain_confidence", "summary"),
    )
}
