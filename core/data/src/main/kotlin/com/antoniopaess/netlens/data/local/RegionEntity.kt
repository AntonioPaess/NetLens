package com.antoniopaess.netlens.data.local

import androidx.room.Entity

/** Room row for a region returned by the public REST source. */
@Entity(tableName = "regions")
data class RegionEntity(
    @androidx.room.PrimaryKey
    val code: String,
    val name: String,
    val diagnosticLatencyMillis: Long,
)
