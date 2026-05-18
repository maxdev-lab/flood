package com.example.hou

import android.Manifest
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import android.speech.tts.TextToSpeech
import com.example.hou.data.model.PoiItem
import com.example.hou.data.network.RetrofitClient
import com.google.android.gms.location.LocationServices
import com.skt.tmap.TMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * MainActivity: 각 매니저를 초기화하고 Compose UI에 상태를 전달하는 조율자 역할.
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "HOU"
        private const val MOCK_SPEED_MS = 2000L
        const val MY_LOCATION_LABEL = "내 위치"
    }

    // ──────────────────────────────────────────
    // 매니저
    // ──────────────────────────────────────────
    private lateinit var navManager:          NavigationManager
    private lateinit var locationManager:     LocationSensorManager
    private lateinit var routeManager:        RouteManager
    private lateinit var historyManager:      SearchHistoryManager
    private lateinit var floodHeatmapManager: FloodHeatmapManager

    // ──────────────────────────────────────────
    // 시스템 서비스 / SDK
    // ──────────────────────────────────────────
    private lateinit var tts:           TextToSpeech
    private lateinit var sensorManager: SensorManager
    private lateinit var tMapView:      TMapView

    // ── 침수 데이터 (현재는 더미, 서버 연동 시 교체) ──
    private var currentFloodDataset: FloodDataset = FloodDataset(emptyList())

    // ──────────────────────────────────────────
    // 내비게이션 UI 상태
    // ──────────────────────────────────────────
    private var uiIsRouteReady   by mutableStateOf(false)
    private var uiRouteSummary   by mutableStateOf("")
    private var uiIsNavigating   by mutableStateOf(false)
    private var uiIsMockMode     by mutableStateOf(true)
    private var uiInstruction    by mutableStateOf("어디로 갈까요?")
    private var uiRemainDistance by mutableStateOf("")

    // ──────────────────────────────────────────
    // 침수 히트맵 UI 상태
    // ──────────────────────────────────────────
    private var uiIsFloodVisible  by mutableStateOf(false)
    private var uiFloodBannerState by mutableStateOf(FloodBannerState.NONE)

    // ──────────────────────────────────────────
    // 검색 UI 상태
    // ──────────────────────────────────────────
    private var uiShowSearchScreen by mutableStateOf(false)
    private var uiOriginQuery      by mutableStateOf("")
    private var uiDestQuery        by mutableStateOf("")
    private var uiActiveField      by mutableStateOf(SearchField.DEST)
    private var uiSearchResults    by mutableStateOf<List<PoiItem>>(emptyList())
    private var uiSearchHistory    by mutableStateOf<List<String>>(emptyList())
    private var uiIsSearching      by mutableStateOf(false)

    private var originPoi: PoiItem? = null

    // ──────────────────────────────────────────
    // 가상 주행
    // ──────────────────────────────────────────
    private var mockNavJob: Job? = null

    // ──────────────────────────────────────────
    // 실시간 검색 디바운스
    // ──────────────────────────────────────────
    private var searchJob: Job? = null

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 상태표시줄 아이콘/글씨를 어둡게(검은색)로 설정
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true   // 상태표시줄 아이콘/시각 검은색
        }

        tts = TextToSpeech(this, this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        historyManager = SearchHistoryManager(this)
        setupTMapView()
        initManagers()
        registerSensors()
        loadFloodData()
        startFloodRefreshLoop()


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        setContent {
            // ── 뒤로가기 처리 ──────────────────────────────────────────
            var backPressedOnce by remember { mutableStateOf(false) }

            androidx.activity.compose.BackHandler(enabled = true) {
                when {
                    // 1) 검색창 열려 있으면 → 메인 지도로
                    uiShowSearchScreen -> {
                        uiShowSearchScreen = false
                    }
                    // 2) 경로 준비 or 안내 중 → 중단 확인 다이얼로그
                    uiIsRouteReady || uiIsNavigating -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("경로 탐색 중단")
                            .setMessage("경로 탐색을 중단하시겠습니까?")
                            .setPositiveButton("예") { d, _ ->
                                if (uiIsNavigating) stopNavigation() else clearRoute()
                                d.dismiss()
                            }
                            .setNegativeButton("아니오") { d, _ -> d.dismiss() }
                            .show()
                    }
                    // 3) 메인 지도 → 한 번 더 누르면 종료
                    else -> {
                        if (backPressedOnce) {
                            finish()
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, "한 번 더 누르면 종료됩니다.", Toast.LENGTH_SHORT).show()
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(2000)
                                backPressedOnce = false
                            }
                        }
                    }
                }
            }

            NavScreen(
                tMapView          = tMapView,
                isNavigating      = uiIsNavigating,
                isRouteReady      = uiIsRouteReady,
                isMockMode        = uiIsMockMode,
                instruction       = uiInstruction,
                remainDistance    = uiRemainDistance,
                routeSummary      = uiRouteSummary,
                // 침수 히트맵
                isFloodVisible    = uiIsFloodVisible,
                onFloodToggle     = { toggleFloodHeatmap() },
                // ★ 침수 경고 배너
                floodBannerState  = uiFloodBannerState,
                // 검색
                showSearchScreen  = uiShowSearchScreen,
                originQuery       = uiOriginQuery,
                destQuery         = uiDestQuery,
                activeField       = uiActiveField,
                searchResults     = uiSearchResults,
                searchHistory     = uiSearchHistory,
                isSearching       = uiIsSearching,
                // 검색 이벤트
                onSearchBarClick  = { openSearchScreen() },
                onOriginChange    = { onQueryChange(SearchField.ORIGIN, it) },
                onDestChange      = { onQueryChange(SearchField.DEST, it) },
                onFieldFocus      = { uiActiveField = it; uiSearchResults = emptyList() },
                onSwapFields      = { swapFields() },
                onUseMyLocation   = { useMyLocation() },
                onPoiSelected     = { poi, field -> onPoiSelected(poi, field) },
                onHistorySelected = { onHistorySelected(it) },
                onFindRoute       = { findRouteFromSearch() },
                onDeleteHistory   = { historyManager.removeHistory(it); refreshHistory() },
                onClearHistory    = { historyManager.clearAll(); refreshHistory() },
                onSearchDismiss   = { uiShowSearchScreen = false },
                // 내비게이션 이벤트
                onStartNavigation = { startNavigation() },
                onStopNavigation  = { showStopDialog() },
                onClearRoute      = { clearRoute() },
                onMockModeChange  = { uiIsMockMode = it },
                onFetchLocation   = { locationManager.fetchCurrentLocation() },
            )
        }
    }

    override fun onDestroy() {
        if (navManager.isTtsReady) { tts.stop(); tts.shutdown() }
        locationManager.stopRealNav()
        locationManager.unregisterSensors()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(0.85f)
            navManager.isTtsReady = true
        }
    }

    // ──────────────────────────────────────────
    // 초기화
    // ──────────────────────────────────────────
    private fun setupTMapView() {
        tMapView = TMapView(this).apply {
            setSKTMapApiKey(BuildConfig.TMAP_APP_KEY)
            setOnApiKeyListenerCallback(object : TMapView.OnApiKeyListenerCallback {
                override fun onSKTMapApikeySucceed() {
                    Log.d(TAG, "TMap 인증 성공")
                }
                override fun onSKTMapApikeyFailed(msg: String?) {
                    Log.e(TAG, "인증 실패: $msg")
                }
            })
            setOnMapReadyListener(object : TMapView.OnMapReadyListener {
                override fun onMapReady() {
                    if (::locationManager.isInitialized) {
                        locationManager.fetchCurrentLocation()
                    }
                }
            })
        }
    }

    private fun initManagers() {
        navManager = NavigationManager(
            tts = tts,
            scope = lifecycleScope,
            onInstructionChanged = { uiInstruction = it },
            onReroute = { curLoc ->
                routeManager.destinationPoint?.let {
                    routeManager.findFloodAvoidRoute(curLoc, it, isRerouting = true)
                }
            },
            onArrived = { stopNavigation() },
            onRemainDistanceChanged = { uiRemainDistance = it },
            onPolylineTrim = { routeManager.drawPolyline() }
        )

        locationManager = LocationSensorManager(
            activity = this,
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this),
            sensorManager = sensorManager,
            tMapView = tMapView,
            onLocationUpdate = { pt ->
                if (uiIsNavigating) {
                    if (!uiIsMockMode) {
                        tMapView.setCenterPoint(pt.latitude, pt.longitude)
                    }

                    navManager.checkNavProgress(
                        curLoc = pt,
                        allRoutePoints = routeManager.allRoutePoints,
                        upcomingSteps = routeManager.upcomingSteps,
                        destinationPoint = routeManager.destinationPoint,
                        currentAzimuth = locationManager.currentAzimuth,
                        isMockMode = uiIsMockMode
                    )
                } else {
                    tMapView.setRotation(0f)
                }
            }
        )

        routeManager = RouteManager(
            context = this,
            scope = lifecycleScope,
            tMapView = tMapView,
            getPinBitmap = { getBitmapFromVector(it) },
            onRouteReady = { summary ->
                uiRouteSummary = summary
                uiIsRouteReady = true
            },
            onRerouteComplete = {
                navManager.isRerouting = false
                navManager.lastStraightFeedbackTime = System.currentTimeMillis()
                navManager.announce("새로운 경로를 찾았습니다. 안내를 계속합니다.",
                    NavigationManager.REROUTE_CONFIRM_COUNT)
                if (uiIsMockMode) startMockNav()
            },
            onSearchResults = { list ->
                uiSearchResults = list
                uiIsSearching = false
            },
            onFloodBanner = { state ->
                uiFloodBannerState = state
            }
        )

        floodHeatmapManager = FloodHeatmapManager(tMapView)
    }

    private fun registerSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer  = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        locationManager.registerSensors(accelerometer, magnetometer)
    }

    // ──────────────────────────────────────────
    // 침수 히트맵 & 데이터
    // ──────────────────────────────────────────

    /**
     * 침수 데이터를 로드합니다.
     * 현재는 getMockFloodData()를 사용하며, 서버 연동 시 Retrofit 호출로 교체하면 됩니다.
     */
    private fun loadFloodData() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.floodService.getFloodCells()
                }
                if (response.isSuccessful && response.body()?.cells?.isNotEmpty() == true) {
                    applyFloodDataset(response.body()!!)
                    Log.d(TAG, "서버 침수 데이터 로드: ${response.body()!!.cells.size}개 셀")
                } else {
                    // 서버 응답 없음 or 빈 데이터 → 더미 유지
                    Log.w(TAG, "서버 데이터 없음 → 더미 사용")
                    applyFloodDataset(getMockFloodData())
                }
            } catch (e: Exception) {
                // 서버 미실행·오프라인 → 더미로 fallback (앱 정상 동작 유지)
                Log.e(TAG, "침수 데이터 로드 실패: ${e.message} → 더미 사용")
                applyFloodDataset(getMockFloodData())
            }
        }
    }

    private fun startFloodRefreshLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(10_000L)      // 백엔드 MAPE-K 10초 주기와 동일
                loadFloodData()
            }
        }
    }

    /** 침수 데이터셋을 히트맵 매니저와 경로 매니저 양쪽에 전달합니다. */
    private fun applyFloodDataset(dataset: FloodDataset) {
        currentFloodDataset = dataset
        routeManager.floodDataset = dataset
        navManager.currentFloodDataset = dataset
        if (uiIsFloodVisible) floodHeatmapManager.show(dataset)
    }

    /** 💧 FAB 클릭 → 히트맵 토글 */
    private fun toggleFloodHeatmap() {
        floodHeatmapManager.toggle(currentFloodDataset)
        uiIsFloodVisible = floodHeatmapManager.isVisible
    }

    // ──────────────────────────────────────────
    // 검색 화면 로직
    // ──────────────────────────────────────────
    private fun openSearchScreen() {
        refreshHistory()
        uiSearchResults = emptyList()
        uiActiveField = SearchField.DEST
        uiShowSearchScreen = true

        // 출발지가 비어 있으면 현재 위치 주소를 자동으로 채움
        if (uiOriginQuery.isBlank()) {
            val loc = locationManager.currentLocation ?: return
            uiOriginQuery = "현재 위치 확인 중…"
            lifecycleScope.launch(Dispatchers.IO) {
                val address = try {
                    val resp = RetrofitClient.tmapService.reverseGeocode(
                        appKey = BuildConfig.TMAP_APP_KEY,
                        lon    = loc.longitude.toString(),
                        lat    = loc.latitude.toString()
                    )
                    resp.body()?.addressInfo?.toDisplayAddress() ?: "현재 위치"
                } catch (e: Exception) {
                    "현재 위치"
                }
                withContext(Dispatchers.Main) {
                    uiOriginQuery = address
                }
            }
        }
    }

    private fun refreshHistory() {
        uiSearchHistory = historyManager.getHistory()
    }

    private fun onQueryChange(field: SearchField, text: String) {
        if (field == SearchField.ORIGIN) {
            uiOriginQuery = text
            originPoi = null
        } else {
            uiDestQuery = text
        }
        uiActiveField = field
        uiSearchResults = emptyList()

        searchJob?.cancel()
        if (text.isBlank()) {
            uiIsSearching = false
            return
        }
        uiIsSearching = true
        searchJob = lifecycleScope.launch {
            delay(300)
            routeManager.searchPOI(text)
        }
    }

    private fun onPoiSelected(poi: PoiItem, field: SearchField) {
        historyManager.addHistory(poi.name)
        refreshHistory()
        uiSearchResults = emptyList()
        uiIsSearching = false
        if (field == SearchField.ORIGIN) {
            originPoi = poi
            uiOriginQuery = poi.name
            if (uiDestQuery.isBlank()) uiActiveField = SearchField.DEST
        } else {
            uiDestQuery = poi.name
            if (uiOriginQuery.isBlank()) uiActiveField = SearchField.ORIGIN
        }
    }

    private fun onHistorySelected(query: String) {
        if (uiActiveField == SearchField.ORIGIN) {
            uiOriginQuery = query
            originPoi = null
        } else {
            uiDestQuery = query
        }
        uiSearchResults = emptyList()
        uiIsSearching = false
    }

    private fun swapFields() {
        val tmpQuery = uiOriginQuery
        uiOriginQuery = uiDestQuery
        uiDestQuery = tmpQuery
        originPoi = null
        uiSearchResults = emptyList()
        uiIsSearching = false
        searchJob?.cancel()
    }

    private fun useMyLocation() {
        val loc = locationManager.currentLocation
        if (loc == null) {
            Toast.makeText(this, "현재 위치를 가져오는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val targetField = uiActiveField
        originPoi = null
        uiSearchResults = emptyList()
        uiIsSearching = false

        if (targetField == SearchField.ORIGIN) uiOriginQuery = "현재 위치 확인 중…"
        else uiDestQuery = "현재 위치 확인 중…"

        lifecycleScope.launch(Dispatchers.IO) {
            val address = try {
                val resp = RetrofitClient.tmapService.reverseGeocode(
                    appKey = BuildConfig.TMAP_APP_KEY,
                    lon    = loc.longitude.toString(),
                    lat    = loc.latitude.toString()
                )
                resp.body()?.addressInfo?.toDisplayAddress() ?: "현재 위치"
            } catch (e: Exception) {
                "현재 위치"
            }
            withContext(Dispatchers.Main) {
                if (targetField == SearchField.ORIGIN) uiOriginQuery = address
                else uiDestQuery = address
            }
        }
    }

    private fun findRouteFromSearch() {
        if (uiOriginQuery.isBlank() || uiDestQuery.isBlank()) return

        uiShowSearchScreen = false

        // 출발지 좌표 결정
        val startPoint: com.skt.tmap.TMapPoint? = when {
            // 1) 출발지 POI 명시적 선택
            originPoi != null -> {
                val lat = originPoi!!.noorLat.toDoubleOrNull()
                val lon = originPoi!!.noorLon.toDoubleOrNull()
                if (lat != null && lon != null) com.skt.tmap.TMapPoint(lat, lon)
                else locationManager.currentLocation
            }
            // 2) "현재 위치" 텍스트 → GPS 사용
            uiOriginQuery.contains("현재 위치") || uiOriginQuery.contains("내 위치") -> {
                locationManager.currentLocation
            }
            // 3) 텍스트 입력 → POI 검색해서 좌표 가져오기
            else -> null  // 아래에서 비동기 검색
        }

        if (startPoint != null) {
            // 바로 경로 탐색
            routeManager.searchAndRoute(uiDestQuery, startPoint)
        } else {
            // 출발지 텍스트로 POI 검색 후 경로 탐색
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val resp = RetrofitClient.tmapService.searchPOI(
                        appKey  = BuildConfig.TMAP_APP_KEY,
                        keyword = uiOriginQuery
                    )
                    val poi = resp.body()?.searchPoiInfo?.pois?.poiList?.firstOrNull()
                    withContext(Dispatchers.Main) {
                        if (poi != null) {
                            val lat = poi.noorLat.toDoubleOrNull()
                            val lon = poi.noorLon.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                val start = com.skt.tmap.TMapPoint(lat, lon)
                                routeManager.searchAndRoute(uiDestQuery, start)
                            } else {
                                Toast.makeText(this@MainActivity, "출발지 좌표를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "출발지를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "출발지 검색 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // 권한
    // ──────────────────────────────────────────
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            locationManager.fetchCurrentLocation()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────
    // 안내 시작 / 종료
    // ──────────────────────────────────────────
    private fun startNavigation() {
        uiIsNavigating = true
        uiIsRouteReady = false
        navManager.reset()
        routeManager.prepareNavigation()
        navManager.announce(
            if (uiIsMockMode) "가상 주행 안내를 시작합니다." else "안내를 시작합니다.",
            5
        )
        if (uiIsMockMode) startMockNav() else locationManager.startRealNav()
        navManager.startStraightFeedback(
            isNavigating     = { uiIsNavigating },
            currentLocation  = { locationManager.currentLocation },
            upcomingSteps    = { routeManager.upcomingSteps },
            destinationPoint = { routeManager.destinationPoint }
        )
    }

    private fun stopNavigation() {
        uiIsNavigating = false
        navManager.isRerouting = false
        mockNavJob?.cancel()
        navManager.stopStraightFeedback()
        locationManager.stopRealNav()
        tMapView.setRotation(0f)
        navManager.announce("경로 안내를 종료합니다.", 5)
        clearRoute()
    }

    private fun clearRoute() {
        routeManager.clearRoute(locationManager.currentLocation)
        uiIsRouteReady = false
        uiFloodBannerState = FloodBannerState.NONE
        uiOriginQuery = ""
        uiDestQuery = ""
        originPoi = null
    }

    private fun showStopDialog() {
        AlertDialog.Builder(this)
            .setTitle("안내 종료")
            .setMessage("경로 안내를 종료하시겠습니까?")
            .setPositiveButton("예") { d, _ -> stopNavigation(); d.dismiss() }
            .setNegativeButton("아니오") { d, _ -> d.dismiss() }
            .show()
    }

    // ──────────────────────────────────────────
    // 가상 주행
    // ──────────────────────────────────────────
    private fun startMockNav() {
        mockNavJob?.cancel()
        mockNavJob = lifecycleScope.launch(Dispatchers.Main) {
            for (pt in routeManager.allRoutePoints.toList()) {
                if (!uiIsNavigating) break
                locationManager.currentLocation = pt
                locationManager.updateMyLocationMarker()
                tMapView.setCenterPoint(pt.latitude, pt.longitude)
                navManager.checkNavProgress(
                    curLoc           = pt,
                    allRoutePoints   = routeManager.allRoutePoints,
                    upcomingSteps    = routeManager.upcomingSteps,
                    destinationPoint = routeManager.destinationPoint,
                    currentAzimuth   = locationManager.currentAzimuth,
                    isMockMode       = true
                )
                delay(MOCK_SPEED_MS)
            }
        }
    }

    // ──────────────────────────────────────────
    // 유틸
    // ──────────────────────────────────────────
    private fun getBitmapFromVector(drawableId: Int): Bitmap {
        val d = ContextCompat.getDrawable(this, drawableId)!!
        val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        return bmp
    }
}
