package com.earlln.rainornot.ui

import com.earlln.rainornot.analysis.AnalysisResult
import com.earlln.rainornot.data.CctvData

sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Success(val items: List<CctvData>) : UiState()
    data object Empty : UiState()
    data class Error(val message: String) : UiState()
}

sealed class AnalysisState {
    data object Idle : AnalysisState()
    data class Downloading(val cctvName: String) : AnalysisState()
    data class Analyzing(val cctvName: String) : AnalysisState()
    data class Done(
        val cctv: CctvData,
        val result: AnalysisResult,
        val distanceKm: Double,
        val isAuto: Boolean,
    ) : AnalysisState()
    data class Error(val message: String, val isAuto: Boolean) : AnalysisState()
}

/** 화면 간(목록 ↔ 지도) CCTV 목록 공유 */
object CctvDataHolder {
    var cctvList: List<CctvData> = emptyList()
    var userLat: Double = 0.0
    var userLon: Double = 0.0
}
