package com.earlln.rainornot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.earlln.rainornot.R
import com.earlln.rainornot.data.AppSettings
import com.earlln.rainornot.data.CctvData
import com.earlln.rainornot.data.FavoritesManager
import com.earlln.rainornot.data.formatDistance
import com.earlln.rainornot.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var tts: TtsManager
    private val vm: CctvViewModel by viewModels()
    private var currentCctv: CctvData? = null

    private val adapter by lazy {
        CctvAdapter(::onAnalyzeClick, ::onWatchClick, ::onFavoriteClick) { vm.favorites.isFavorite(it) }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) fetchLocation()
        else {
            showSnackbar("위치 권한이 필요합니다. 설정에서 허용해 주세요.")
            b.swipeRefresh.isRefreshing = false
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        applySettingsToUi()
        checkPermissionAndFetch()
    }

    private val mapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val url = result.data?.getStringExtra(MapActivity.RESULT_ANALYZE) ?: return@registerForActivityResult
        CctvDataHolder.cctvList.firstOrNull { it.bestUrl == url }?.let { onAnalyzeClick(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        settings = AppSettings(this)
        tts = TtsManager(this)

        b.recyclerView.adapter = adapter
        b.swipeRefresh.setOnRefreshListener { checkPermissionAndFetch() }
        b.btnRetry.setOnClickListener { checkPermissionAndFetch() }
        b.btnToggleVoice.setOnClickListener {
            settings.voiceEnabled = !settings.voiceEnabled
            if (!settings.voiceEnabled) tts.stop()
            updateToggleButtons()
            showSnackbar(if (settings.voiceEnabled) "음성 안내 켜짐" else "음성 안내 꺼짐")
        }
        b.btnToggleScreen.setOnClickListener {
            settings.screenEnabled = !settings.screenEnabled
            updateToggleButtons()
            vm.analysisState.value?.let { renderAnalysisState(it, speak = false) }
            showSnackbar(if (settings.screenEnabled) "화면 표시 켜짐" else "화면 표시 꺼짐")
        }
        b.btnWatchCurrent.setOnClickListener { currentCctv?.let { onWatchClick(it) } }

        vm.state.observe(this) { renderListState(it) }
        vm.analysisState.observe(this) { renderAnalysisState(it, speak = true) }
        vm.userLat.observe(this) { adapter.userLat = it }
        vm.userLon.observe(this) { adapter.userLon = it }

        applySettingsToUi()
        updateToggleButtons()
        if (savedInstanceState == null) checkPermissionAndFetch()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_map -> {
            if (vm.state.value is UiState.Success) mapLauncher.launch(Intent(this, MapActivity::class.java))
            else showSnackbar("CCTV 목록을 먼저 불러주세요.")
            true
        }
        R.id.action_settings -> {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_refresh -> {
            checkPermissionAndFetch()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ── 위치 ─────────────────────────────────────────────────────

    private fun checkPermissionAndFetch() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) fetchLocation()
        else locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        b.swipeRefresh.isRefreshing = true
        val client = LocationServices.getFusedLocationProviderClient(this)
        try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) startAnalysis(loc.latitude, loc.longitude)
                    else client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) startAnalysis(last.latitude, last.longitude)
                        else {
                            b.swipeRefresh.isRefreshing = false
                            showSnackbar("위치를 가져올 수 없습니다. GPS를 켜주세요.")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    b.swipeRefresh.isRefreshing = false
                    showSnackbar("위치 오류: ${e.message}")
                }
        } catch (e: SecurityException) {
            b.swipeRefresh.isRefreshing = false
            showSnackbar("위치 권한이 거부되었습니다.")
        }
    }

    private fun startAnalysis(lat: Double, lon: Double) {
        reverseGeocode(lat, lon)
        vm.fetchAndAnalyze(lat, lon, settings.searchRadiusKm, settings.autoAnalyze, settings.includeHighwayCctv)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double) {
        val fallback = "📍 %.4f°N, %.4f°E".format(lat, lon)
        b.toolbar.subtitle = fallback
        if (!Geocoder.isPresent()) return
        Thread {
            val text = try {
                Geocoder(this, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { formatAddress(it) }
            } catch (e: Exception) {
                null
            }
            if (text != null) runOnUiThread { if (!isFinishing) b.toolbar.subtitle = text }
        }.start()
    }

    private fun formatAddress(a: Address): String? {
        val parts = listOfNotNull(a.adminArea, a.subAdminArea ?: a.locality, a.subLocality ?: a.thoroughfare)
            .distinct()
        return if (parts.isEmpty()) null else "📍 " + parts.joinToString(" ")
    }

    // ── 목록 ─────────────────────────────────────────────────────

    private fun applySettingsToUi() {
        b.recyclerView.visibility = if (settings.showList) View.VISIBLE else View.GONE
    }

    private fun renderListState(state: UiState) {
        b.progressBar.visibility = if (state is UiState.Loading && !b.swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
        if (state !is UiState.Loading) b.swipeRefresh.isRefreshing = false
        b.tvEmpty.visibility = View.GONE
        b.groupError.visibility = View.GONE
        when (state) {
            is UiState.Success -> {
                adapter.submitList(state.items)
                b.recyclerView.visibility = if (settings.showList) View.VISIBLE else View.GONE
            }
            is UiState.Empty -> {
                adapter.submitList(emptyList())
                b.tvEmpty.text = "검색 반경(${settings.searchRadiusKm}km) 내 CCTV가 없습니다.\n설정에서 반경을 늘려보세요."
                b.tvEmpty.visibility = View.VISIBLE
            }
            is UiState.Error -> {
                b.tvError.text = state.message
                b.groupError.visibility = View.VISIBLE
            }
            else -> Unit
        }
    }

    // ── 분석 결과 ────────────────────────────────────────────────

    private fun renderAnalysisState(state: AnalysisState, speak: Boolean) {
        val screen = settings.screenEnabled
        when (state) {
            is AnalysisState.Idle -> b.cardAnalysis.visibility = View.GONE
            is AnalysisState.Downloading, is AnalysisState.Analyzing -> {
                b.cardAnalysis.visibility = View.VISIBLE
                b.progressAnalysis.visibility = View.VISIBLE
                val name = if (state is AnalysisState.Downloading) state.cctvName else (state as AnalysisState.Analyzing).cctvName
                b.tvAnalysisStatus.text = name
                b.tvAnalysisResult.text =
                    if (state is AnalysisState.Downloading) "📡 CCTV 영상 프레임 가져오는 중..." else "🤖 AI 분석 중 (보행자·우산 확인)..."
                b.tvAnalysisDetail.visibility = View.GONE
                b.tvAnalysisDistance.visibility = View.GONE
                b.btnWatchCurrent.visibility = View.GONE
            }
            is AnalysisState.Done -> {
                currentCctv = state.cctv
                b.cardAnalysis.visibility = if (screen) View.VISIBLE else View.GONE
                b.progressAnalysis.visibility = View.GONE
                b.tvAnalysisStatus.text = (if (state.isAuto) "자동분석 · " else "") + state.cctv.displayName
                b.tvAnalysisDistance.text = "📍 " + formatDistance(state.distanceKm)
                b.tvAnalysisDistance.visibility = View.VISIBLE
                b.tvAnalysisResult.text = state.result.toDisplayText()
                b.tvAnalysisDetail.text = state.result.toDetailText()
                b.tvAnalysisDetail.visibility = View.VISIBLE
                b.btnWatchCurrent.visibility = View.VISIBLE
                if (speak && settings.voiceEnabled) {
                    tts.speak("${state.cctv.displayName} 입니다. ${state.result.toSpeech()}")
                }
            }
            is AnalysisState.Error -> {
                b.cardAnalysis.visibility = if (screen) View.VISIBLE else View.GONE
                b.progressAnalysis.visibility = View.GONE
                b.tvAnalysisStatus.text = "⚠️ 분석 오류"
                b.tvAnalysisResult.text = state.message
                b.tvAnalysisDetail.visibility = View.GONE
                b.tvAnalysisDistance.visibility = View.GONE
                if (speak && settings.voiceEnabled && !state.isAuto) tts.speak("분석 오류. ${state.message}")
            }
        }
    }

    private fun updateToggleButtons() {
        b.btnToggleVoice.text = if (settings.voiceEnabled) "🔊 음성 ON" else "🔇 음성 OFF"
        b.btnToggleScreen.text = if (settings.screenEnabled) "🖥️ 화면 ON" else "🖥️ 화면 OFF"
    }

    // ── 목록 항목 액션 ────────────────────────────────────────────

    private fun onAnalyzeClick(cctv: CctvData) {
        vm.analyzeManual(cctv)
        showSnackbar("${cctv.displayName} 분석 시작...")
    }

    private fun onWatchClick(cctv: CctvData) {
        startActivity(Intent(this, CctvDetailActivity::class.java).apply {
            putExtra(CctvDetailActivity.EXTRA_NAME, cctv.displayName)
            putExtra(CctvDetailActivity.EXTRA_STREAM_URL, cctv.bestUrl)
            putExtra(CctvDetailActivity.EXTRA_TYPE, cctv.cctvType)
        })
    }

    private fun onFavoriteClick(cctv: CctvData) {
        val added = vm.favorites.toggle(cctv)
        val count = vm.favorites.getAll().size
        showSnackbar(
            if (added) "⭐ 즐겨찾기 추가 ($count/${FavoritesManager.MAX_FAVORITES})\n분석 우선순위 ${count}번"
            else "☆ 즐겨찾기 제거"
        )
    }

    private fun showSnackbar(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
}
