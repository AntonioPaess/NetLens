package com.antoniopaess.netlens.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Local region operations used by the cache-first repository. */
@Dao
interface RegionDao {
    /** Read a stable ordering for deterministic screen and test output. */
    @Query("SELECT * FROM regions ORDER BY name COLLATE NOCASE ASC")
    suspend fun readAll(): List<RegionEntity>

    /** Replace the snapshot atomically after a successful remote refresh. */
    @Transaction
    suspend fun replaceAll(regions: List<RegionEntity>) {
        deleteAll()
        insertAll(regions)
    }

    @Query("DELETE FROM regions")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(regions: List<RegionEntity>)
}
