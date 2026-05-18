package com.example.hou

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.skt.tmap.overlay.TMapMarkerItem
import com.skt.tmap.TMapPoint
import com.skt.tmap.TMapView
import kotlin.math.abs
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 위치 업데이트 / 방향 센서 / 내 위치 마커를 담당합니다.
 */
class LocationSensorManager(
    private val activity: MainActivity,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val sensorManager: SensorManager,
    private val tMapView: TMapView,
    private val onLocationUpdate: (TMapPoint) -> Unit,
) {
    // ──────────────────────────────────────────
    // 센서 데이터
    // ──────────────────────────────────────────
    private val gravity     = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    var currentAzimuth    = 0f
    private var lastMarkerAzimuth = 0f

    // ──────────────────────────────────────────
// 현재 위치 (에뮬레이터용 방어 코드 포함)
// ──────────────────────────────────────────
    private var _currentLocation: TMapPoint? = null
    var currentLocation: TMapPoint?
        get() {
            val loc = _currentLocation ?: return null

            // 위도 33~43, 경도 124~132 범위를 벗어나면 대한민국 지공간이 아님 (에뮬레이터 미국 좌표 등)
            return if (loc.latitude < 33.0 || loc.latitude > 43.0 || loc.longitude < 124.0 || loc.longitude > 132.0) {
                // 개발/테스트용 강남역 좌표로 대체 반환
                TMapPoint(37.497952, 127.027619)
            } else {
                loc
            }
        }
        set(value) {
            _currentLocation = value
        }
    // ──────────────────────────────────────────
    // LocationCallback
    // ──────────────────────────────────────────
    val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                val pt = TMapPoint(loc.latitude, loc.longitude)
                currentLocation = pt
                updateMyLocationMarker()
                onLocationUpdate(pt)
            }
        }
    }

    // ──────────────────────────────────────────
    // 센서 리스너
    // ──────────────────────────────────────────
    val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER)
                gravity.indices.forEach { gravity[it] = event.values[it] }
            if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
                geomagnetic.indices.forEach { geomagnetic[it] = event.values[it] }

            val r = FloatArray(9); val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val ori = FloatArray(3)
                SensorManager.getOrientation(r, ori)
                currentAzimuth = (Math.toDegrees(ori[0].toDouble()).toFloat() + 360) % 360
                if (abs(currentAzimuth - lastMarkerAzimuth) > 5f) {
                    lastMarkerAzimuth = currentAzimuth
                    updateMyLocationMarker()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ──────────────────────────────────────────
// 초기 위치 가져오기 (보완된 버전)
// ──────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                val pt = TMapPoint(loc.latitude, loc.longitude)
                currentLocation = pt
                updateMyLocationMarker()

                // 기존 방어 코드가 동작하여 대한민국 좌표(또는 강남역)로 중심점이 잡힙니다.
                val finalLat = currentLocation?.latitude ?: loc.latitude
                val finalLng = currentLocation?.longitude ?: loc.longitude

                tMapView.post {
                    tMapView.setCenterPoint(finalLat, finalLng)
                    tMapView.zoomLevel = 15
                }
            } else {
                // lastLocation이 null이면 실시간으로 한 번 받아오기
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                    .setMaxUpdates(1).build()

                // 콜백을 약간 수정하여 받아오는 즉시 맵 중심점도 이동되도록 보완할 수 있습니다.
                fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let { newLoc ->
                            val pt = TMapPoint(newLoc.latitude, newLoc.longitude)
                            currentLocation = pt
                            updateMyLocationMarker()

                            val finalLat = currentLocation?.latitude ?: newLoc.latitude
                            val finalLng = currentLocation?.longitude ?: newLoc.longitude

                            tMapView.post {
                                tMapView.setCenterPoint(finalLat, finalLng)
                                tMapView.zoomLevel = 15
                            }
                        }
                    }
                }, Looper.getMainLooper())
            }
        }
    }
    // ──────────────────────────────────────────
    // 실제 주행 위치 업데이트 시작/종료
    // ──────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun startRealNav() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000).build()
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }
    }

    fun stopRealNav() {
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────
    // 센서 등록/해제
    // ──────────────────────────────────────────
    fun registerSensors(accelerometer: Sensor?, magnetometer: Sensor?) {
        accelerometer?.let { sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let  { sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI) }
        if (!breathingAnimator.isRunning) breathingAnimator.start()
    }

    fun unregisterSensors() {
        sensorManager.unregisterListener(sensorEventListener)
        breathingAnimator.cancel()
    }

    // ──────────────────────────────────────────
    // 마커 업데이트
    // ──────────────────────────────────────────
    // 한 번 생성한 마커를 재사용 — remove+add 반복 시 깜빡임 발생하므로 금지



    private var myLocationMarker: TMapMarkerItem? = null

    private var breathingProgress = 0f
    private val breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            breathingProgress = anim.animatedValue as Float
            updateMyLocationMarker()
        }
    }
    fun updateMyLocationMarker() {
        val pt = currentLocation ?: return
        val bitmap = drawLocationMarker(currentAzimuth, breathingProgress)

        val existing = myLocationMarker
        if (existing != null) {
            existing.tMapPoint = pt
            existing.icon = bitmap
            tMapView.updateTMapMarkerItem(existing)
        } else {
            val marker = TMapMarkerItem().apply {
                id = "my_location"; tMapPoint = pt; name = "내 위치"
                icon = bitmap; setPosition(0.5f, 0.5f)
            }
            myLocationMarker = marker
            tMapView.addTMapMarkerItem(marker)
        }
        tMapView.postInvalidate()
    }

    private fun drawLocationMarker(azimuthDeg: Float, breathProgress: Float): Bitmap {
        val size = 150          // 전체 비트맵 크기 (px)
        val cx = size / 2f
        val cy = size / 2f
        val coreRadius = 22f    // 파란 중심 원 반지름

        // 브리딩: 0→1→0 사인 커브로 자연스럽게
        val breathSin = sin(breathProgress * Math.PI).toFloat()  // 0→1→0
        val breathRadius = coreRadius + 14f + breathSin * 20f    // 32 ~ 52px
        val breathAlpha = ((1f - breathProgress) * 160).toInt().coerceIn(30, 160)

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ① 브리딩 원 (반투명 파란색)
        val breathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(breathAlpha, 25, 118, 210)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, breathRadius, breathPaint)

        // ② 흰색 테두리 링
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, coreRadius + 3f, ringPaint)

        // ③ 파란 중심 원
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 118, 210)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // ④ 방향 삼각형 (방위각 방향으로 회전)
        val arrowLen = coreRadius + 25f   // 삼각형 끝(tip)까지 거리
        val arrowHalfBase = 12f
        val baseOffset = coreRadius + 8f  // 밑변 시작점을 원 바깥으로 밀어냄
        val angleRad = Math.toRadians(azimuthDeg.toDouble())

        // tip: 방위각 방향으로 arrowLen만큼
        val tipX = cx + (arrowLen * sin(angleRad)).toFloat()
        val tipY = cy - (arrowLen * cos(angleRad)).toFloat()
        // 밑변 두 점: baseOffset만큼 나간 지점에서 수직으로 arrowHalfBase씩
        val leftX  = cx + (baseOffset * sin(angleRad)).toFloat() + (arrowHalfBase * cos(angleRad)).toFloat()
        val leftY  = cy - (baseOffset * cos(angleRad)).toFloat() + (arrowHalfBase * sin(angleRad)).toFloat()
        val rightX = cx + (baseOffset * sin(angleRad)).toFloat() - (arrowHalfBase * cos(angleRad)).toFloat()
        val rightY = cy - (baseOffset * cos(angleRad)).toFloat() - (arrowHalfBase * sin(angleRad)).toFloat()

        val arrowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 118, 210)
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, arrowPaint)

        return bmp
    }
}
