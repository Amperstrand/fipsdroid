package com.fipsdroid.ble

interface BridgeCallback {
    fun onDataReceived(data: ByteArray)
    fun onConnectionStateChanged(connected: Boolean)
    fun onError(message: String)
}

// Contract for Rust FFI integration via UniFFI (Task 11).
// Data flow: Android BLE (L2CAP) <-> RustBridge <-> fipsdroid-core (Rust)
interface RustBridge {
    fun sendBytes(data: ByteArray)
    fun registerCallback(callback: BridgeCallback)
    fun unregisterCallback()
    fun initialize(): Boolean
    fun shutdown()
}

class StubRustBridge : RustBridge {
    private var callback: BridgeCallback? = null
    private var initialized = false

    override fun sendBytes(data: ByteArray) {
        check(initialized) { "RustBridge not initialized. Call initialize() first." }
        android.util.Log.d("StubRustBridge", "sendBytes(${data.size} bytes) - stub, no-op")
    }

    override fun registerCallback(callback: BridgeCallback) {
        this.callback = callback
    }

    override fun unregisterCallback() {
        this.callback = null
    }

    override fun initialize(): Boolean {
        initialized = true
        android.util.Log.d("StubRustBridge", "initialize() - stub, no-op")
        return true
    }

    override fun shutdown() {
        initialized = false
        callback = null
        android.util.Log.d("StubRustBridge", "shutdown() - stub, no-op")
    }
}
