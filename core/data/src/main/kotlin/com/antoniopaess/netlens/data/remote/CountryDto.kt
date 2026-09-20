package com.antoniopaess.netlens.data.remote

import kotlinx.serialization.Serializable

/** Minimal response shape requested from restcountries.com. */
@Serializable
data class CountryDto(
    val name: CountryNameDto,
    val cca2: String? = null,
)

@Serializable
data class CountryNameDto(
    val common: String,
)
