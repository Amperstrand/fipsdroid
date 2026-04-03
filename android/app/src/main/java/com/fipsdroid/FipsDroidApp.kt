package com.fipsdroid

import android.app.Application

class FipsDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Will load native library in Task 10
    }

    companion object {
        init {
            System.loadLibrary("fipsdroid_core")
        }
    }
}
