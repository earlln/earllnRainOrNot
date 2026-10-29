package com.earlln.rainornot.analysis

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 다중 프레임 + 확대 영역을 Gemini 에 보내 보행자별 우산 여부를 구조화 JSON 으로 받는다.
 */
class GeminiAnalyzer(
    private val apiKey: String,
    primaryModel: String,
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // 이미지 여러 장 + 사고(thinking) 시간 고려
        .build()

    /** 설정 모델이 종료/미지원이면 순서대로 대체 (gemini-2.0-flash 는 2026-06-01 종료됨) */
    private val models = listOf(primaryModel, "gemini-flash-latest", "gemini-2.5-flash")
        .filter { it.isNotBlank() }.distinct()

    @Volatile private var workingModel: String? = null

    suspend fun analyze(frames: List<Bitmap>, movingOnly: Boolean): AnalysisResult = withContext(Dispatchers.Default) {
        if (apiKey.isBlank()) throw AnalysisException("Gemini API 키가 설정되지 않았습니다.\n(GitHub Secrets 의 GEMINI_API_KEY 확인)")
        require(frames.isNotEmpty())

        val latest = frames.last()
        val motion = MotionDetector.detect(ImagePrep.toLumaFrames(frames))
        val crops = CropPlanner.plan(motion)
        Log.d(TAG, "움직임: reliable=${motion.reliable} boxes=${motion.boxes.size} (${motion.reason}) → 확대 ${crops.size}곳")

        val infos = ArrayList<AnalysisPrompt.ImageInfo>()
        val images = ArrayList<String>()
        infos += AnalysisPrompt.ImageInfo(AnalysisPrompt.ImageInfo.Kind.LATEST_FULL)
        images += ImagePrep.toJpegBase64(latest, maxLong = 1600)
        if (frames.size > 1 && motion.reliable) {
            infos += AnalysisPrompt.ImageInfo(AnalysisPrompt.ImageInfo.Kind.EARLIER_FULL)
            images += ImagePrep.toJpegBase64(frames.first(), maxLong = 1024, quality = 80)
        }
        for (box in crops) {
            val c = ImagePrep.crop(latest, box)
            infos += AnalysisPrompt.ImageInfo(AnalysisPrompt.ImageInfo.Kind.ZOOM, box)
            images += ImagePrep.toJpegBase64(c, maxLong = 1024)
            c.recycle()
        }

        val motionNote = if (motion.reliable) {
            val shown = motion.boxes.sortedByDescending { it.height >= it.width }.take(8)
            "프레임 ${frames.size}장 비교, 움직임 영역 ${motion.boxes.size}곳" +
                (if (shown.isNotEmpty()) ": " + shown.joinToString("; ") { it.describe() } else " (움직이는 물체 없음)")
        } else {
            "움직임 정보 없음 (${motion.reason}) — motion 은 자세로 추정하거나 unknown"
        }
        val prompt = AnalysisPrompt.build(infos, motionNote)
        val body = buildRequest(infos, images, prompt)

        val (text, model) = callWithFallback(body)
        Log.d(TAG, "분석 텍스트($model): ${text.take(400)}")
        val verdict = VerdictParser.parse(text) ?: throw AnalysisException("분석 결과 없음 (응답 JSON 파싱 실패)")
        ResultAggregator.aggregate(verdict, motion, frames.size, movingOnly, model)
    }

    private fun buildRequest(infos: List<AnalysisPrompt.ImageInfo>, images: List<String>, prompt: String): String {
        val parts = ArrayList<Map<String, Any>>()
        images.forEachIndexed { i, b64 ->
            val label = when (infos[i].kind) {
                AnalysisPrompt.ImageInfo.Kind.LATEST_FULL -> "이미지 ${i + 1} (최신 전체 화면)"
                AnalysisPrompt.ImageInfo.Kind.EARLIER_FULL -> "이미지 ${i + 1} (이전 전체 화면)"
                AnalysisPrompt.ImageInfo.Kind.ZOOM -> "이미지 ${i + 1} (확대: ${infos[i].region!!.describe()})"
            }
            parts += mapOf("text" to label)
            parts += mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64))
        }
        parts += mapOf("text" to prompt)
        val req = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to AnalysisPrompt.responseSchema,
                "maxOutputTokens" to 8192,
            ),
        )
        return gson.toJson(req)
    }

    private suspend fun callWithFallback(body: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(workingModel) + models.filter { it != workingModel }
        var lastError: Exception? = null
        for (model in candidates) {
            try {
                val text = call(model, body)
                workingModel = model
                return@withContext text to model
            } catch (e: ModelUnavailableException) {
                Log.w(TAG, "모델 $model 사용 불가 → 다음 모델 시도: ${e.message}")
                lastError = e
            }
        }
        throw AnalysisException("사용 가능한 Gemini 모델이 없습니다: ${lastError?.message}")
    }

    private suspend fun call(model: String, body: String, attempt: Int = 0): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val req = Request.Builder().url(url)
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val (code, raw) = try {
            http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: java.net.SocketTimeoutException) {
            throw AnalysisException("서버 응답 시간 초과. 잠시 후 다시 시도해주세요.")
        }
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        val errMsg = json?.getAsJsonObject("error")?.get("message")?.asString

        if (code != 200) {
            Log.w(TAG, "HTTP $code 응답: ${raw.take(300)}")
            when {
                code == 429 -> throw GeminiQuotaException("Gemini API 사용 한도 도달\n1분 후 다시 시도해주세요.")
                code == 404 || (code == 400 && errMsg?.contains("not found", true) == true) ||
                    errMsg?.contains("not supported", true) == true ->
                    throw ModelUnavailableException(errMsg ?: "HTTP $code")
                code == 400 && errMsg?.contains("API key", true) == true ->
                    throw AnalysisException("API 키가 유효하지 않습니다.")
                code == 403 -> throw AnalysisException("API 키 권한이 없습니다: ${errMsg.orEmpty()}")
                code >= 500 && attempt < 1 -> {
                    delay(2000)
                    return call(model, body, attempt + 1)
                }
                else -> throw AnalysisException("Gemini 오류($code): ${errMsg ?: "알 수 없는 API 오류"}")
            }
        }
        json ?: throw AnalysisException("응답 없음 (HTTP $code)")

        json.getAsJsonObject("promptFeedback")?.get("blockReason")?.asString?.let {
            throw AnalysisException("Gemini가 이미지를 처리하지 못했습니다 ($it)")
        }
        val candidate = json.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
            ?: throw AnalysisException("분석 결과 없음 (이미지를 인식하지 못했습니다)")
        val text = textOf(candidate)
        if (text.isBlank()) {
            val reason = candidate.get("finishReason")?.asString
            throw AnalysisException("응답 텍스트 없음 (finishReason=$reason)")
        }
        return text
    }

    /** thinking 파트(thought=true)는 제외하고 본문 텍스트만 합친다. */
    private fun textOf(candidate: JsonObject): String {
        val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts") ?: return ""
        return parts.mapNotNull { p ->
            val o = p.asJsonObject
            if (o.get("thought")?.asBoolean == true) null else o.get("text")?.asString
        }.joinToString("")
    }

    private class ModelUnavailableException(message: String) : Exception(message)

    companion object {
        private const val TAG = "GeminiAnalyzer"
    }
}
