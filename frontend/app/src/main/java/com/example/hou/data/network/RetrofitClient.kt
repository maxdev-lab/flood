package com.example.hou.data.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val TMAP_BASE_URL  = "https://apis.openapi.sk.com/"
    private const val FLOOD_BASE_URL = "http://10.0.2.2:8000/"

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor { message -> Log.d("TMapNavi", message) }
                .apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    val tmapService: TMapApiService by lazy {
        Retrofit.Builder()
            .baseUrl(TMAP_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TMapApiService::class.java)
    }

    val floodService: FloodApiService by lazy {
        Retrofit.Builder()
            .baseUrl(FLOOD_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FloodApiService::class.java)
    }
}
