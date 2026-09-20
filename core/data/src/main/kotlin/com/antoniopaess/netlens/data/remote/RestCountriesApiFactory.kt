package com.antoniopaess.netlens.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Creates the real public REST client used by the production data graph. */
object RestCountriesApiFactory {
    private const val BASE_URL = "https://restcountries.com/"
    private const val JSON_MEDIA_TYPE = "application/json"
    private val JSON_CONVERTER =
        Json {
            ignoreUnknownKeys = true
        }.asConverterFactory(MediaType.parse(JSON_MEDIA_TYPE)!!)

    /** Build a client pointed at restcountries.com, without a mock fallback. */
    fun create(): RegionApi =
        Retrofit
            .Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(JSON_CONVERTER)
            .build()
            .create(RegionApi::class.java)
}
