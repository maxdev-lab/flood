package com.example.hou

import android.content.Context
import android.graphics.Color as AndroidColor
import android.widget.Toast
import com.example.hou.BuildConfig
import com.example.hou.data.model.PoiItem
import com.example.hou.data.model.RouteFeature
import com.example.hou.data.model.RouteStep
import com.example.hou.data.network.RetrofitClient
import com.skt.tmap.overlay.TMapMarkerItem
import com.skt.tmap.TMapPoint
import com.skt.tmap.overlay.TMapPolyLine
import com.skt.tmap.TMapView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 침수 배너 표시 상태.
 * NONE: 배너 없음 / DETOUR_ACTIVE: 우회 성공 / UNAVOIDABLE: 우회 불가 경고
 */
enum class FloodBannerState { NONE, DETOUR_ACTIVE, UNAVOIDABLE }

/**
 * POI 검색, 경로 탐색, 경로 렌더링을 담당합니다.
 *
 * 침수 우회 경로 탐색 흐름:
 *   1. FloodRouter에서 위험 블록 측면의 앵커포인트 계산
 *   2. 출발→앵커, 앵커→도착 구간을 TMap API에 각각 병렬 요청
 *   3. 응답을 이어붙여 최종 경로 생성
 *   4. 이어붙인 경로가 여전히 위험구역 통과 시 UNAVOIDABLE 배너 표시
 */
class RouteManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tMapView: TMapView,
    private val getPinBitmap: (Int) -> android.graphics.Bitmap,
    private val onRouteReady: (summary: String) -> Unit,
    private val onRerouteComplete: () -> Unit,
    private val onSearchResults: (List<PoiItem>) -> Unit,
    private val onFloodBanner: ((FloodBannerState) -> Unit)? = null,
) {
    // ──────────────────────────────────────────
    // 공유 데이터
    // ──────────────────────────────────────────
    val allRoutePoints = mutableListOf<TMapPoint>()
    val routeSteps     = mutableListOf<RouteStep>()
    val upcomingSteps  = mutableListOf<RouteStep>()
    var destinationPoint: TMapPoint? = null
    var destinationName: String = ""

    var floodDataset: FloodDataset = FloodDataset(emptyList())

    // ──────────────────────────────────────────
    // POI 검색
    // ──────────────────────────────────────────
    fun searchPOI(query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.tmapService.searchPOI(
                    appKey  = BuildConfig.TMAP_APP_KEY,
                    keyword = query
                )
                withContext(Dispatchers.Main) {
                    val list = resp.body()?.searchPoiInfo?.pois?.poiList
                    if (resp.isSuccessful && !list.isNullOrEmpty()) {
                        onSearchResults(list)
                    } else {
                        onSearchResults(emptyList())
                        Toast.makeText(context, "검색 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onSearchResults(emptyList())
                    Toast.makeText(context, "네트워크 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // 목적지 설정
    // ──────────────────────────────────────────
    fun setDestination(item: PoiItem, currentLocation: TMapPoint?) {
        val lat = item.noorLat.toDoubleOrNull() ?: return
        val lon = item.noorLon.toDoubleOrNull() ?: return
        destinationPoint = TMapPoint(lat, lon)
        destinationName = item.name

        tMapView.removeAllTMapPolyLine()
        tMapView.removeTMapMarkerItem("destination")

        val marker = TMapMarkerItem().apply {
            tMapPoint = destinationPoint
            name = item.name
            icon = getPinBitmap(R.drawable.ic_pin)
            setPosition(0.5f, 1.0f)
        }
        marker.id = "destination"
        tMapView.addTMapMarkerItem(marker)
        tMapView.setCenterPoint(lat, lon)
        tMapView.zoomLevel = 15

        currentLocation?.let {
            findFloodAvoidRoute(it, destinationPoint!!)
        }
    }

    // ──────────────────────────────────────────
    // 기본 경로 탐색 (우회 없음, 내부 위임용)
    // ──────────────────────────────────────────
    fun findRoute(start: TMapPoint, end: TMapPoint, isRerouting: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.tmapService.getCarRoute(
                    appKey = BuildConfig.TMAP_APP_KEY,
                    body   = buildRouteBody(start, end)
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful && resp.body() != null) {
                        applyRouteFeatures(resp.body()!!.features, isRerouting,
                            bannerState = FloodBannerState.NONE)
                    } else {
                        Toast.makeText(context, "경로 탐색에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "네트워크 연결이 불안정합니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // 침수 우회 경로 탐색
    // ──────────────────────────────────────────
    fun findFloodAvoidRoute(
        start: TMapPoint,
        end: TMapPoint,
        isRerouting: Boolean = false
    ) {
        val router = FloodRouter(floodDataset)

        // ── 0단계: 우회 불가 판단 ──────────────────────────────────────
        if (router.isDestinationInDanger(end) || router.isBypassImpossible(start, end)) {
            onFloodBanner?.invoke(FloodBannerState.UNAVOIDABLE)
            scope.launch(Dispatchers.IO) {
                try {
                    val resp = RetrofitClient.tmapService.getCarRoute(
                        appKey = BuildConfig.TMAP_APP_KEY,
                        body   = buildRouteBody(start, end)
                    )
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful && resp.body() != null) {
                            applyRouteFeatures(resp.body()!!.features, isRerouting,
                                bannerState = FloodBannerState.UNAVOIDABLE)
                        } else {
                            Toast.makeText(context, "경로 탐색에 실패했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "네트워크 연결이 불안정합니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        // ── 1단계: 앵커포인트 계산 (0개 / 1개 / 2개) ─────────────────
        val anchors = router.findBypassAnchors(start, end)

        if (anchors.isEmpty()) {
            // 위험구역 없음 → 정상 경로
            findRoute(start, end, isRerouting)
            return
        }

        // ── 2단계: 구간별 병렬 탐색 ──────────────────────────────────
        // anchors = [A1] → 2구간: start→A1, A1→end
        // anchors = [A1, A2] → 3구간: start→A1, A1→A2, A2→end
        val waypoints = listOf(start) + anchors + listOf(end)

        scope.launch(Dispatchers.IO) {
            try {
                val deferreds = waypoints.zipWithNext().map { (from, to) ->
                    async {
                        RetrofitClient.tmapService.getCarRoute(
                            appKey = BuildConfig.TMAP_APP_KEY,
                            body   = buildRouteBody(from, to)
                        )
                    }
                }
                val responses = deferreds.map { it.await() }

                withContext(Dispatchers.Main) {
                    val bodies = responses.map { if (it.isSuccessful) it.body() else null }

                    if (bodies.any { it == null }) {
                        Toast.makeText(context, "우회 경로 탐색에 실패했습니다. 일반 경로로 안내합니다.", Toast.LENGTH_SHORT).show()
                        findRoute(start, end, isRerouting)
                        return@withContext
                    }

                    stitchMultiAndApply(
                        featuresList = bodies.map { it!!.features },
                        router       = router,
                        isRerouting  = isRerouting
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "네트워크 연결이 불안정합니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // 구간 이어붙이기 및 경로 적용
    // ──────────────────────────────────────────
    private fun stitchMultiAndApply(
        featuresList: List<List<RouteFeature>>,
        router: FloodRouter,
        isRerouting: Boolean
    ) {
        // 각 구간 포인트/스텝 추출, 구간 경계 중복 포인트 제거
        var pointOffset = 0
        val stitchedPoints = mutableListOf<TMapPoint>()
        val stitchedSteps  = mutableListOf<RouteStep>()

        featuresList.forEachIndexed { idx, features ->
            val pts   = extractPoints(features)
            val steps = extractSteps(features, stepIndexOffset = pointOffset)
            if (idx == 0) {
                stitchedPoints.addAll(pts)
            } else {
                stitchedPoints.addAll(pts.drop(1))  // 앞 구간 마지막 == 이 구간 첫 포인트
            }
            stitchedSteps.addAll(steps)
            pointOffset += pts.size
        }

        val totalTime = featuresList.sumOf { extractTotalTime(it) }
        val totalDist = featuresList.sumOf { extractTotalDistance(it) }

        val finalBanner = if (router.isRouteInDanger(stitchedPoints)) {
            FloodBannerState.UNAVOIDABLE
        } else {
            FloodBannerState.DETOUR_ACTIVE
        }

        allRoutePoints.clear()
        allRoutePoints.addAll(stitchedPoints)
        routeSteps.clear()
        routeSteps.addAll(stitchedSteps)

        onFloodBanner?.invoke(finalBanner)
        drawPolyline()

        if (isRerouting) {
            upcomingSteps.clear()
            upcomingSteps.addAll(routeSteps)
            onRerouteComplete()
        } else {
            val suffix = if (finalBanner == FloodBannerState.DETOUR_ACTIVE) " 🔀 우회 경로" else ""
            onRouteReady("자동차 ${totalTime / 60}분 (%.1fkm)$suffix".format(totalDist / 1000.0))
        }
    }

    // ──────────────────────────────────────────
    // RouteFeature → 폴리라인 포인트 추출
    // ──────────────────────────────────────────
    private fun extractPoints(features: List<RouteFeature>): List<TMapPoint> {
        val pts = mutableListOf<TMapPoint>()
        features.forEach { f ->
            if (f.geometry.type == "LineString") {
                val arr = f.geometry.coordinates.asJsonArray
                for (i in 0 until arr.size()) {
                    val c = arr[i].asJsonArray
                    pts.add(TMapPoint(c[1].asDouble, c[0].asDouble))
                }
            }
        }
        return pts
    }

    // ──────────────────────────────────────────
    // RouteFeature → 방향 안내 스텝 추출
    // stepIndexOffset: 이전 구간 포인트 수를 더해 전체 경로에서의 인덱스 연속성 보장
    // ──────────────────────────────────────────
    private fun extractSteps(
        features: List<RouteFeature>,
        stepIndexOffset: Int
    ): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()
        features.forEach { f ->
            if (f.geometry.type == "Point") {
                val c    = f.geometry.coordinates.asJsonArray
                val props = f.properties
                val desc = props.description
                val turn = props.turnType
                val idx  = props.pointIndex
                if (desc != null && turn != null && idx != null) {
                    val isDir = desc.contains("좌") || desc.contains("우") ||
                            desc.contains("회전") || desc.contains("출발") ||
                            desc.contains("도착") || desc.contains("직진") ||
                            desc.contains("고속") || desc.contains("방면")
                    if (isDir && desc.length > 2) {
                        steps.add(
                            RouteStep(
                                pointIndex  = idx + stepIndexOffset,
                                coordinate  = TMapPoint(c[1].asDouble, c[0].asDouble),
                                description = desc,
                                turnType    = turn,
                                distance    = props.totalDistance ?: 0
                            )
                        )
                    }
                }
            }
        }
        return steps
    }

    private fun extractTotalTime(features: List<RouteFeature>): Int =
        features.firstNotNullOfOrNull { it.properties.totalTime } ?: 0

    private fun extractTotalDistance(features: List<RouteFeature>): Int =
        features.firstNotNullOfOrNull { it.properties.totalDistance } ?: 0

    // ──────────────────────────────────────────
    // 단일 구간 경로 바로 적용 (우회 불가, 재탐색 등)
    // ──────────────────────────────────────────
    private fun applyRouteFeatures(
        features: List<RouteFeature>,
        isRerouting: Boolean,
        bannerState: FloodBannerState
    ) {
        val points = extractPoints(features)
        val steps  = extractSteps(features, stepIndexOffset = 0)

        allRoutePoints.clear()
        allRoutePoints.addAll(points)
        routeSteps.clear()
        routeSteps.addAll(steps)

        onFloodBanner?.invoke(bannerState)
        drawPolyline()

        val totalTime     = extractTotalTime(features)
        val totalDistance = extractTotalDistance(features)

        if (isRerouting) {
            upcomingSteps.clear()
            upcomingSteps.addAll(routeSteps)
            onRerouteComplete()
        } else {
            onRouteReady("자동차 ${totalTime / 60}분 (%.1fkm)".format(totalDistance / 1000.0))
        }
    }

    // ──────────────────────────────────────────
    // TMap 경로 API 요청 body 생성
    // ──────────────────────────────────────────
    private fun buildRouteBody(
        start: TMapPoint,
        end: TMapPoint
    ): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
        addProperty("startX",       start.longitude.toString())
        addProperty("startY",       start.latitude.toString())
        addProperty("endX",         end.longitude.toString())
        addProperty("endY",         end.latitude.toString())
        addProperty("reqCoordType", "WGS84GEO")
        addProperty("resCoordType", "WGS84GEO")
        addProperty("startName",    "출발지")
        addProperty("endName",      "목적지")
        addProperty("trafficInfo",  "Y")
    }

    // ──────────────────────────────────────────
    // 폴리라인 그리기
    // ──────────────────────────────────────────
    fun drawPolyline(floodWarning: Boolean = false) {
        tMapView.removeTMapPolyLine("route")
        if (allRoutePoints.isEmpty()) return

        val lineColor    = AndroidColor.parseColor("#215CF3")
        val outLineColor = AndroidColor.parseColor("#1976D2")

        val poly = TMapPolyLine("route", ArrayList(allRoutePoints)).apply {
            setLineColor(lineColor)
            setLineWidth(8f)
            setOutLineColor(outLineColor)
            setOutLineWidth(2f)
        }
        tMapView.addTMapPolyLine(poly)
        tMapView.postInvalidate()
    }

    // ──────────────────────────────────────────
    // 경로 초기화
    // ──────────────────────────────────────────
    fun clearRoute(currentLocation: TMapPoint?) {
        tMapView.removeAllTMapPolyLine()
        tMapView.removeTMapMarkerItem("destination")
        destinationPoint = null
        destinationName = ""
        onFloodBanner?.invoke(FloodBannerState.NONE)
        currentLocation?.let {
            tMapView.setCenterPoint(it.latitude, it.longitude)
            tMapView.zoomLevel = 15
        }
    }

    fun prepareNavigation() {
        upcomingSteps.clear()
        upcomingSteps.addAll(routeSteps)
    }

    // ──────────────────────────────────────────
    // 텍스트 검색 후 바로 경로 탐색
    // ──────────────────────────────────────────
    fun searchAndRoute(destQuery: String, startPoint: TMapPoint) {
        scope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.tmapService.searchPOI(
                    appKey  = BuildConfig.TMAP_APP_KEY,
                    keyword = destQuery
                )
                withContext(Dispatchers.Main) {
                    val list = resp.body()?.searchPoiInfo?.pois?.poiList
                    if (resp.isSuccessful && !list.isNullOrEmpty()) {
                        setDestination(list.first(), startPoint)
                    } else {
                        Toast.makeText(context, "도착지를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "네트워크 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
