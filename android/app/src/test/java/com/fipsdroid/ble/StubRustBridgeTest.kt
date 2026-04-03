package com.fipsdroid.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StubRustBridgeTest {

    private lateinit var bridge: StubRustBridge

    @Before
    fun setUp() {
        bridge = StubRustBridge()
    }

    @Test
    fun `initialize returns true`() {
        assertTrue(bridge.initialize())
    }

    @Test(expected = IllegalStateException::class)
    fun `sendBytes throws before initialize`() {
        bridge.sendBytes(byteArrayOf(0x01))
    }

    @Test
    fun `sendBytes succeeds after initialize`() {
        bridge.initialize()
        bridge.sendBytes(byteArrayOf(0x01, 0x02, 0x03))
    }

    @Test
    fun `registerCallback and unregisterCallback do not throw`() {
        val callback = object : BridgeCallback {
            override fun onDataReceived(data: ByteArray) {}
            override fun onConnectionStateChanged(connected: Boolean) {}
            override fun onError(message: String) {}
        }
        bridge.registerCallback(callback)
        bridge.unregisterCallback()
    }

    @Test
    fun `shutdown resets initialized state`() {
        bridge.initialize()
        bridge.sendBytes(byteArrayOf(0x01))
        bridge.shutdown()
        try {
            bridge.sendBytes(byteArrayOf(0x01))
            fail("Expected IllegalStateException after shutdown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not initialized"))
        }
    }
}
