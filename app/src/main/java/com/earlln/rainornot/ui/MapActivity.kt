package com.earlln.rainornot.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.earlln.rainornot.BuildConfig
import com.earlln.rainornot.data.CctvData
import com.earlln.rainornot.data.FavoritesManager
import com.earlln.rainornot.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapActivity : AppCompatActivity() {
    private lateinit var b: ActivityMapBinding
    private lateinit var favorites: FavoritesManager
    private var myLocationOverlay: MyLocationNewOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = BuildConfig.APPLICATION_ID
        }
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbarMap)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbarMap.setNavigationOnClickListener { finish() }
        favorites = FavoritesManager(this)

        setupMap()
        refreshMarkers()
        b.fabMyLocation.setOnClickListener { goToMyLocation() }
    }

    private fun setupMap() {
        b.mapView.setTileSource(TileSourceFactory.MAPNIK)
        b.mapView.setMultiTouchControls(true)
        b.mapView.controller.setZoom(14.0)
        b.mapView.controller.setCenter(GeoPoint(CctvDataHolder.userLat, CctvDataHolder.userLon))
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), b.mapView).also {
            it.enableMyLocation()
            b.mapView.overlays.add(it)
        }
    }

    private fun refreshMarkers() {
        b.mapView.overlays.removeAll { it is Marker }
        val list = CctvDataHolder.cctvList
        for (cctv in list) {
            val color = when {
                favorites.isFavorite(cctv) -> Color.parseColor("#FFC107")
                cctv.hasCrosswalk() -> Color.parseColor("#F44336")
                else -> Color.parseColor("#2196F3")
            }
            val marker = Marker(b.mapView).apply {
                position = GeoPoint(cctv.latitude, cctv.longitude)
                title = cctv.displayName
                snippet = "${cctv.typeLabel} · ${cctv.formattedDistance(CctvDataHolder.userLat, CctvDataHolder.userLon)}"
                icon = createCircleMarker(color)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> showCctvDialog(cctv); true }
            }
            b.mapView.overlays.add(marker)
        }
        b.tvMarkerCount.text = "${list.size}개 CCTV  ★즐겨찾기 ${favorites.getAll().size}개"
        b.mapView.invalidate()
    }

    private fun showCctvDialog(cctv: CctvData) {
        val fav = favorites.isFavorite(cctv)
        AlertDialog.Builder(this)
            .setTitle(cctv.displayName)
            .setMessage("${cctv.typeLabel}\n거리: ${cctv.formattedDistance(CctvDataHolder.userLat, CctvDataHolder.userLon)}")
            .setPositiveButton("☔ 우산 분석") { _, _ ->
                setResult(RESULT_OK, Intent().putExtra(RESULT_ANALYZE, cctv.bestUrl))
                finish()
            }
            .setNeutralButton("📹 CCTV 보기") { _, _ ->
                startActivity(Intent(this, CctvDetailActivity::class.java).apply {
                    putExtra(CctvDetailActivity.EXTRA_NAME, cctv.displayName)
                    putExtra(CctvDetailActivity.EXTRA_STREAM_URL, cctv.bestUrl)
                    putExtra(CctvDetailActivity.EXTRA_TYPE, cctv.cctvType)
                })
            }
            .setNegativeButton(if (fav) "☆ 즐겨찾기 제거" else "⭐ 즐겨찾기 추가") { _, _ ->
                favorites.toggle(cctv)
                refreshMarkers()
            }
            .show()
    }

    private fun goToMyLocation() {
        val p = myLocationOverlay?.myLocation ?: GeoPoint(CctvDataHolder.userLat, CctvDataHolder.userLon)
        b.mapView.controller.animateTo(p)
    }

    private fun createCircleMarker(color: Int): BitmapDrawable {
        val sizePx = (22 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        canvas.drawCircle(r, r, r - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        })
        return BitmapDrawable(resources, bmp)
    }

    override fun onResume() {
        super.onResume()
        b.mapView.onResume()
    }

    override fun onPause() {
        b.mapView.onPause()
        super.onPause()
    }

    companion object {
        const val RESULT_ANALYZE = "result_analyze_cctv"
    }
}
