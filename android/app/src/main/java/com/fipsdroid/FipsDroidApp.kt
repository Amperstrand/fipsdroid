package com.fipsdroid

import android.app.Application
import android.util.Log

class FipsDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }

    companion object {
        private const val TAG = "FipsDroidApp"

        var nativeLibAvailable = false
            private set

        init {
            try {
                System.loadLibrary("fipsdroid_core")
                nativeLibAvailable = true
                Log.i(TAG, "UniFFI native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                nativeLibAvailable = false
                Log.w(TAG, "Native library not available — demo mode: ${e.message}")
            } catch (e: Exception) {
                nativeLibAvailable = false
                Log.w(TAG, "Native library load failed: ${e.message}")
            }
        }
    }
}
