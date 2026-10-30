package com.earlln.rainornot.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.earlln.rainornot.data.CctvData
import com.earlln.rainornot.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * CCTV 에서 시간 순서대로 여러 프레임을 가져온다.
 * 기존 앱은 프레임 1장만 사용해 '움직이는 사람'을 구분할 수 없었다.
 * - 실시간(HLS)/동영상(MP4): 한 영상 구간에서 약 0.8초 간격으로 3프레임
 * - 정지영상(JPEG): 간격을 두고 다시 받아 갱신되었으면 추가 (갱신 안 되면 1장)
 */
class FrameGrabber(context: Context) {
    private val cacheDir = context.cacheDir

    suspend fun grab(cctv: CctvData): List<Bitmap> = withContext(Dispatchers.IO) {
        val url = cctv.bestUrl?.takeIf { it.isNotBlank() } ?: throw AnalysisException("스트림 URL이 없습니다.")
        Log.d(TAG, "타입${cctv.cctvType} 시작: $url")
        val frames = when (cctv.cctvType) {
            "3" -> grabStill(url)
            "2" -> grabVideoFile(url).ifEmpty { grabStill(url) }
            "1" -> grabHls(url).ifEmpty { grabStill(url) }
            else -> grabStill(url).ifEmpty { grabVideoFile(url) }
        }
        if (frames.isEmpty()) throw AnalysisException("CCTV 이미지를 가져올 수 없습니다")
        Log.d(TAG, "프레임 ${frames.size}장 확보 (${frames[0].width}x${frames[0].height})")
        frames
    }

    // ── 정지영상 ────────────────────────────────────────────────

    private suspend fun grabStill(url: String): List<Bitmap> {
        val first = downloadBytes(url) ?: return emptyList()
        val firstBmp = decode(first) ?: return emptyList()
        val frames = arrayListOf(firstBmp)
        var last = first
        repeat(STILL_EXTRA_FETCHES) {
            delay(STILL_INTERVAL_MS)
            val next = downloadBytes(url) ?: return frames
            if (next.contentEquals(last)) return frames // 아직 갱신 안 됨 → 움직임 비교 불가
            decode(next)?.let { frames += it }
            last = next
        }
        return frames
    }

    // ── 동영상 파일(MP4) ─────────────────────────────────────────

    private fun grabVideoFile(url: String): List<Bitmap> {
        val bytes = downloadBytes(url, MAX_VIDEO_BYTES) ?: return emptyList()
        return withTempFile(bytes) { extractFrames(it.absolutePath) }
    }

    // ── 실시간(HLS) ─────────────────────────────────────────────

    private fun grabHls(url: String): List<Bitmap> {
        val segmentUrl = resolveSegment(url, 0)
        if (segmentUrl != null) {
            Log.d(TAG, "TS URL: ${segmentUrl.take(120)}")
            val bytes = downloadBytes(segmentUrl, MAX_VIDEO_BYTES)
            if (bytes != null) {
                val frames = withTempFile(bytes) { extractFrames(it.absolutePath) }
                if (frames.isNotEmpty()) return frames
            }
        } else {
            Log.w(TAG, "TS URL 획득 실패, 직접 시도")
        }
        return tryRetrieverDirect(url)
    }

    /** 마스터 플레이리스트면 가장 높은 화질을 고르고, 미디어 플레이리스트면 가장 최근 세그먼트를 반환 */
    private fun resolveSegment(playlistUrl: String, depth: Int): String? {
        if (depth > 3) return null
        val text = downloadText(playlistUrl) ?: return null
        if (!text.contains("#EXTM3U")) {
            Log.w(TAG, "M3U8 형식 아님: ${text.take(80)}")
            return null
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            var best: Pair<Long, String>? = null
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (best == null || bw > best.first) best = bw to uri
            }
            return best?.let { resolveSegment(resolveUrl(playlistUrl, it.second), depth + 1) }
        }

        val uris = lines.filter { !it.startsWith("#") }
        val last = uris.lastOrNull() ?: return null
        val abs = resolveUrl(playlistUrl, last)
        return if (last.contains(".m3u8")) resolveSegment(abs, depth + 1) else abs
    }

    private fun tryRetrieverDirect(url: String): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(url, mapOf("User-Agent" to Http.USER_AGENT, "Referer" to Http.TOPIS_REFERER))
            framesFrom(r, startUs = 1_000_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Direct MediaRetriever 실패: ${e.message}")
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun extractFrames(path: String): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            framesFrom(r, startUs = 0L)
        } catch (e: Exception) {
            Log.w(TAG, "프레임 추출 실패: ${e.message}")
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun framesFrom(r: MediaMetadataRetriever, startUs: Long): List<Bitmap> {
        val durationUs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.times(1000)
        var times = (0 until VIDEO_FRAME_COUNT).map { startUs + it * VIDEO_FRAME_GAP_US }
        if (durationUs != null && durationUs > 0 && times.last() >= durationUs) {
            // 짧은 구간이면 구간 안에서 고르게 분배
            times = (0 until VIDEO_FRAME_COUNT).map { durationUs * it / VIDEO_FRAME_COUNT }
        }
        val frames = times.mapNotNull { t ->
            runCatching { r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        }
        if (frames.isNotEmpty()) return frames
        return listOfNotNull(runCatching { r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull())
    }

    // ── 네트워크/파일 유틸 ───────────────────────────────────────

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", Http.USER_AGENT)
        .header("Referer", Http.TOPIS_REFERER)
        .build()

    private fun downloadBytes(url: String, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray? = try {
        Http.client.newCall(request(url)).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                Log.w(TAG, "다운로드 HTTP ${resp.code}: ${url.take(120)}")
                null
            } else if (body.contentLength() > maxBytes) {
                Log.w(TAG, "파일이 너무 큼: ${body.contentLength()}B")
                null
            } else {
                body.bytes().also { Log.d(TAG, "다운로드 완료: ${it.size}B") }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "다운로드 실패: ${e.message}")
        null
    }

    private fun downloadText(url: String): String? = try {
        Http.client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "M3U8 HTTP ${resp.code}")
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "M3U8 다운로드 실패: ${e.message}")
        null
    }

    private fun <T> withTempFile(bytes: ByteArray, block: (File) -> T): T {
        val f = File.createTempFile("cctv_seg_", ".tmp", cacheDir)
        return try {
            f.writeBytes(bytes)
            block(f)
        } finally {
            f.delete()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also {
            if (it == null) Log.w(TAG, "유효하지 않은 이미지 데이터 (${bytes.size}B)")
        }

    companion object {
        private const val TAG = "FrameGrabber"
        private const val STILL_EXTRA_FETCHES = 2
        private const val STILL_INTERVAL_MS = 2_500L
        private const val VIDEO_FRAME_COUNT = 3
        private const val VIDEO_FRAME_GAP_US = 800_000L
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 20L * 1024 * 1024

        fun resolveUrl(base: String, ref: String): String = when {
            ref.startsWith("http://") || ref.startsWith("https://") -> ref
            ref.startsWith("//") -> base.substringBefore("://") + ":" + ref
            ref.startsWith("/") -> base.substringBefore("://") + "://" +
                base.substringAfter("://").substringBefore("/") + ref
            else -> base.substringBefore("?").substringBeforeLast("/") + "/" + ref
        }
    }
}

class AnalysisException(message: String) : Exception(message)
class GeminiQuotaException(message: String) : Exception(message)
class MissingApiKeyException : Exception("Gemini API 키가 없습니다.\n설정 > API 키에서 입력해주세요.")
