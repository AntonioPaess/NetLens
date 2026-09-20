package com.antoniopaess.netlens.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/** Retrofit boundary for the public region source used by production builds. */
interface RegionApi {
    /** Fetch only the country name and two-letter code needed by the app. */
    @GET("v3.1/all")
    suspend fun getCountries(
        @Query("fields") fields: String = REQUESTED_FIELDS,
    ): List<CountryDto>

    private companion object {
        const val REQUESTED_FIELDS = "name,cca2"
    }
}
