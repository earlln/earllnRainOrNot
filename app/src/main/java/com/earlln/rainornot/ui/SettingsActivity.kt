package com.earlln.rainornot.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.earlln.rainornot.BuildConfig
import com.earlln.rainornot.R
import com.earlln.rainornot.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbarSettings)
        supportActionBar?.apply {
            title = getString(R.string.settings)
            setDisplayHomeAsUpEnabled(true)
        }
        b.toolbarSettings.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("app_version")?.summary =
                "${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE})"
            for (key in listOf("gemini_api_key", "its_api_key")) {
                findPreference<EditTextPreference>(key)?.apply {
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        it.setSingleLine()
                    }
                    setSummaryProvider { p ->
                        val text = (p as EditTextPreference).text
                        if (key == "its_api_key") maskItsKey(text) else maskKey(text)
                    }
                    setOnPreferenceChangeListener { _, v ->
                        text = (v as String).trim() // 붙여넣기 공백 제거
                        false
                    }
                }
            }
        }

        private fun maskKey(key: String?): String =
            if (key.isNullOrBlank()) "미입력 — 탭해서 입력" else "입력됨 (…${key.takeLast(4)})"

        /** ITS 는 기본 키가 있으므로 비어 있으면 '기본 키 사용'으로 안내한다 */
        private fun maskItsKey(key: String?): String =
            if (key.isNullOrBlank()) {
                if (BuildConfig.ITS_API_KEY.isNotBlank()) "기본 키 사용 중 — 개인 키를 넣으면 우선 사용" else "미입력 — 서울 CCTV만 표시"
            } else "개인 키 사용 중 (…${key.takeLast(4)})"
    }
}
