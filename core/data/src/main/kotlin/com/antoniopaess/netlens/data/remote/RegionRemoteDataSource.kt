package com.antoniopaess.netlens.data.remote

/** Remote source boundary kept small so repository tests do not need Retrofit. */
fun interface RegionRemoteDataSource {
    /** Fetch the current public list; transport exceptions remain inside data. */
    suspend fun fetch(): List<CountryDto>
}

/** Adapter that makes the Retrofit API usable as the repository's remote source. */
class RetrofitRegionRemoteDataSource(
    private val api: RegionApi,
) : RegionRemoteDataSource {
    override suspend fun fetch(): List<CountryDto> = api.getCountries()
}
