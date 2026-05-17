package com.example.hou

import android.graphics.Color
import com.skt.tmap.TMapPoint
import com.skt.tmap.TMapView
import com.skt.tmap.overlay.TMapPolygon

// ──────────────────────────────────────────────────────────────
// 데이터 모델
// ──────────────────────────────────────────────────────────────

/**
 * 침수 위험 셀 하나를 나타냅니다.
 *
 * @param centerLat  셀 중심점 위도 (WGS84)
 * @param centerLon  셀 중심점 경도 (WGS84)
 * @param riskLevel  위험 척도 1(낮음) ~ 100(높음)
 */
data class FloodCell(
    val centerLat: Double,
    val centerLon: Double,
    val riskLevel: Int
)

/**
 * 서버 또는 로컬에서 받아온 전체 침수 데이터셋.
 * [timestamp]은 데이터가 생성된 Unix epoch(ms)로, UI에서 "마지막 업데이트" 표시에 사용합니다.
 */
data class FloodDataset(
    val cells: List<FloodCell>,
    val timestamp: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────────────────────
// 임시 더미 데이터  (나중에 서버 연동 시 이 부분만 교체)
// ──────────────────────────────────────────────────────────────

/**
 * 실제 서버 연동 전까지 사용할 임시 침수 데이터.
 *
 * ■ 대상 지역: 강남역 ~ 신논현역 일대 (서울 서초구/강남구)
 *
 * ■ 실제 침수 패턴 반영 (2010·2022년 강남 침수 기록 참고)
 *   - 강남역 사거리: 도로 지하차도 특성상 빗물 집중 → 고위험 핫스팟
 *   - 서초대로(강남역↔신논현 구간): 저지대 선형 침수 → 중위험 선형 분포
 *   - 강남역 9~11번 출구 인근 골목: 반지하·저층 상가 밀집 → 고위험 집중
 *   - 신논현역 인근: 역삼로 교차 저지대 → 중위험 산발
 *   - 논현로 골목: 비교적 고지대, 산발적 저위험
 *
 * ■ 그리드: 위도 0.00036° (≈40m), 경도 0.00045° (≈40m)
 *
 * 서버 연동 시 이 함수 대신 Retrofit 등으로 [FloodDataset]을 받아오면 됩니다.
 */
fun getMockFloodData(): FloodDataset {
    val latStep = 0.00036  // ≈ 40m
    val lonStep = 0.00045  // ≈ 40m (위도 37.5° 기준)

    /**
     * @param lat    중심 위도
     * @param lon    중심 경도
     * @param weight 영향력 (1.0 = 최고위험)
     * @param radius 영향 반경 (위경도 단위)
     * @param shape  "circle" | "hline" | "vline" — 침수 형태
     */
    data class Hotspot(
        val lat: Double,
        val lon: Double,
        val weight: Double,
        val radius: Double = 0.003,
        val shape: String = "circle"
    )

    val hotspots = listOf(
        // ── 강남역 사거리 저지대 (교차로 집수 → 최고위험) ──────────────
        Hotspot(37.4980, 127.0276, weight = 1.0,  radius = 0.0018, shape = "circle"),

        // ── 강남역 9~11번 출구 골목 (반지하 밀집) ───────────────────────
        Hotspot(37.4971, 127.0265, weight = 0.92, radius = 0.0013, shape = "circle"),
        Hotspot(37.4965, 127.0258, weight = 0.85, radius = 0.0010, shape = "circle"),

        // ── 서초대로 선형 침수 (강남역 → 신논현 방향) ───────────────────
        // 서초대로는 동서 방향 저지대 → hline(가로 타원)으로 표현
        Hotspot(37.4982, 127.0290, weight = 0.75, radius = 0.0014, shape = "hline"),
        Hotspot(37.4984, 127.0310, weight = 0.65, radius = 0.0013, shape = "hline"),
        Hotspot(37.4986, 127.0330, weight = 0.60, radius = 0.0012, shape = "hline"),

        // ── 신논현역 사거리 저지대 ──────────────────────────────────────
        Hotspot(37.5048, 127.0252, weight = 0.78, radius = 0.0015, shape = "circle"),
        Hotspot(37.5055, 127.0260, weight = 0.65, radius = 0.0010, shape = "circle"),

        // ── 논현로 골목 산발 (비교적 경미) ─────────────────────────────
        Hotspot(37.5010, 127.0240, weight = 0.42, radius = 0.0010, shape = "circle"),
        Hotspot(37.5020, 127.0225, weight = 0.35, radius = 0.0009, shape = "circle"),

        // ── 역삼로 교차 저지대 (역삼1동 쪽) ────────────────────────────
        Hotspot(37.4995, 127.0315, weight = 0.55, radius = 0.0011, shape = "circle"),
        Hotspot(37.4990, 127.0340, weight = 0.48, radius = 0.0010, shape = "circle"),

        // ── 강남역 남쪽 (서초동 진입로 저지대) ─────────────────────────
        Hotspot(37.4958, 127.0282, weight = 0.60, radius = 0.0012, shape = "circle"),
        Hotspot(37.4945, 127.0295, weight = 0.45, radius = 0.0009, shape = "circle"),

        // ── 봉은사로 방향 산발 소규모 ───────────────────────────────────
        Hotspot(37.5030, 127.0280, weight = 0.38, radius = 0.0008, shape = "circle"),
        Hotspot(37.5038, 127.0295, weight = 0.32, radius = 0.0007, shape = "circle")
    )

    val cells = mutableListOf<FloodCell>()

    // 탐색 범위: 강남역~신논현역을 포함하는 사각형
    var lat = 37.493
    while (lat <= 37.508) {
        var lon = 127.022
        while (lon <= 127.037) {
            var influence = 0.0

            for (h in hotspots) {
                val dLat = lat - h.lat
                val dLon = lon - h.lon

                // 형태별 거리 계산
                val distDeg = when (h.shape) {
                    "hline" -> Math.sqrt(dLat * dLat * 4.0 + dLon * dLon)  // 경도 방향 타원 (도로 선형)
                    "vline" -> Math.sqrt(dLat * dLat + dLon * dLon * 4.0)  // 위도 방향 타원
                    else    -> Math.sqrt(dLat * dLat + dLon * dLon)         // 원형
                }

                if (distDeg < h.radius) {
                    // 핫스팟 중심에 가까울수록 영향 강해짐 (2차 감쇠)
                    val normalized = distDeg / h.radius
                    influence += h.weight * (1.0 - normalized * normalized)
                }
            }

            if (influence > 0.08) {
                val jitter = (Math.random() - 0.5) * 0.12
                val risk = ((influence + jitter) * 105).coerceIn(5.0, 100.0).toInt()
                cells.add(FloodCell(lat, lon, risk))
            }

            lon += lonStep
        }
        lat += latStep
    }

    return FloodDataset(cells = cells)
}

// ──────────────────────────────────────────────────────────────
// 히트맵 렌더링 매니저
// ──────────────────────────────────────────────────────────────

/**
 * TMap 위에 침수 히트맵(40m×40m 사각형)을 그리고 지우는 역할을 담당합니다.
 *
 * TMapView SDK 실제 API (바이트코드 확인):
 *   addTMapPolygon(TMapPolygon)         : 객체로 추가
 *   removeTMapPolygon(String id)        : id 문자열로 제거
 *   removeAllTMapPolygon()              : 전체 제거
 *   TMapPolygon(id: String, points: ArrayList<TMapPoint>)  : 생성자
 */
class FloodHeatmapManager(private val tMapView: TMapView) {

    companion object {
        // 40m 크기를 위경도 오프셋으로 환산 (한국 중부 기준 근사값)
        // 위도 1° ≈ 111,000m  →  20m ≈ 0.00018°
        // 경도 1° ≈  88,800m  →  20m ≈ 0.000225° (위도 37° 기준)
        private const val HALF_LAT = 0.00018
        private const val HALF_LON = 0.000225

        private const val ID_PREFIX  = "flood_cell_"

        /** 히트맵 표시 최소 위험도 임계치 */
        private const val HEATMAP_MIN_RISK = 30
    }

    /** 현재 지도에 표시된 폴리곤 ID 목록 (remove 시 String id 필요) */
    private val activeIds = mutableListOf<String>()

    var isVisible: Boolean = false
        private set

    /**
     * 침수 데이터를 받아 지도에 히트맵을 그립니다.
     * riskLevel >= 30인 셀만 표시합니다.
     * 이전에 그려진 히트맵이 있으면 먼저 지웁니다.
     */
    fun show(dataset: FloodDataset) {
        clear()
        dataset.cells
            .filter { it.riskLevel >= HEATMAP_MIN_RISK }   // ★ 30 미만 제외
            .forEachIndexed { index, cell ->
                val polygon = buildPolygon(cell, index)
                tMapView.addTMapPolygon(polygon)
                activeIds.add("$ID_PREFIX$index")
            }
        isVisible = true
    }

    /**
     * 지도에서 히트맵 폴리곤을 모두 제거합니다.
     */
    fun clear() {
        activeIds.forEach { id ->
            tMapView.removeTMapPolygon(id)            // remove: String id
        }
        activeIds.clear()
        isVisible = false
    }

    /**
     * 표시/숨김을 토글합니다.
     */
    fun toggle(dataset: FloodDataset) {
        if (isVisible) clear() else show(dataset)
    }

    // ── 내부: 단일 셀 → TMapPolygon 변환 ──────────────────────

    private fun buildPolygon(cell: FloodCell, index: Int): TMapPolygon {
        // SDK 생성자: TMapPolygon(id: String, points: ArrayList<TMapPoint>)
        val corners = arrayListOf(
            TMapPoint(cell.centerLat - HALF_LAT, cell.centerLon - HALF_LON), // SW
            TMapPoint(cell.centerLat - HALF_LAT, cell.centerLon + HALF_LON), // SE
            TMapPoint(cell.centerLat + HALF_LAT, cell.centerLon + HALF_LON), // NE
            TMapPoint(cell.centerLat + HALF_LAT, cell.centerLon - HALF_LON)  // NW
        )

        return TMapPolygon("$ID_PREFIX$index", corners).apply {
            setAreaColor(riskToFillColor(cell.riskLevel))
            setAreaAlpha(120)
            setLineColor(riskToStrokeColor(cell.riskLevel))
            setPolygonWidth(if (cell.riskLevel >= 70) 1.5f else 0.8f)
            setLineAlpha(80)
        }
    }

    /**
     * 위험도(30~100) → 채움 색상
     * 30: 연노랑(#FFF176) → 50: 주황(#FF9800) → 100: 짙은 빨강(#B71C1C)
     */
    private fun riskToFillColor(risk: Int): Int {
        // 30~100을 0.0~1.0으로 정규화
        val t = ((risk - 30) / 70f).coerceIn(0f, 1f)
        return when {
            t < 0.4f -> {
                // 연노랑(#FFF176) → 주황(#FF9800)
                val r = t / 0.4f
                Color.rgb(
                    lerp(0xFF, 0xFF, r),
                    lerp(0xF1, 0x98, r),
                    lerp(0x76, 0x00, r)
                )
            }
            else -> {
                // 주황(#FF9800) → 짙은 빨강(#B71C1C)
                val r = (t - 0.4f) / 0.6f
                Color.rgb(
                    lerp(0xFF, 0xB7, r),
                    lerp(0x98, 0x1C, r),
                    lerp(0x00, 0x1C, r)
                )
            }
        }
    }

    /**
     * 위험도(30~100) → 테두리 색상 (채움색보다 약간 어둡게)
     */
    private fun riskToStrokeColor(risk: Int): Int {
        val t = ((risk - 30) / 70f).coerceIn(0f, 1f)
        return when {
            t < 0.4f -> Color.rgb(0xCC, 0xA0, 0x00)   // 어두운 노랑
            else     -> Color.rgb(lerp(0xCC, 0x7F, (t - 0.4f) / 0.6f), 0x00, 0x00)
        }
    }

    /** 정수형 선형 보간 */
    private fun lerp(start: Int, end: Int, t: Float): Int =
        (start + (end - start) * t).toInt().coerceIn(0, 255)
}
