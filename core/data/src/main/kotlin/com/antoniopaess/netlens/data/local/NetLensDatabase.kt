package com.antoniopaess.netlens.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version-one local store for the region cache and diagnostic event history.
 *
 * Exported schema JSON is checked in alongside this database so a future schema
 * change can add an explicit migration instead of silently recreating data.
 */
@Database(
    entities = [RegionEntity::class, ConnectionEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NetLensDatabase : RoomDatabase() {
    /** Region cache DAO. */
    abstract fun regionDao(): RegionDao

    /** Persisted event DAO. */
    abstract fun connectionEventDao(): ConnectionEventDao
}
