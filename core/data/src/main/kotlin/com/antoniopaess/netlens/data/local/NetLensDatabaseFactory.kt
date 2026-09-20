package com.antoniopaess.netlens.data.local

import android.content.Context
import androidx.room.Room

/** Creates the version-one database for the Android composition root. */
object NetLensDatabaseFactory {
    /** Build the on-device Room store with migrations enabled for future versions. */
    fun create(context: Context): NetLensDatabase = Room.databaseBuilder(context, NetLensDatabase::class.java, DATABASE_NAME).build()

    private const val DATABASE_NAME = "netlens.db"
}
