package com.example.hou

import com.skt.tmap.TMapPoint
import kotlin.math.*

/**
 * 침수 위험구역 우회 경로 계산기.
 *
 * 위험 셀들을 클러스터로 병합한 뒤, 경로를 막는 클러스터가 있으면
 * 측면 우회 앵커포인트를 계산해 반환합니다.
 * RouteManager가 이 앵커를 경유해 구간별로 TMap API를 호출합니다.
 *
 * 앵커 배치 전략 ("중간점 측면 이탈"):
 *   1. 경로를 막는 클러스터들을 하나의 AABB로 병합
 *   2. AABB 중심을 출발~도착 직선에 투영 → 경로상 블록 위치 P
 *   3. P에서 수직으로 (블록 수직폭/2 + ANCHOR_MARGIN_M) 만큼 좌·우 이동
 *   4. 총 경로 거리가 짧은 쪽 선택
 *   5. 앵커가 다른 위험 셀 위에 놓이면 같은 방향으로 추가 이동
 */
class FloodRouter(
    private val floodDataset: FloodDataset
) {

     companion object {
        const val RISK_THRESHOLD = 55
        const val ANCHOR_MARGIN_M = 80.0
        const val CLUSTER_MERGE_RADIUS_M = 200.0
        const val ROUTE_CHECK_MARGIN_M = 120.0
        const val BYPASS_IMPOSSIBLE_RATIO = 0.99
        const val ROUTE_DANGER_RADIUS_M = 80.0    // ← 추가
        const val DEST_DANGER_RADIUS_M = 100.0    // ← 추가
    }

    internal val dangerCells: List<FloodCell> by lazy {
        floodDataset.cells.filter { it.riskLevel >= RISK_THRESHOLD }
    }

    private val dangerClusters: List<DangerCluster> by lazy {
        mergeClusters(dangerCells)
    }

    // ─────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────

    /** 목적지가 위험구역 안에 있는지 반환합니다. */
    fun isDestinationInDanger(end: TMapPoint): Boolean =
        dangerCells.any { cell ->
            haversineM(end.latitude, end.longitude, cell.centerLat, cell.centerLon) < DEST_DANGER_RADIUS_M
        }

    /**
     * 측면 우회가 불가능한지 반환합니다.
     * 위험 블록의 경로 방향 폭이 전체 경로 길이의 BYPASS_IMPOSSIBLE_RATIO 이상이면 true.
     */
    fun isBypassImpossible(start: TMapPoint, end: TMapPoint): Boolean {
        if (dangerClusters.isEmpty()) return false
        val totalDistM = haversineM(start.latitude, start.longitude, end.latitude, end.longitude)
        if (totalDistM < 1.0) return false

        val blocking = dangerClusters.filter { isClusterBlockingPath(it, start, end) }
        if (blocking.isEmpty()) return false

        // 모든 클러스터가 완전히 경로를 막고 있을 때만 불가 판정
        val bearing = bearingRad(start.latitude, start.longitude, end.latitude, end.longitude)
        val blockingWidth = blocking.sumOf { clusterProjectionWidthM(it, bearing) }

        // 추가 조건: 우회 앵커 후보가 전부 위험구역 안에 있을 때만 불가
        val aabb = mergeBoundingBoxes(blocking)
        val cosLat = cos(Math.toRadians((aabb.minLat + aabb.maxLat) / 2.0))
        val perpWidth = clusterPerpWidthM(aabb, bearing, cosLat)
        val midLat = (aabb.minLat + aabb.maxLat) / 2.0
        val midLon = (aabb.minLon + aabb.maxLon) / 2.0
        val leftAnchor = lateralOffset(midLat, midLon, bearing, -(perpWidth / 2.0 + ANCHOR_MARGIN_M), cosLat)
        val rightAnchor = lateralOffset(midLat, midLon, bearing, +(perpWidth / 2.0 + ANCHOR_MARGIN_M), cosLat)

        val leftInDanger = dangerCells.any {
            haversineM(leftAnchor.latitude, leftAnchor.longitude, it.centerLat, it.centerLon) < ANCHOR_MARGIN_M * 0.5
        }
        val rightInDanger = dangerCells.any {
            haversineM(rightAnchor.latitude, rightAnchor.longitude, it.centerLat, it.centerLon) < ANCHOR_MARGIN_M * 0.5
        }

        // 양쪽 앵커 모두 위험 AND 비율도 높을 때만 불가
        return (blockingWidth / totalDistM) >= BYPASS_IMPOSSIBLE_RATIO && leftInDanger && rightInDanger
    }

    /**
     * 우회 앵커포인트 목록을 반환합니다.
     * - 위험구역 없음 → 빈 리스트 (우회 불필요)
     * - 위험구역 있음 → 단일 앵커 1개
     */
    fun findBypassAnchors(start: TMapPoint, end: TMapPoint): List<TMapPoint> {
        if (dangerClusters.isEmpty()) return emptyList()
        val blocking = dangerClusters.filter { isClusterBlockingPath(it, start, end) }
        if (blocking.isEmpty()) return emptyList()

        val bearing = bearingRad(start.latitude, start.longitude, end.latitude, end.longitude)
        val aabb = mergeBoundingBoxes(blocking)
        val cosLat = cos(Math.toRadians((aabb.minLat + aabb.maxLat) / 2.0))

        // 위험구역 AABB 모서리 4개 중 출발~도착 직선에서
        // 가장 가까운 안전 모서리를 앵커로 사용
        val corners = listOf(
            TMapPoint(aabb.minLat - 0.001, aabb.minLon - 0.001),
            TMapPoint(aabb.minLat - 0.001, aabb.maxLon + 0.001),
            TMapPoint(aabb.maxLat + 0.001, aabb.minLon - 0.001),
            TMapPoint(aabb.maxLat + 0.001, aabb.maxLon + 0.001),
        )

        // 위험구역과 겹치지 않는 모서리만 필터
        val safeCorners = corners.filter { corner ->
            dangerCells.none { cell ->
                haversineM(corner.latitude, corner.longitude,
                    cell.centerLat, cell.centerLon) < ANCHOR_MARGIN_M * 0.8
            }
        }

        if (safeCorners.isEmpty()) return emptyList()

        // 전체 경로 거리가 가장 짧은 모서리 1개 선택
        val best = safeCorners.minByOrNull { corner ->
            haversineM(start.latitude, start.longitude, corner.latitude, corner.longitude) +
                    haversineM(corner.latitude, corner.longitude, end.latitude, end.longitude)
        } ?: return emptyList()

        return listOf(best)
    }
    /** 하위 호환용 단일 앵커 반환 */
    fun findBypassAnchor(start: TMapPoint, end: TMapPoint): TMapPoint? =
        findBypassAnchors(start, end).firstOrNull()

    /** 경로 포인트 중 하나라도 위험구역을 통과하는지 반환합니다. */
    fun isRouteInDanger(routePoints: List<TMapPoint>): Boolean {
        if (dangerCells.isEmpty()) return false
        return routePoints.any { pt ->
            dangerCells.any { cell ->
                haversineM(pt.latitude, pt.longitude, cell.centerLat, cell.centerLon) < ROUTE_DANGER_RADIUS_M
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 기하 계산
    // ─────────────────────────────────────────────────────────

    /**
     * (lat, lon)을 start→end 직선 위에 투영합니다.
     * t 범위를 0.2~0.8로 제한해 앵커가 출발·도착 너무 가까이 붙지 않게 합니다.
     */
    private fun projectOntoPath(
        lat: Double, lon: Double,
        start: TMapPoint, end: TMapPoint,
        bearing: Double, cosLat: Double
    ): Pair<Double, Double> {
        val eN = (end.latitude  - start.latitude)  * 111_000.0
        val eE = (end.longitude - start.longitude) * 111_000.0 * cosLat
        val pN = (lat - start.latitude)  * 111_000.0
        val pE = (lon - start.longitude) * 111_000.0 * cosLat

        val lenSq = eN * eN + eE * eE
        if (lenSq < 1e-6) return start.latitude to start.longitude

        val t = ((pN * eN + pE * eE) / lenSq).coerceIn(0.2, 0.8)
        return (start.latitude  + (eN * t) / 111_000.0) to
               (start.longitude + (eE * t) / (111_000.0 * cosLat))
    }

    /**
     * (lat, lon)에서 bearing 방향과 수직으로 offsetM만큼 이동합니다.
     * offsetM > 0 → 오른쪽, < 0 → 왼쪽
     */
    private fun lateralOffset(
        lat: Double, lon: Double,
        bearing: Double, offsetM: Double, cosLat: Double
    ): TMapPoint {
        val perp = bearing + Math.PI / 2.0
        return TMapPoint(
            lat + offsetM * cos(perp) / 111_000.0,
            lon + offsetM * sin(perp) / (111_000.0 * cosLat)
        )
    }

    /** AABB의 경로 수직 방향 폭 (미터). */
    private fun clusterPerpWidthM(aabb: BoundingBox, bearing: Double, cosLat: Double): Double {
        val midLat = (aabb.minLat + aabb.maxLat) / 2.0
        val midLon = (aabb.minLon + aabb.maxLon) / 2.0
        val perp   = bearing + Math.PI / 2.0
        val projs  = listOf(
            aabb.minLat to aabb.minLon, aabb.minLat to aabb.maxLon,
            aabb.maxLat to aabb.minLon, aabb.maxLat to aabb.maxLon
        ).map { (cLat, cLon) ->
            val dN = (cLat - midLat) * 111_000.0
            val dE = (cLon - midLon) * 111_000.0 * cosLat
            dN * cos(perp) + dE * sin(perp)
        }
        return projs.maxOrNull()!! - projs.minOrNull()!!
    }

    /**
     * 앵커가 위험 셀 위에 있으면 sign 방향(+1=오른쪽, -1=왼쪽)으로 계속 밀어냅니다.
     */
    private fun pushAnchorClear(
        anchor: TMapPoint, bearing: Double, sign: Double, cosLat: Double
    ): TMapPoint {
        var cur = anchor
        val perp = bearing + Math.PI / 2.0
        repeat(5) {
            if (dangerCells.none { cell ->
                    haversineM(cur.latitude, cur.longitude, cell.centerLat, cell.centerLon) < ANCHOR_MARGIN_M * 0.7
                }) return cur
            val step = ANCHOR_MARGIN_M * sign
            cur = TMapPoint(
                cur.latitude  + step * cos(perp) / 111_000.0,
                cur.longitude + step * sin(perp) / (111_000.0 * cosLat)
            )
        }
        return cur
    }

    // ─────────────────────────────────────────────────────────
    // 클러스터링 (BFS 그리디 병합)
    // ─────────────────────────────────────────────────────────

    private fun mergeClusters(cells: List<FloodCell>): List<DangerCluster> {
        if (cells.isEmpty()) return emptyList()
        val assigned = BooleanArray(cells.size)
        val clusters = mutableListOf<DangerCluster>()
        for (i in cells.indices) {
            if (assigned[i]) continue
            val group = mutableListOf(cells[i])
            assigned[i] = true
            val queue = ArrayDeque<Int>().also { it.add(i) }
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (j in cells.indices) {
                    if (assigned[j]) continue
                    if (haversineM(cells[cur].centerLat, cells[cur].centerLon,
                                   cells[j].centerLat,   cells[j].centerLon) <= CLUSTER_MERGE_RADIUS_M) {
                        assigned[j] = true; group.add(cells[j]); queue.add(j)
                    }
                }
            }
            clusters.add(DangerCluster(group))
        }
        return clusters
    }

    // ─────────────────────────────────────────────────────────
    // 클러스터-경로 교차 판정
    // ─────────────────────────────────────────────────────────

    private fun isClusterBlockingPath(cluster: DangerCluster, start: TMapPoint, end: TMapPoint): Boolean {
        val bb     = cluster.boundingBox
        val cosLat = cos(Math.toRadians((bb.minLat + bb.maxLat) / 2.0))
        val mLat   = ROUTE_CHECK_MARGIN_M / 111_000.0
        val mLon   = ROUTE_CHECK_MARGIN_M / (111_000.0 * cosLat)
        return segmentIntersectsAABB(
            start.latitude, start.longitude, end.latitude, end.longitude,
            bb.minLat - mLat, bb.maxLat + mLat, bb.minLon - mLon, bb.maxLon + mLon
        )
    }

    private fun segmentIntersectsAABB(
        sLat: Double, sLon: Double, eLat: Double, eLon: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): Boolean {
        fun inside(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
        if (inside(sLat, sLon) || inside(eLat, eLon)) return true

        fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
        fun intersects(p1a: Double, p1b: Double, p2a: Double, p2b: Double,
                       p3a: Double, p3b: Double, p4a: Double, p4b: Double): Boolean {
            val da = p2a - p1a; val db = p2b - p1b
            val ea = p4a - p3a; val eb = p4b - p3b
            val d  = cross(da, db, ea, eb)
            if (abs(d) < 1e-12) return false
            val tx = cross(p3a - p1a, p3b - p1b, ea, eb) / d
            val ty = cross(p3a - p1a, p3b - p1b, da, db) / d
            return tx in 0.0..1.0 && ty in 0.0..1.0
        }
        val c = listOf(minLat to minLon, minLat to maxLon, maxLat to maxLon, maxLat to minLon)
        for (i in c.indices) {
            val (c1a, c1b) = c[i]; val (c2a, c2b) = c[(i + 1) % 4]
            if (intersects(sLat, sLon, eLat, eLon, c1a, c1b, c2a, c2b)) return true
        }
        return false
    }

    private fun clusterProjectionWidthM(cluster: DangerCluster, bearing: Double): Double {
        val refLat = (cluster.boundingBox.minLat + cluster.boundingBox.maxLat) / 2.0
        val refLon = (cluster.boundingBox.minLon + cluster.boundingBox.maxLon) / 2.0
        val cosLat = cos(Math.toRadians(refLat))
        val projs  = cluster.cells.map { cell ->
            val dN = (cell.centerLat - refLat) * 111_000.0
            val dE = (cell.centerLon - refLon) * 111_000.0 * cosLat
            dN * cos(bearing) + dE * sin(bearing)
        }
        return (projs.maxOrNull() ?: 0.0) - (projs.minOrNull() ?: 0.0)
    }

    private fun mergeBoundingBoxes(clusters: List<DangerCluster>) = BoundingBox(
        minLat = clusters.minOf { it.boundingBox.minLat },
        maxLat = clusters.maxOf { it.boundingBox.maxLat },
        minLon = clusters.minOf { it.boundingBox.minLon },
        maxLon = clusters.maxOf { it.boundingBox.maxLon }
    )

    // ─────────────────────────────────────────────────────────
    // 수학 유틸
    // ─────────────────────────────────────────────────────────

    internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingRad(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y    = sin(dLon) * cos(Math.toRadians(lat2))
        val x    = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                   sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return atan2(y, x)
    }

    // ─────────────────────────────────────────────────────────
    // 내부 클래스
    // ─────────────────────────────────────────────────────────

    private inner class DangerCluster(val cells: List<FloodCell>) {
        val boundingBox = BoundingBox(
            minLat = cells.minOf { it.centerLat },
            maxLat = cells.maxOf { it.centerLat },
            minLon = cells.minOf { it.centerLon },
            maxLon = cells.maxOf { it.centerLon }
        )
    }

    private data class BoundingBox(
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double
    )
}
