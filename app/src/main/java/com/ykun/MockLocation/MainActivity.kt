package com.ykun.MockLocation

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.EditText
import android.Manifest
import android.annotation.SuppressLint
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.location.Location
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlin.math.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job

// AdMob用
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.AdError
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private val REQUEST_LOCATION_PERMISSION = 100
    private lateinit var googleMap: GoogleMap

    private var currentMode: MarkerMode = MarkerMode.NONE
    private enum class MarkerMode { NONE, START, VIA, END }

    private var startPoint: LatLng? = null
    private var startMarker: Marker? = null
    private val viaPoints = mutableListOf<LatLng>()
    private val viaMarkers = mutableListOf<Marker>()
    private var endPoint: LatLng? = null
    private var endMarker: Marker? = null

    private var polyline: Polyline? = null

    // native admob再読込
    private var adReloadJob: Job? = null
    private val adReloadIntervalMillis = 60_000L  // ← 60秒ごとに再読み込み

    // リワード広告
    private var rewardedAd: RewardedAd? = null

    // 広告ユニットid
    private lateinit var nativeAdUnitId: String
    private lateinit var rewardAdUnitId: String

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 地図フラグメントの初期化
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // onCreate メソッド内で Context が利用可能になってから初期化
        nativeAdUnitId = getString(R.string.admob_native_id)
        rewardAdUnitId = getString(R.string.admob_reward_id)

        // admob初期化
        MobileAds.initialize(this)
        loadNativeAd()
        loadRewardAd()

        // ネイティブ広告の定期読み込み
        adReloadJob = lifecycleScope.launch {
            while (isActive) {
                delay(adReloadIntervalMillis)
                Log.d("AdReload", "周期的に広告を再読み込み")
                loadNativeAd()
            }
        }

        // 各種 UI 要素の取得
        val startButton: Button = findViewById(R.id.buttonStart)
        val stopButton: Button = findViewById(R.id.buttonStop)
        val startPointButton: Button = findViewById(R.id.buttonStartPoint)
        val viaPointButton: Button = findViewById(R.id.buttonViaPoint)
        val endPointButton: Button = findViewById(R.id.buttonEndPoint)
        val generateRouteButton: Button = findViewById(R.id.buttonGenerateRoute)
        val speedEdit = findViewById<EditText>(R.id.editSpeed)         // ユーザー指定の速度入力欄
        val intervalEdit = findViewById<EditText>(R.id.editInterval)   // 更新間隔入力欄

        val snackbarRootView = findViewById<View>(R.id.snackbarRoot)    // Snackbar表示用ビュー

        // モード切り替え用ボタン
        startPointButton.setOnClickListener {
            currentMode = MarkerMode.START
            Snackbar.make(snackbarRootView, "開始点モード\n長押しで開始点を設置・移動できます", Snackbar.LENGTH_LONG)
                .setTextMaxLines(3)
                .show()
        }
        viaPointButton.setOnClickListener {
            currentMode = MarkerMode.VIA
            Snackbar.make(snackbarRootView, "経由点モード\n長押しで経由点を設置・移動できます\nタップで削除できます", Snackbar.LENGTH_LONG)
                .setTextMaxLines(5)
                .show()
        }
        endPointButton.setOnClickListener {
            currentMode = MarkerMode.END
            Snackbar.make(snackbarRootView, "終了点モード\n長押しで終了点を設置・移動できます", Snackbar.LENGTH_LONG)
                .setTextMaxLines(3)
                .show()
        }

        // ルート生成ボタンが押されたときの処理
        generateRouteButton.setOnClickListener {
            val rawRoute = mutableListOf<LatLng>()
            startPoint?.let { rawRoute.add(it) }
            rawRoute.addAll(viaPoints)
            endPoint?.let { rawRoute.add(it) }

            if (rawRoute.size >= 2) {
                val speedKmh = speedEdit.text.toString().toFloatOrNull() ?: 30f
                val intervalMs = intervalEdit.text.toString().toLongOrNull() ?: 1000L

                val interpolatedRoute = interpolateRoute(rawRoute, speedKmh, intervalMs)

                polyline?.remove()
                polyline = googleMap.addPolyline(PolylineOptions().addAll(interpolatedRoute).width(10f))

                Snackbar.make(snackbarRootView, "経路を生成しました", Snackbar.LENGTH_SHORT).show()

                saveRouteToJsonFile(interpolatedRoute)
            } else {
                Snackbar.make(snackbarRootView, "開始点と終了点を設定してください", Snackbar.LENGTH_LONG)
                    .setTextMaxLines(3)
                    .show()
            }
        }

        // モックサービスの開始/停止
        startButton.setOnClickListener {
            if (checkPermission()) {
                showRewardAd {
                    val speedKmh = speedEdit.text.toString().toFloatOrNull() ?: 30f
                    val accuracy = findViewById<EditText>(R.id.editAccuracy).text.toString().toFloatOrNull() ?: 5f
                    val intervalMs = intervalEdit.text.toString().toLongOrNull() ?: 1000L
                    val intent = Intent(this, MockLocationService::class.java).apply {
                        putExtra("speed_kmh", speedKmh)
                        putExtra("accuracy", accuracy)
                        putExtra("interval_ms", intervalMs)
                    }

                    val message = "モック位置の送信を開始します\n速度: $speedKmh km/h, 精度: $accuracy m, 間隔: $intervalMs ms"
                    Snackbar.make(snackbarRootView, message, Snackbar.LENGTH_LONG)
                        .setTextMaxLines(6)
                        .show()

                    startService(intent)
                }
            } else {
                requestPermission()
            }
        }

        stopButton.setOnClickListener {
            Snackbar.make(snackbarRootView, "モック位置の送信を停止しました", Snackbar.LENGTH_SHORT).show()
            stopService(Intent(this, MockLocationService::class.java))
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false // 二本指回転を無効化
        map.uiSettings.isTiltGesturesEnabled = false   // チルト（斜め視点）を無効化

        val initialLatLng = LatLng(35.38305058390067, 139.27263622165583)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialLatLng, 15f))

        // 長押しでマーカーを追加
        googleMap.setOnMapLongClickListener { latLng ->
            when (currentMode) {
                MarkerMode.START -> {
                    startPoint = latLng
                    startMarker?.remove()
                    startMarker = googleMap.addMarker(MarkerOptions().position(latLng).title("開始点").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)).draggable(true))
                }
                MarkerMode.VIA -> {
                    viaPoints.add(latLng)
                    val marker = googleMap.addMarker(MarkerOptions().position(latLng).title("経由点").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)).draggable(true))
                    marker?.let { viaMarkers.add(it) }
                }
                MarkerMode.END -> {
                    endPoint = latLng
                    endMarker?.remove()
                    endMarker = googleMap.addMarker(MarkerOptions().position(latLng).title("終了点").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).draggable(true))
                }
                else -> {}
            }
        }

        // 経由点の削除処理（タップ）
        googleMap.setOnMarkerClickListener { marker ->
            val index = viaMarkers.indexOf(marker)
            if (index != -1) {
                marker.remove()
                viaMarkers.removeAt(index)
                viaPoints.removeAt(index)
                true
            } else false
        }

        // マーカー移動時の位置更新
        googleMap.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                val newPosition = marker.position
                when {
                    marker == startMarker -> startPoint = newPosition
                    marker == endMarker -> endPoint = newPosition
                    else -> {
                        val index = viaMarkers.indexOf(marker)
                        if (index != -1) viaPoints[index] = newPosition
                    }
                }
            }
        })
    }

    // 補間されたルートを JSON ファイルに保存
    private fun saveRouteToJsonFile(route: List<LatLng>) {
        val jsonArray = JSONArray()
        for (point in route) {
            val obj = JSONObject()
            obj.put("lat", point.latitude)
            obj.put("lng", point.longitude)
            jsonArray.put(obj)
        }
        val file = File(filesDir, "route.json")
        file.writeText(jsonArray.toString())
        Log.d("RouteSave", "ルートを保存しました: ${file.absolutePath}")
    }

    // 権限チェック
    private fun checkPermission(): Boolean {
        val fineLocationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // Android 12(API31)以降で FOREGROUND_SERVICE_LOCATION も合わせてチェック
        val foregroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 11以下では不要
        }

        return fineLocationGranted && foregroundLocationGranted
    }


    // 権限要求
    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 12(API31)以降で FOREGROUND_SERVICE_LOCATION の権限を明示的に要求する必要がある
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_LOCATION_PERMISSION)
    }

    // 地球曲率を考慮しつつ、ルート上の補間点を生成
    private fun interpolateRoute(route: List<LatLng>, speedKmh: Float, intervalMs: Long): List<LatLng> {
        val result = mutableListOf<LatLng>()
        val speedMps = speedKmh / 3.6f
        val intervalSec = intervalMs / 1000f
        val movePerStep = speedMps * intervalSec

        for (i in 0 until route.size - 1) {
            val start = route[i]
            val end = route[i + 1]

            // 2点間の距離を計算
            val results = FloatArray(1)
            Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
            val totalDist = results[0]
            val steps = (totalDist / movePerStep).toInt().coerceAtLeast(1)

            for (step in 0 until steps) {
                val frac = step.toFloat() / steps
                val interpolated = interpolateLatLng(start, end, frac)
                result.add(interpolated)
            }
        }
        result.add(route.last()) // 最終地点も追加
        return result
    }

    // 球面線形補間（Slerp）による緯度経度補間
    private fun interpolateLatLng(start: LatLng, end: LatLng, fraction: Float): LatLng {
        val lat1 = Math.toRadians(start.latitude)
        val lng1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lng2 = Math.toRadians(end.longitude)

        // 2点間の角距離を求める（球面上の距離）
        val d = 2 * asin(sqrt(sin((lat2 - lat1) / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin((lng2 - lng1) / 2).pow(2.0)))
        if (d == 0.0) return start // 零距離なら補間不要

        val A = sin((1 - fraction) * d) / sin(d)
        val B = sin(fraction * d) / sin(d)

        val x = A * cos(lat1) * cos(lng1) + B * cos(lat2) * cos(lng2)
        val y = A * cos(lat1) * sin(lng1) + B * cos(lat2) * sin(lng2)
        val z = A * sin(lat1) + B * sin(lat2)

        val lat = atan2(z, sqrt(x * x + y * y))
        val lng = atan2(y, x)

        return LatLng(Math.toDegrees(lat), Math.toDegrees(lng))
    }

    // native AdMob
    private fun loadNativeAd() {
        val adContainer = findViewById<LinearLayout>(R.id.ad_container)
        val adLoader = AdLoader.Builder(this, nativeAdUnitId) // 広告ユニットID
            .forNativeAd { nativeAd ->
                val adView = layoutInflater.inflate(R.layout.ad_native_layout, null) as NativeAdView
                populateNativeAdView(nativeAd, adView)

                // フェードアニメーション
                if (adContainer.childCount > 0) {
                    val oldAdView = adContainer.getChildAt(0)

                    // 既存の広告をフェードアウト → 新しい広告をフェードイン
                    oldAdView.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            adContainer.removeAllViews()
                            adView.alpha = 0f
                            adContainer.addView(adView)

                            adView.animate()
                                .alpha(1f)
                                .setDuration(300)
                                .start()
                        }
                        .start()
                } else {
                    // 最初の広告表示（アニメーションなしまたはフェードイン）
                    adView.alpha = 0f
                    adContainer.removeAllViews()
                    adContainer.addView(adView)
                    adView.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }

                adContainer.visibility = View.VISIBLE  // ← 成功時に表示
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e("AdMob", "ネイティブ広告読み込み失敗: ${error.message}")
                    adContainer.visibility = View.GONE // ← 失敗時に非表示
                }

                override fun onAdLoaded() {
                    Log.d("AdMob", "ネイティブ広告読み込み成功")
                    // ※ forNativeAd内でも visibility を表示にしてるのでここでは省略可
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }


    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        // View の取得
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)

        // テキスト設定
        (adView.headlineView as TextView).text = nativeAd.headline
        (adView.bodyView as TextView).text = nativeAd.body
        (adView.callToActionView as Button).text = nativeAd.callToAction

        // アイコン画像を設定
        val icon = nativeAd.icon
        if (icon != null) {
            (adView.iconView as ImageView).setImageDrawable(icon.drawable)
            adView.iconView?.visibility = View.VISIBLE
        } else {
            adView.iconView?.visibility = View.GONE
        }

        // バインド
        adView.setNativeAd(nativeAd)
    }

    // reward AdMob
    fun loadRewardAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this, rewardAdUnitId, adRequest, object : RewardedAdLoadCallback() { // 広告ユニットID
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                Log.d("AdMob", "リワード広告読み込み完了")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e("AdMob", "リワード広告読み込み失敗: ${error.message}")
            }
        })
    }

    // 広告表示＆報酬検知
    fun showRewardAd(onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d("AdMob", "広告が閉じられたので再読み込みを行う")
                    rewardedAd = null
                    loadRewardAd() // ← 再読み込み
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e("AdMob", "広告表示失敗: ${adError.message}")
                    rewardedAd = null
                    loadRewardAd()
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d("AdMob", "広告が表示されました")
                }
            }

            // rewardItem を取得してログに出力
            ad.show(this) { rewardItem ->
                val amount = rewardItem.amount
                val type = rewardItem.type
                Log.d("AdMob", "リワード獲得！ amount=$amount, type=$type")

                // 実際の報酬処理
                onRewardEarned()
            }
        } else {
            Toast.makeText(this, "広告がまだ読み込まれていません", Toast.LENGTH_SHORT).show()
            loadRewardAd() // ← ★ 念のためここでも再読み込み
        }
    }
}
