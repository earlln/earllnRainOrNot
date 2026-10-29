package com.earlln.rainornot.analysis

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/** Gemini 가 반환하는 구조화 JSON (responseSchema 와 1:1 대응) */
data class GeminiVerdict(
    @SerializedName("pedestrians") val pedestrians: List<PedestrianObs>? = null,
    @SerializedName("scene") val scene: SceneObs? = null,
    @SerializedName("rain_judgement") val rainJudgement: String? = null,
    @SerializedName("rain_confidence") val rainConfidence: Double? = null,
    @SerializedName("summary") val summary: String? = null,
)

data class PedestrianObs(
    /** 이미지 1(최신 전체 화면) 기준 [ymin, xmin, ymax, xmax], 0~1000 */
    @SerializedName("box_2d") val box2d: List<Double>? = null,
    /** moving | stationary | unknown */
    @SerializedName("motion") val motion: String? = null,
    /** open_overhead | closed_or_carried | none | unclear */
    @SerializedName("umbrella") val umbrella: String? = null,
    @SerializedName("confidence") val confidence: Double? = null,
)

data class SceneObs(
    @SerializedName("wet_road_reflection") val wetRoad: Boolean? = null,
    @SerializedName("rain_streaks_or_drops") val rainStreaks: Boolean? = null,
    @SerializedName("splashes_or_spray") val splashes: Boolean? = null,
    @SerializedName("lens_droplets") val lensDroplets: Boolean? = null,
    @SerializedName("sunny_hard_shadows") val sunnyShadows: Boolean? = null,
    @SerializedName("night") val night: Boolean? = null,
    @SerializedName("visibility") val visibility: String? = null,
)

object VerdictParser {
    private val gson = Gson()

    /** 코드펜스/앞뒤 설명이 섞여 와도 첫 JSON 객체만 뽑아 파싱한다. */
    fun parse(text: String): GeminiVerdict? {
        val cleaned = text.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val json = cleaned.substring(start, end + 1)
            JsonParser.parseString(json) // 문법 검증
            gson.fromJson(json, GeminiVerdict::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
