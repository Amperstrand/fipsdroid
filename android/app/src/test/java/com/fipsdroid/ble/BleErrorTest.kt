package com.fipsdroid.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BleErrorTest {

    @Test
    fun `AdapterUnavailable has correct message`() {
        val error = BleError.AdapterUnavailable()
        assertEquals("Bluetooth adapter not available", error.message)
    }

    @Test
    fun `AdapterDisabled has correct message`() {
        val error = BleError.AdapterDisabled()
        assertEquals("Bluetooth adapter is disabled", error.message)
    }

    @Test
    fun `PermissionsNotGranted has correct message`() {
        val error = BleError.PermissionsNotGranted()
        assertEquals("BLE permissions not granted", error.message)
    }

    @Test
    fun `ConnectionTimeout includes address`() {
        val error = BleError.ConnectionTimeout("AA:BB:CC:DD:EE:FF")
        assertTrue(error.message!!.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(error.message!!.contains("timed out"))
    }

    @Test
    fun `ConnectionFailed includes address and cause`() {
        val cause = IOException("socket closed")
        val error = BleError.ConnectionFailed("AA:BB:CC:DD:EE:FF", cause)
        assertTrue(error.message!!.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(error.message!!.contains("socket closed"))
        assertEquals(cause, error.cause)
    }

    @Test
    fun `InvalidAddress includes address`() {
        val error = BleError.InvalidAddress("INVALID")
        assertTrue(error.message!!.contains("INVALID"))
    }

    @Test
    fun `all BleError subclasses are Exceptions`() {
        val errors: List<BleError> = listOf(
            BleError.AdapterUnavailable(),
            BleError.AdapterDisabled(),
            BleError.PermissionsNotGranted(),
            BleError.ConnectionTimeout("AA:BB:CC:DD:EE:FF"),
            BleError.ConnectionFailed("AA:BB:CC:DD:EE:FF", IOException()),
            BleError.InvalidAddress("bad")
        )
        errors.forEach { error ->
            assertTrue(error is Exception)
            assertNotNull(error.message)
        }
    }
}
