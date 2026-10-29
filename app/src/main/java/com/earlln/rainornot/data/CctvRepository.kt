package com.earlln.rainornot.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** 국가교통정보센터(ITS) 국도 CCTV */
class CctvRepository {
    private val gson = Gson()

    suspend fun getNearby(lat: Double, lon: Double, apiKey: String, radiusKm: Double): List<CctvData> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.w(TAG, "ITS_API_KEY 미설정 → ITS 조회 건너뜀")
                return@withContext emptyList()
            }
            // 위도 1도 ≈ 111km. 경도는 위도에 따라 줄어들지만 넉넉하게 같은 값을 쓰고 이후 거리로 거른다.
            val delta = radiusKm.coerceAtMost(500.0) / 111.0
            val minX = lon - delta
            val maxX = lon + delta
            val minY = lat - delta
            val maxY = lat + delta
            Log.d(TAG, "검색: lat=$lat lon=$lon radius=${radiusKm}km")

            val all = coroutineScope {
                listOf("1", "2", "3").map { type ->
                    async { fetchType(apiKey, type, minX, maxX, minY, maxY) }
                }.awaitAll().flatten()
            }
            val seen = HashSet<String>()
            val unique = all.filter { item ->
                val url = item.bestUrl
                !url.isNullOrBlank() && seen.add(url)
            }
            unique.sortedWith(
                compareByDescending<CctvData> { it.hasCrosswalk() }.thenBy { it.distanceTo(lat, lon) }
            ).also { list ->
                Log.d(TAG, "결과: 총 ${list.size}개 (교차로포함: ${list.count { it.hasCrosswalk() }}개)")
            }
        }

    private fun fetchType(
        apiKey: String, cctvType: String,
        minX: Double, maxX: Double, minY: Double, maxY: Double,
    ): List<CctvData> = try {
        val url = "$BASE_URL/cctvInfo".toHttpUrl().newBuilder()
            .addQueryParameter("apiKey", apiKey)
            .addQueryParameter("type", "its")
            .addQueryParameter("cctvType", cctvType)
            .addQueryParameter("minX", minX.toString())
            .addQueryParameter("maxX", maxX.toString())
            .addQueryParameter("minY", minY.toString())
            .addQueryParameter("maxY", maxY.toString())
            .addQueryParameter("getType", "json")
            .build()
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "타입$cctvType 조회 실패: HTTP ${resp.code}")
                emptyList()
            } else {
                val list = gson.fromJson(body, CctvApiResponse::class.java)?.response?.data.orEmpty()
                Log.d(TAG, "타입$cctvType → ${list.size}개 (교차로: ${list.count { it.hasCrosswalk() }}개)")
                list
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "타입$cctvType 조회 실패: ${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "CctvRepo"
        private const val BASE_URL = "https://openapi.its.go.kr:9443"
    }
}
