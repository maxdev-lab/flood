package com.example.hou.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.skt.tmap.TMapPoint

// ──────────────────────────────────────────────
// POI 검색 응답
// ──────────────────────────────────────────────

data class TMapPoiResponse(
    @SerializedName("searchPoiInfo")
    val searchPoiInfo: SearchPoiInfo
)

data class SearchPoiInfo(
    @SerializedName("pois")
    val pois: Pois
)

data class Pois(
    @SerializedName("poi")
    val poiList: List<PoiItem>
)

data class PoiItem(
    val name: String,
    val upperAddrName: String,
    val middleAddrName: String,
    val lowerAddrName: String,
    val noorLat: String,
    val noorLon: String
) {
    fun getFullAddress(): String = "$upperAddrName $middleAddrName $lowerAddrName".trim()
}

// ──────────────────────────────────────────────
// 경로 탐색 응답
// ──────────────────────────────────────────────

data class TmapRouteResponse(
    val type: String,
    val features: List<RouteFeature>
)

data class RouteFeature(
    val type: String,
    val geometry: RouteGeometry,
    val properties: RouteProperties
)

data class RouteGeometry(
    val type: String,
    val coordinates: JsonElement   // Point / LineString 모두 수용
)

data class RouteProperties(
    val index: Int?,
    val pointIndex: Int?,
    val lineIndex: Int?,
    val name: String?,
    val description: String?,
    val distance: Int?,
    val time: Int?,
    val turnType: Int?,
    val totalDistance: Int?,
    val totalTime: Int?
)

// 앱 내부에서 사용하는 안내 스텝
data class RouteStep(
    val pointIndex: Int,
    val coordinate: TMapPoint,
    val description: String,
    val turnType: Int,
    val distance: Int
)

// ──────────────────────────────────────────────
// 역지오코딩 응답
// ──────────────────────────────────────────────

data class ReverseGeoResponse(
    @SerializedName("addressInfo")
    val addressInfo: AddressInfo?
)

data class AddressInfo(
    @SerializedName("fullAddress")     val fullAddress: String?,     // 전체 주소
    @SerializedName("roadName")        val roadName: String?,        // 도로명
    @SerializedName("buildingIndex")   val buildingIndex: String?,   // 건물번호
    @SerializedName("city_do")         val cityDo: String?,          // 시/도
    @SerializedName("gu_gun")          val guGun: String?,           // 구/군
    @SerializedName("eup_myun")        val eupMyun: String?,         // 읍/면
    @SerializedName("ri")              val ri: String?,              // 리
    @SerializedName("bunji")           val bunji: String?            // 번지
) {
    /** 도로명 주소 조합. 없으면 지번 주소로 fallback */
    fun toDisplayAddress(): String {
        // 도로명 주소: "도로명 건물번호"
        val road = buildString {
            roadName?.takeIf { it.isNotBlank() }?.let { append(it) }
            buildingIndex?.takeIf { it.isNotBlank() }?.let { append(" $it") }
        }
        if (road.isNotBlank()) return road

        // fallback: 지번 주소 조합
        val jibun = buildString {
            guGun?.takeIf { it.isNotBlank() }?.let { append(it) }
            eupMyun?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            ri?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            bunji?.takeIf { it.isNotBlank() }?.let { append(" $it") }
        }
        if (jibun.isNotBlank()) return jibun

        return fullAddress?.takeIf { it.isNotBlank() } ?: "현재 위치"
    }
}