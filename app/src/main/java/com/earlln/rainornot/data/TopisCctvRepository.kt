package com.earlln.rainornot.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 서울시 TOPIS 시내 CCTV (실시간 HLS) */
class TopisCctvRepository {
    private val gson = Gson()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Referer", "https://topis.seoul.go.kr/map/openCctvMap.do")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("User-Agent", Http.USER_AGENT)
                    .build()
            )
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var listCache: List<TopisCctvItem>? = null
    @Volatile private var cacheTs = 0L
    private val detailUrlCache = ConcurrentHashMap<String, String>()
    private val semaphore = Semaphore(4)

    suspend fun getNearby(lat: Double, lon: Double, radiusKm: Double = 50.0): List<CctvData> =
        withContext(Dispatchers.IO) {
            val all = getCachedList()
            val nearby = all
                .map { it to haversineKm(lat, lon, it.lat, it.lng) }
                .filter { it.second <= radiusKm }
                .sortedBy { it.second }
                .take(MAX_DETAIL)
                .map { it.first }
            Log.d(TAG, "반경${radiusKm}km 내 TOPIS CCTV: ${nearby.size}개 → 상세 조회")

            coroutineScope {
                nearby.map { item ->
                    async {
                        semaphore.withPermit {
                            try {
                                val url = getDetailUrl(item.camId ?: return@withPermit null)
                                if (url.isNullOrBlank()) return@withPermit null
                                CctvData(
                                    roadSectionId = item.camId,
                                    coordX = item.lng.toString(),
                                    coordY = item.lat.toString(),
                                    fileCreateTime = "",
                                    cctvType = "1",
                                    format = "HLS",
                                    name = item.camName?.trim(),
                                    streamUrl = url,
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "상세조회 실패 camId=${item.camId}: ${e.message}")
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    private fun getCachedList(): List<TopisCctvItem> {
        val cached = listCache
        if (cached != null && System.currentTimeMillis() - cacheTs < CACHE_TTL_MS) return cached
        val fresh = fetchAllCctvs()
        if (fresh.isNotEmpty()) {
            listCache = fresh
            cacheTs = System.currentTimeMillis()
            Log.d(TAG, "TOPIS 목록 갱신: ${fresh.size}개")
        }
        return fresh.ifEmpty { cached.orEmpty() }
    }

    private fun fetchAllCctvs(): List<TopisCctvItem> = try {
        val req = Request.Builder().url("https://topis.seoul.go.kr/map/cctv/selectCctvList.do").get().build()
        http.newCall(req).execute().use { resp ->
            gson.fromJson(resp.body?.string().orEmpty(), TopisListResponse::class.java)?.rows.orEmpty()
        }
    } catch (e: Exception) {
        Log.w(TAG, "목록 조회 실패: ${e.message}")
        emptyList()
    }

    private fun getDetailUrl(camId: String): String? {
        detailUrlCache[camId]?.let { return it }
        val detail = fetchDetail(camId) ?: return null
        val url = detail.hlsUrl?.takeIf { it.isNotBlank() } ?: detail.backupUrl?.takeIf { it.isNotBlank() }
        if (url != null) detailUrlCache[camId] = url
        return url
    }

    private fun fetchDetail(camId: String): TopisCctvDetail? = try {
        val body = FormBody.Builder()
            .add("camId", camId)
            .add("cctvSourceCd", "HP")
            .build()
        val req = Request.Builder().url("https://topis.seoul.go.kr/map/selectCctvInfo.do").post(body).build()
        http.newCall(req).execute().use { resp ->
            gson.fromJson(resp.body?.string().orEmpty(), TopisInfoResponse::class.java)?.rows?.firstOrNull()
        }
    } catch (e: Exception) {
        Log.w(TAG, "상세 조회 실패 camId=$camId: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "TopisRepo"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_DETAIL = 10
    }
}
