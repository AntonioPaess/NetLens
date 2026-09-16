package com.antoniopaess.netlens

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** The application boundary owns the dependency graph assembled by the app module. */
@HiltAndroidApp
class NetLensApplication : Application()
