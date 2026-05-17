
package com.example.hou.data.network

import com.example.hou.data.model.TMapPoiResponse
import com.example.hou.data.model.TmapRouteResponse
import com.example.hou.data.model.ReverseGeoResponse
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.*
interface TMapApiService {

    /** 장소 키워드 검색 */
    @GET("tmap/pois?version=1")
    suspend fun searchPOI(
        @Header("appKey") appKey: String,
        @Query("searchKeyword") keyword: String,
        @Query("resCoordType") resCoordType: String = "WGS84GEO",
        @Query("reqCoordType") reqCoordType: String = "WGS84GEO",
        @Query("count") count: Int = 15
    ): Response<TMapPoiResponse>

    /** 보행자 경로 탐색 */
    @FormUrlEncoded
    @POST("tmap/routes/pedestrian?version=1")
    suspend fun getPedestrianRoute(
        @Header("appKey") appKey: String,
        @Field("startX") startX: String,
        @Field("startY") startY: String,
        @Field("endX") endX: String,
        @Field("endY") endY: String,
        @Field("passList") passList: String? = null,
        @Field("reqCoordType") reqCoordType: String = "WGS84GEO",
        @Field("resCoordType") resCoordType: String = "WGS84GEO",
        @Field("startName") startName: String = "출발지",
        @Field("endName") endName: String = "목적지"
    ): Response<TmapRouteResponse>

    /** 자동차 경로 탐색 */
    @POST("tmap/routes?version=1")
    suspend fun getCarRoute(
        @Header("appKey") appKey: String,
        @Body body: JsonObject
    ): Response<TmapRouteResponse>

    /** 역지오코딩 (좌표 → 주소) */
    @GET("tmap/geo/reversegeocoding")
    suspend fun reverseGeocode(
        @Header("appKey") appKey: String,
        @Query("lon") lon: String,
        @Query("lat") lat: String,
        @Query("coordType") coordType: String = "WGS84GEO",
        @Query("addressType") addressType: String = "A10",  // A10 = 도로명주소 우선
        @Query("version") version: Int = 1
    ): Response<ReverseGeoResponse>

}

