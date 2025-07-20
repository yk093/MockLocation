package com.ykun.MockLocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import java.io.File

class MockLocationService : Service() {

    private val handler = Handler(Looper.getMainLooper()) // メインスレッド用のハンドラー
    private lateinit var fusedClient: FusedLocationProviderClient // モック位置を提供するクライアント

    private var route: List<Pair<Double, Double>> = listOf() // 経路（緯度・経度のリスト）
    private var index = 0 // 現在の位置インデックス

    private var speed = 10.0f // モック速度（m/s）
    private var accuracy = 5.0f // モック精度（m）
    private var intervalMillis = 1000L // 更新間隔（ms）

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel() // 通知チャネルを作成（Android O以降用）
        fusedClient = LocationServices.getFusedLocationProviderClient(this) // FusedLocationProviderClient の初期化

        // モックモードを有効にする（通常の位置情報と区別）
        if (checkPermission()) {
            fusedClient.setMockMode(true)
                .addOnSuccessListener { Log.d("MockGPS", "Mock mode enabled") }
                .addOnFailureListener { e -> Log.e("MockGPS", "Failed to enable mock mode: ${e.message}") }
        }

        route = loadRouteFromJson() // JSONファイルからルートを読み込む
        startMockLocationUpdates()  // モック位置情報の送信を開始
        startForegroundService()    // フォアグラウンドサービスとして開始（通知が必要）
    }

    // MainActivityのIntent を受け取る
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speedKmh = intent?.getFloatExtra("speed_kmh", 36f) ?: 36f
        speed = speedKmh / 3.6f // km/h → m/s に変換
        accuracy = intent?.getFloatExtra("accuracy", 5f) ?: 5f
        intervalMillis = intent?.getLongExtra("interval_ms", 1000L) ?: 1000L
        return START_STICKY
    }

    // JSONファイル（route.json）を読み込んで緯度経度のリストに変換
    private fun loadRouteFromJson(): List<Pair<Double, Double>> {
        return try {
            val file = File(applicationContext.filesDir, "route.json")
            val json = file.readText()
            val jsonArray = JSONArray(json)
            val result = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val lat = obj.getDouble("lat")
                val lng = obj.getDouble("lng")
                result.add(Pair(lat, lng))
            }
            result
        } catch (e: Exception) {
            Log.e("MockGPS", "JSON読み込み失敗", e)
            listOf()
        }
    }

    // 一定間隔でモック位置情報を送信する処理
    private fun startMockLocationUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                if (index >= route.size) index = 0 // ループ再開
                val (lat, lon) = route[index++]

                // Location オブジェクトを構築して設定（速度と精度を含む）
                val mockLocation = Location("fused").apply {
                    latitude = lat
                    longitude = lon
                    accuracy = this@MockLocationService.accuracy // 精度（メートル）
                    speed = this@MockLocationService.speed       // 速度（m/s）
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }

                // パーミッションがある場合のみ送信
                if (ContextCompat.checkSelfPermission(this@MockLocationService, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MockGPS", "Sending location: $lat, $lon (speed=$speed m/s, accuracy=$accuracy m)")

                    // FusedLocationProviderClient へモック位置を設定（速度・精度付き）
                    fusedClient.setMockLocation(mockLocation)
                        .addOnSuccessListener { Log.d("MockGPS", "Mock location set successfully") }
                        .addOnFailureListener { e -> Log.e("MockGPS", "Failed to set mock location", e) }
                }

                // 次の送信を予約
                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    // フォアグラウンド通知の構成と起動
    private fun startForegroundService() {
        val channelId = "mock_location_channel"
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mock GPS 実行中")
            .setContentText("モック位置を送信中...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)
    }

    // サービス破棄時のクリーンアップ処理
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null) // タイマー停止

        // モックモードを解除
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.setMockMode(false)
                .addOnSuccessListener { Log.d("MockGPS", "Mock mode disabled") }
                .addOnFailureListener { e -> Log.e("MockGPS", "Failed to disable mock mode: ${e.message}") }
        }

        // 通知を削除
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1)
        Log.d("MockGPS", "サービス終了")
    }

    // バインドは不要（null）
    override fun onBind(intent: Intent?): IBinder? = null

    // Android O以降の通知チャネル作成
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mock_location_channel",
                "Mock Location Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Mock GPS通知用"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // パーミッション確認
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
