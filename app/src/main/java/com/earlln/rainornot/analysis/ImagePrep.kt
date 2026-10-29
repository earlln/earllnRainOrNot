package com.earlln.rainornot.analysis

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImagePrep {
    private const val LUMA_WIDTH = 320

    /** 움직임 분석용 축소 흑백 프레임. 모든 프레임을 첫 프레임과 같은 크기로 맞춘다. */
    fun toLumaFrames(frames: List<Bitmap>): List<LumaFrame> {
        if (frames.isEmpty()) return emptyList()
        val w = minOf(LUMA_WIDTH, frames[0].width)
        val h = max(1, (frames[0].height * w.toDouble() / frames[0].width).roundToInt())
        return frames.map { bmp ->
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bmp) small.recycle()
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                px[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            LumaFrame(w, h, px)
        }
    }

    /** 정규화 박스 영역을 잘라 긴 변이 targetLong 이 되도록 확대(최대 4배)한다. */
    fun crop(src: Bitmap, box: Box, targetLong: Int = 1024): Bitmap {
        val x = (box.xmin * src.width / Box.SCALE).coerceIn(0, src.width - 1)
        val y = (box.ymin * src.height / Box.SCALE).coerceIn(0, src.height - 1)
        val w = (box.width * src.width / Box.SCALE).coerceIn(1, src.width - x)
        val h = (box.height * src.height / Box.SCALE).coerceIn(1, src.height - y)
        val cropped = Bitmap.createBitmap(src, x, y, w, h)
        val scale = (targetLong.toDouble() / max(w, h)).coerceIn(1.0, 4.0)
        if (scale == 1.0) return cropped
        val out = Bitmap.createScaledBitmap(cropped, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
        if (out !== cropped) cropped.recycle()
        return out
    }

    /** 긴 변이 maxLong 을 넘을 때만 축소해 JPEG base64 로 인코딩 (확대는 하지 않음) */
    fun toJpegBase64(bmp: Bitmap, maxLong: Int, quality: Int = 88): String {
        val longSide = max(bmp.width, bmp.height)
        val scaled = if (longSide > maxLong) {
            val s = maxLong.toDouble() / longSide
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
