package com.earlln.rainornot.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
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
            findPreference<Preference>("its_key_help")?.setOnPreferenceClickListener {
                showItsKeyGuide()
                true
            }
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

        /** ITS 키 발급 절차 안내 (사이트 메뉴 이름은 개편될 수 있어 흐름 위주로 안내) */
        private fun showItsKeyGuide() {
            AlertDialog.Builder(requireContext())
                .setTitle("ITS API 키 발급 방법")
                .setMessage(
                    "서울 밖 지역의 국도 CCTV는 국가교통정보센터(ITS) 키가 있어야 볼 수 있어요. 무료입니다.\n\n" +
                        "1. 아래 '발급 사이트 열기'로 국가교통정보센터 공개 API 페이지에 들어갑니다.\n" +
                        "2. 회원가입 후 로그인합니다.\n" +
                        "3. 오픈 API 목록에서 'CCTV 정보'를 찾아 활용 신청(인증키 신청)을 합니다. 이용 목적은 '개인 날씨 확인'처럼 간단히 적으면 됩니다.\n" +
                        "4. 승인되면(보통 바로~1일) 마이페이지의 인증키 관리에서 키를 복사합니다.\n" +
                        "5. 이 화면의 'ITS API 키'에 붙여 넣으면 끝!\n\n" +
                        "※ 사이트 메뉴 이름은 바뀔 수 있어요. 키는 이 기기에만 저장됩니다."
                )
                .setPositiveButton("발급 사이트 열기") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.its.go.kr/opendata/")))
                }
                .setNegativeButton("닫기", null)
                .show()
        }

        private fun maskKey(key: String?): String =
            if (key.isNullOrBlank()) "미입력 — 탭해서 입력" else "입력됨 (…${key.takeLast(4)})"

        private fun maskItsKey(key: String?): String =
            if (key.isNullOrBlank()) "미입력 — 서울 CCTV만 표시 (지방은 키 필요)" else "입력됨 (…${key.takeLast(4)}) — 전국 국도 CCTV 포함"
    }
}
