package com.earlln.rainornot.data

import com.google.gson.annotations.SerializedName
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CCTV 한 대. ITS 응답 필드명을 그대로 사용하고, TOPIS 항목도 같은 형태로 변환해 담는다.
 * cctvType: "1" 실시간(HLS), "2" 동영상 파일(MP4), "3" 정지영상(JPEG)
 */
data class CctvData(
    @SerializedName("roadsectionid") val roadSectionId: String? = null,
    @SerializedName("coordx") val coordX: String? = null,
    @SerializedName("coordy") val coordY: String? = null,
    @SerializedName("filecreatetime") val fileCreateTime: String? = null,
    @SerializedName("cctvtype") val cctvType: String? = null,
    @SerializedName("cctvformat") val format: String? = null,
    @SerializedName("cctvname") val name: String? = null,
    @SerializedName("cctvurl") val streamUrl: String? = null,
    @SerializedName("cctvurl2") val streamUrl2: String? = null,
) {
    val latitude: Double get() = coordY?.toDoubleOrNull() ?: 0.0
    val longitude: Double get() = coordX?.toDoubleOrNull() ?: 0.0

    val displayName: String get() = name?.trim().takeUnless { it.isNullOrEmpty() } ?: "이름 없는 CCTV"

    val isStream: Boolean get() = cctvType == "1"

    val bestUrl: String?
        get() = if (cctvType == "3" && !streamUrl2.isNullOrBlank()) streamUrl2 else streamUrl

    val typeLabel: String
        get() = when (cctvType) {
            "1" -> "📡 실시간"
            "2" -> "🎬 동영상"
            else -> "🖼️ 정지영상"
        }

    fun hasCrosswalk(): Boolean {
        val n = name ?: return false
        return CROSSWALK_KEYWORDS.any { n.contains(it) }
    }

    /**
     * 자동 분석 우선순위. 보행자가 많은 교차로/횡단보도를 우선하고,
     * 움직임 판별이 가능한 영상(실시간/동영상) CCTV를 정지영상보다 우선한다.
     */
    fun analysisPriority(): Int {
        var p = 0
        if (hasCrosswalk()) p += 10
        when (cctvType) {
            "1" -> p += 4
            "2" -> p += 3
            "3" -> p += 2
        }
        return p
    }

    fun distanceTo(lat: Double, lon: Double): Double = haversineKm(lat, lon, latitude, longitude)

    fun formattedDistance(lat: Double, lon: Double): String = formatDistance(distanceTo(lat, lon))

    companion object {
        val CROSSWALK_KEYWORDS = listOf(
            "교차로", "횡단보도", "삼거리", "사거리", "오거리", "네거리",
            "사거", "삼거", "교차", "광장", "터미널", "역앞", "역 앞",
        )
    }
}

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun formatDistance(km: Double): String =
    if (km < 1.0) "${(km * 1000).toInt()}m" else String.format("%.1fkm", km)

data class CctvApiResponse(val response: CctvResponse? = null)

data class CctvResponse(
    @SerializedName("data") val data: List<CctvData>? = null,
    @SerializedName("datacount") val dataCount: Int? = null,
    @SerializedName("coordtype") val coordType: Int? = null,
)

data class TopisCctvItem(
    val camId: String? = null,
    val camName: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
)

data class TopisListResponse(val rows: List<TopisCctvItem>? = null)

data class TopisCctvDetail(
    val cctvId: String? = null,
    val cctvName: String? = null,
    val axX: Double = 0.0,
    val axY: Double = 0.0,
    val hlsUrl: String? = null,
    val backupUrl: String? = null,
    val roadNm: String? = null,
    val timerYn: String? = null,
)

data class TopisInfoResponse(val rows: List<TopisCctvDetail>? = null)
