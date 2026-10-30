package com.earlln.rainornot.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.earlln.rainornot.BuildConfig
import com.earlln.rainornot.analysis.AnalysisException
import com.earlln.rainornot.analysis.AnalysisResult
import com.earlln.rainornot.analysis.FrameGrabber
import com.earlln.rainornot.analysis.GeminiAnalyzer
import com.earlln.rainornot.analysis.GeminiQuotaException
import com.earlln.rainornot.analysis.MissingApiKeyException
import com.earlln.rainornot.data.AppSettings
import com.earlln.rainornot.data.CctvData
import com.earlln.rainornot.data.CctvRepository
import com.earlln.rainornot.data.FavoritesManager
import com.earlln.rainornot.data.TopisCctvRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CctvViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CctvRepository()
    private val topisRepo = TopisCctvRepository()
    private val grabber = FrameGrabber(app)
    private val settings = AppSettings(app)
    private val analyzer = GeminiAnalyzer({ settings.effectiveGeminiKey }, BuildConfig.GEMINI_MODEL)
    val favorites = FavoritesManager(app)

    private val _state = MutableLiveData<UiState>(UiState.Idle)
    val state: LiveData<UiState> = _state

    private val _analysisState = MutableLiveData<AnalysisState>(AnalysisState.Idle)
    val analysisState: LiveData<AnalysisState> = _analysisState

    private val _userLat = MutableLiveData(0.0)
    val userLat: LiveData<Double> = _userLat
    private val _userLon = MutableLiveData(0.0)
    val userLon: LiveData<Double> = _userLon

    private var fetchJob: Job? = null
    private var analysisJob: Job? = null

    private data class Cached(val result: AnalysisResult, val ts: Long)
    private val resultCache = HashMap<String, Cached>()

    fun fetchAndAnalyze(lat: Double, lon: Double, radiusKm: Int, autoAnalyze: Boolean, includeHighway: Boolean) {
        _userLat.value = lat
        _userLon.value = lon
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val radius = radiusKm.toDouble()
                val (its, topis) = coroutineScope {
                    val itsD = async {
                        if (includeHighway) runCatching { repo.getNearby(lat, lon, settings.effectiveItsKey, radius) }
                            .getOrElse { emptyList() } else emptyList()
                    }
                    val topisD = async { runCatching { topisRepo.getNearby(lat, lon, radius) }.getOrElse { emptyList() } }
                    itsD.await() to topisD.await()
                }
                Log.d(TAG, "ITS: ${its.size}개, TOPIS: ${topis.size}개")

                val favUrls = favorites.getAll().mapNotNull { it.bestUrl }.toSet()
                val list = (its + topis)
                    .filter { !it.bestUrl.isNullOrBlank() && it.distanceTo(lat, lon) <= radius }
                    .distinctBy { it.bestUrl }
                    .sortedWith(
                        compareByDescending<CctvData> { it.bestUrl in favUrls }
                            .thenByDescending { it.hasCrosswalk() }
                            .thenBy { it.distanceTo(lat, lon) }
                    )
                CctvDataHolder.cctvList = list
                CctvDataHolder.userLat = lat
                CctvDataHolder.userLon = lon
                _state.value = if (list.isEmpty()) UiState.Empty else UiState.Success(list)

                if (autoAnalyze && list.isNotEmpty()) {
                    analysisJob?.cancel()
                    analysisJob = launch { autoAnalyzeWithRetry(buildCandidateList(list, lat, lon), lat, lon) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                _state.value = UiState.Error(
                    when {
                        msg.contains("Unable to resolve host") -> "인터넷 연결을 확인해주세요."
                        msg.contains("timeout", true) -> "서버 응답 시간 초과. 잠시 후 다시 시도해주세요."
                        else -> "오류: ${msg.ifBlank { "알 수 없는 오류" }}"
                    }
                )
            }
        }
    }

    /** 즐겨찾기 → (교차로·영상 CCTV 우선) 가까운 순으로 자동 분석 후보를 만든다. */
    private fun buildCandidateList(list: List<CctvData>, lat: Double, lon: Double): List<CctvData> {
        val favUrls = favorites.getAll().mapNotNull { it.bestUrl }
        val favs = favUrls.mapNotNull { url -> list.firstOrNull { it.bestUrl == url } }
        val nearby = list.sortedBy { it.distanceTo(lat, lon) }.take(NEARBY_POOL)
            .sortedWith(compareByDescending<CctvData> { it.analysisPriority() }.thenBy { it.distanceTo(lat, lon) })
        return (favs + nearby).distinctBy { it.bestUrl }.take(MAX_RETRY)
    }

    private suspend fun autoAnalyzeWithRetry(candidates: List<CctvData>, lat: Double, lon: Double) {
        if (settings.effectiveGeminiKey.isBlank()) {
            _analysisState.value = AnalysisState.Error(MissingApiKeyException().message.orEmpty(), true)
            return
        }
        val cooldown = settings.quotaCooldownRemainingMs
        if (cooldown > 0) {
            Log.d(TAG, "API 쿨다운 중 (${cooldown / 1000}초) → 자동분석 스킵")
            return
        }
        // 보행자가 한 명도 없으면 우산 비율을 알 수 없으므로 다음 후보를 한 번 더 시도한다.
        var noPeople: Pair<CctvData, AnalysisResult>? = null
        var emptyScenes = 0
        for ((i, cctv) in candidates.withIndex()) {
            Log.d(TAG, "자동분석 ${i + 1}/${candidates.size}: ${cctv.displayName}")
            try {
                val result = analyzeOne(cctv)
                if (result.allPedestrians > 0) {
                    _analysisState.value = AnalysisState.Done(cctv, result, cctv.distanceTo(lat, lon), true)
                    return
                }
                if (noPeople == null) noPeople = cctv to result
                if (++emptyScenes >= MAX_EMPTY_SCENE_TRIES) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiQuotaException) {
                settings.lastQuotaExceededMs = System.currentTimeMillis()
                Log.w(TAG, "API 할당량 초과 → 자동분석 중단")
                if (noPeople == null) {
                    _analysisState.value = AnalysisState.Error(e.message.orEmpty(), true)
                    return
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "분석 실패: ${e.message} → 다음 CCTV")
            }
        }
        val (c, r) = noPeople ?: run {
            Log.w(TAG, "자동분석 모든 후보 실패")
            _analysisState.value = AnalysisState.Error("주변 CCTV 영상을 분석하지 못했습니다.", true)
            return
        }
        _analysisState.value = AnalysisState.Done(c, r, c.distanceTo(lat, lon), true)
    }

    fun analyzeManual(cctv: CctvData, lat: Double = _userLat.value ?: 0.0, lon: Double = _userLon.value ?: 0.0) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            try {
                if (settings.effectiveGeminiKey.isBlank()) throw MissingApiKeyException()
                val result = analyzeOne(cctv)
                _analysisState.value = AnalysisState.Done(cctv, result, cctv.distanceTo(lat, lon), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiQuotaException) {
                settings.lastQuotaExceededMs = System.currentTimeMillis()
                _analysisState.value = AnalysisState.Error(e.message.orEmpty(), false)
            } catch (e: AnalysisException) {
                _analysisState.value = AnalysisState.Error(e.message.orEmpty(), false)
            } catch (e: MissingApiKeyException) {
                _analysisState.value = AnalysisState.Error(e.message.orEmpty(), false)
            } catch (e: Exception) {
                Log.e(TAG, "분석 실패", e)
                _analysisState.value = AnalysisState.Error("분석 중 오류가 발생했습니다: ${e.message.orEmpty()}", false)
            }
        }
    }

    private suspend fun analyzeOne(cctv: CctvData): AnalysisResult {
        val key = cctv.bestUrl.orEmpty()
        resultCache[key]?.let {
            if (System.currentTimeMillis() - it.ts < RESULT_CACHE_MS) {
                Log.d(TAG, "캐시 결과 사용: ${cctv.displayName}")
                return it.result
            }
        }
        _analysisState.value = AnalysisState.Downloading(cctv.displayName)
        val frames = grabber.grab(cctv)
        try {
            _analysisState.value = AnalysisState.Analyzing(cctv.displayName)
            throttleApiCall()
            settings.lastApiCallMs = System.currentTimeMillis()
            val result = analyzer.analyze(frames, settings.excludeSheltered)
            settings.lastQuotaExceededMs = 0L
            resultCache[key] = Cached(result, System.currentTimeMillis())
            return result
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    /** 무료 등급 분당 호출 제한을 넘지 않도록 호출 간격을 둔다. */
    private suspend fun throttleApiCall() {
        val wait = MIN_API_INTERVAL_MS - (System.currentTimeMillis() - settings.lastApiCallMs)
        if (wait > 0) {
            Log.d(TAG, "API 간격 대기 ${wait}ms")
            delay(wait)
        }
    }

    companion object {
        private const val TAG = "ViewModel"
        private const val MIN_API_INTERVAL_MS = 6_100L
        private const val RESULT_CACHE_MS = 5 * 60 * 1000L
        private const val MAX_RETRY = 4
        private const val NEARBY_POOL = 8
        private const val MAX_EMPTY_SCENE_TRIES = 2
    }
}
