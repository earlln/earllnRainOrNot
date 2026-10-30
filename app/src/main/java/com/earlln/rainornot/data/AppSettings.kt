package com.earlln.rainornot.data

import android.content.Context
import androidx.preference.PreferenceManager
import com.earlln.rainornot.BuildConfig

class AppSettings(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var autoAnalyze: Boolean
        get() = prefs.getBoolean("auto_analyze", true)
        set(v) = prefs.edit().putBoolean("auto_analyze", v).apply()

    var searchRadiusKm: Int
        get() = prefs.getString("search_radius_km", "10")?.toIntOrNull()?.coerceIn(1, 9999) ?: 10
        set(v) = prefs.edit().putString("search_radius_km", v.toString()).apply()

    /** 움직이는 보행자만 우산 비율에 반영할지 여부 */
    var movingOnly: Boolean
        get() = prefs.getBoolean("moving_only", true)
        set(v) = prefs.edit().putBoolean("moving_only", v).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", true)
        set(v) = prefs.edit().putBoolean("voice_enabled", v).apply()

    var screenEnabled: Boolean
        get() = prefs.getBoolean("screen_enabled", true)
        set(v) = prefs.edit().putBoolean("screen_enabled", v).apply()

    var showList: Boolean
        get() = prefs.getBoolean("show_list", true)
        set(v) = prefs.edit().putBoolean("show_list", v).apply()

    var includeHighwayCctv: Boolean
        get() = prefs.getBoolean("include_highway_cctv", true)
        set(v) = prefs.edit().putBoolean("include_highway_cctv", v).apply()

    /** 사용자가 설정 화면에서 입력한 Gemini API 키 (공개 배포 APK 에는 키가 없으므로 필수) */
    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "").orEmpty().trim()
        set(v) = prefs.edit().putString("gemini_api_key", v.trim()).apply()

    var itsApiKey: String
        get() = prefs.getString("its_api_key", "").orEmpty().trim()
        set(v) = prefs.edit().putString("its_api_key", v.trim()).apply()

    /** 사용자 입력 키가 있으면 우선, 없으면 빌드 시 주입된 키 */
    val effectiveGeminiKey: String get() = geminiApiKey.ifEmpty { BuildConfig.GEMINI_API_KEY }
    val effectiveItsKey: String get() = itsApiKey.ifEmpty { BuildConfig.ITS_API_KEY }

    var lastApiCallMs: Long
        get() = prefs.getLong("last_api_call_ms", 0L)
        set(v) = prefs.edit().putLong("last_api_call_ms", v).apply()

    var lastQuotaExceededMs: Long
        get() = prefs.getLong("last_quota_exceeded_ms", 0L)
        set(v) = prefs.edit().putLong("last_quota_exceeded_ms", v).apply()

    val quotaCooldownRemainingMs: Long
        get() = (QUOTA_COOLDOWN_MS - (System.currentTimeMillis() - lastQuotaExceededMs)).coerceAtLeast(0L)

    companion object {
        const val QUOTA_COOLDOWN_MS = 70_000L
    }
}
