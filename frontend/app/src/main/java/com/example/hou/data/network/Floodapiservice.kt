package com.example.hou.data.network

import com.example.hou.FloodDataset
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 백엔드 침수 위험 API
 * 백엔드 응답이 프론트 FloodDataset 모델과 동일한 구조이므로
 * 별도 DTO 없이 FloodDataset 으로 바로 역직렬화.
 *
 * 백엔드 응답:
 * {
 *   "cells":     [{"centerLat": 37.49, "centerLon": 127.02, "riskLevel": 72}, ...],
 *   "timestamp": 1715000000000,
 *   "total":     2470,
 *   "blocked":   134
 * }
 */
interface FloodApiService {

    @GET("api/v1/flood/cells")
    suspend fun getFloodCells(
        @Query("min_risk")  minRisk:   Int   = 30,
        @Query("threshold") threshold: Float = 65.0f
    ): Response<FloodDataset>
}
