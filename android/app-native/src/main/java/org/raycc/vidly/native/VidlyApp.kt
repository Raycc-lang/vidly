package org.raycc.vidly.native

import android.app.Application

class VidlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}