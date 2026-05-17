package com.example.hou

import android.location.Location
import android.speech.tts.TextToSpeech
import com.example.hou.data.model.RouteStep
import com.skt.tmap.TMapPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 내비게이션 안내 로직을 담당합니다.
 * - 회전 안내 (3단계: FAR / MID / NEAR)
 * - 직진 피드백
 * - TTS 우선순위 제어
 * - 경로 이탈 감지
 */
class NavigationManager(
    private val tts: TextToSpeech,
    private val scope: CoroutineScope,
    private val onInstructionChanged: (String) -> Unit,
    private val onReroute: (curLoc: TMapPoint) -> Unit,
    private val onArrived: () -> Unit,
    private val onRemainDistanceChanged: (String) -> Unit,
    private val onPolylineTrim: (count: Int) -> Unit,
) {
    // ──────────────────────────────────────────
    // 상수
    // ──────────────────────────────────────────
    companion object {
        private const val PRI_URGENT   = 5
        private const val PRI_NEAR     = 4
        private const val PRI_MID      = 3
        private const val PRI_FAR      = 2
        private const val PRI_FEEDBACK = 1

        private const val STRAIGHT_FEEDBACK_MS = 30_000L

        private const val DIST_FAR    = 50f
        private const val DIST_MID    = 30f
        private const val DIST_NEAR   = 10f
        private const val DIST_ARRIVE = 15f

        const val REROUTE_THRESHOLD_M  = 25f
        const val REROUTE_CONFIRM_COUNT = 3
        const val POLYLINE_TRIM_MAX     = 3

        private const val DIR_THRESHOLD_BACK = 150f
        private const val DIR_THRESHOLD_TURN = 45f
    }

    var currentFloodDataset: FloodDataset = FloodDataset(emptyList())
    private var lastDangerAnnounceTime = 0L
    private val DANGER_ANNOUNCE_INTERVAL_MS = 30_000L  // 30초마다 한 번만 알림

    // ── 상태 ──
    var isTtsReady = false
    var isRerouting = false
    var offRouteCount = 0
    var isInitialDirAnnounced = false

    private var currentPriority     = 0
    private var priorityReleaseTime = 0L
    var lastStraightFeedbackTime    = 0L

    private val announcedStages = mutableMapOf<Int, MutableSet<String>>()
    private var straightFeedbackJob: Job? = null

    // ──────────────────────────────────────────
    // 초기화 / 리셋
    // ──────────────────────────────────────────
    fun reset() {
        isRerouting = false
        offRouteCount = 0
        isInitialDirAnnounced = false
        announcedStages.clear()
        lastStraightFeedbackTime = System.currentTimeMillis()
    }

    // ──────────────────────────────────────────
    // 직진 피드백
    // ──────────────────────────────────────────
    fun startStraightFeedback(
        isNavigating: () -> Boolean,
        currentLocation: () -> TMapPoint?,
        upcomingSteps: () -> List<RouteStep>,
        destinationPoint: () -> TMapPoint?
    ) {
        straightFeedbackJob?.cancel()
        straightFeedbackJob = scope.launch {
            while (isNavigating()) {
                delay(3_000L)
                if (!isNavigating()) break
                val now = System.currentTimeMillis()
                if (now - lastStraightFeedbackTime < STRAIGHT_FEEDBACK_MS) continue
                val curLoc = currentLocation() ?: continue
                val next = upcomingSteps().firstOrNull()
                if (next != null && calcDist(curLoc, next.coordinate) <= DIST_FAR) continue
                val msg = destinationPoint()?.let {
                    "목적지까지 약 ${calcDist(curLoc, it).toInt()}미터 남았습니다. 계속 직진하세요."
                } ?: "계속 직진하세요."
                withContext(Dispatchers.Main) {
                    if (announce(msg, PRI_FEEDBACK)) lastStraightFeedbackTime = System.currentTimeMillis()
                }
            }
        }
    }

    fun stopStraightFeedback() {
        straightFeedbackJob?.cancel()
    }

    // ──────────────────────────────────────────
    // 안내 진행 체크
    // ──────────────────────────────────────────
    fun checkNavProgress(
        curLoc: TMapPoint,
        allRoutePoints: MutableList<TMapPoint>,
        upcomingSteps: MutableList<RouteStep>,
        destinationPoint: TMapPoint?,
        currentAzimuth: Float,
        isMockMode: Boolean
    ) {
        if (isRerouting || allRoutePoints.isEmpty()) return

        // 최초 방향 안내
        if (!isInitialDirAnnounced) {
            val firstStep = upcomingSteps.firstOrNull()
            if (firstStep != null) {
                val bearing = bearingBetween(curLoc, firstStep.coordinate)
                var diff = bearing - currentAzimuth
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                val msg = when {
                    abs(diff) >= DIR_THRESHOLD_BACK -> "뒤돌아서 출발하세요."
                    diff >= DIR_THRESHOLD_TURN      -> "오른쪽으로 돌아서 출발하세요."
                    diff <= -DIR_THRESHOLD_TURN     -> "왼쪽으로 돌아서 출발하세요."
                    else                            -> "경로를 따라 출발하세요."
                }
                announce(msg, PRI_URGENT)
                isInitialDirAnnounced = true
            }
        }

        // 가장 가까운 경로 포인트
        var minDist = Float.MAX_VALUE
        var closestIdx = 0
        allRoutePoints.forEachIndexed { i, pt ->
            val d = calcDist(curLoc, pt)
            if (d < minDist) { minDist = d; closestIdx = i }
        }

        // 경로 이탈 (실제 주행만)
        if (minDist > REROUTE_THRESHOLD_M && !isMockMode) {
            offRouteCount++
            if (offRouteCount >= REROUTE_CONFIRM_COUNT) {
                offRouteCount = 0
                isRerouting = true
                announce("경로를 벗어났습니다. 재탐색합니다.", PRI_URGENT)
                onReroute(curLoc)
            }
            return
        } else {
            offRouteCount = 0
        }

        // 지나간 포인트 제거
        if (closestIdx > 0) {
            allRoutePoints.subList(0, minOf(closestIdx, POLYLINE_TRIM_MAX)).clear()
            onPolylineTrim(closestIdx)
        }

        // 목적지 도착
        if (destinationPoint != null) {
            val dist = calcDist(curLoc, destinationPoint)
            onRemainDistanceChanged("목적지까지 %.0fm".format(dist))
            if (dist <= DIST_ARRIVE) {
                announce("목적지에 도착했습니다. 안내를 종료합니다.", PRI_URGENT)
                onArrived()
                return
            }
        }

        // ── 침수 위험 구역 진입 감지 ──────────────────────
        val now2 = System.currentTimeMillis()
        if (now2 - lastDangerAnnounceTime > DANGER_ANNOUNCE_INTERVAL_MS) {
            val router = FloodRouter(currentFloodDataset)
            if (router.isRouteInDanger(listOf(curLoc))) {
                if (announce("침수 위험 구역에 진입했습니다. 주의하세요.", PRI_URGENT)) {
                    lastDangerAnnounceTime = now2
                }
            }
        }
        // 3단계 회전 안내
        if (upcomingSteps.isNotEmpty()) {
            val next     = upcomingSteps.first()
            val distTurn = calcDist(curLoc, next.coordinate)
            val stages   = announcedStages.getOrPut(next.pointIndex) { mutableSetOf() }

            if (distTurn <= DIST_FAR && "far" !in stages) {
                val approx = (distTurn / 10).toInt() * 10
                if (announce(buildFar(next.turnType, approx, next.description), PRI_FAR)) {
                    stages.add("far"); lastStraightFeedbackTime = System.currentTimeMillis()
                }
            }
            if (distTurn <= DIST_MID && "mid" !in stages) {
                if (announce(buildMid(next.turnType, next.description), PRI_MID)) {
                    stages.add("mid"); lastStraightFeedbackTime = System.currentTimeMillis()
                }
            }
            if (distTurn <= DIST_NEAR && "near" !in stages) {
                if (announce(buildNear(next.turnType, next.description), PRI_NEAR)) {
                    stages.add("near"); lastStraightFeedbackTime = System.currentTimeMillis()
                    announcedStages.remove(next.pointIndex)
                    upcomingSteps.removeAt(0)
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────
    fun announce(msg: String, priority: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now < priorityReleaseTime && priority < currentPriority) return false
        onInstructionChanged(msg)
        if (isTtsReady) tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "tts_$now")
        currentPriority = priority
        priorityReleaseTime = now + 4000L
        return true
    }

    // ──────────────────────────────────────────
    // 안내 메시지 빌더
    // ──────────────────────────────────────────
    private fun buildFar(turn: Int, dist: Int, desc: String) = when (turn) {
        12          -> "약 ${dist}미터 앞에서 왼쪽으로 회전하세요."
        13          -> "약 ${dist}미터 앞에서 오른쪽으로 회전하세요."
        14          -> "약 ${dist}미터 앞에서 유턴하세요."
        in 211..217 -> "약 ${dist}미터 앞에 횡단보도가 있습니다."
        else        -> "약 ${dist}미터 앞, $desc"
    }

    private fun buildMid(turn: Int, desc: String) = when (turn) {
        12          -> "왼쪽으로 회전할 준비를 하세요."
        13          -> "오른쪽으로 회전할 준비를 하세요."
        14          -> "유턴할 준비를 하세요."
        in 211..217 -> when {
            desc.contains("신호등") -> "신호등이 있는 횡단보도입니다. 신호를 확인하고 건너세요."
            desc.contains("육교")   -> "육교를 이용해 건너세요."
            desc.contains("지하도") -> "지하도를 이용해 건너세요."
            else                    -> "횡단보도를 건너세요."
        }
        else -> desc
    }

    private fun buildNear(turn: Int, desc: String) = when (turn) {
        12          -> "지금 왼쪽으로 회전하세요."
        13          -> "지금 오른쪽으로 회전하세요."
        14          -> "지금 유턴하세요."
        in 211..217 -> "지금 횡단보도를 건너세요."
        else        -> "곧 $desc"
    }

    // ──────────────────────────────────────────
    // 유틸
    // ──────────────────────────────────────────
    fun calcDist(p1: TMapPoint, p2: TMapPoint): Float {
        val a = Location("").apply { latitude = p1.latitude; longitude = p1.longitude }
        val b = Location("").apply { latitude = p2.latitude; longitude = p2.longitude }
        return a.distanceTo(b)
    }

    private fun bearingBetween(from: TMapPoint, to: TMapPoint): Float {
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)).toFloat()) + 360) % 360
    }
}
