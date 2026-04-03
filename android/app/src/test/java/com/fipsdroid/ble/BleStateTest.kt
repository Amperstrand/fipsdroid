package com.fipsdroid.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleStateTest {

    @Test
    fun `BleState sealed class has four variants`() {
        val states: List<BleState> = listOf(
            BleState.AdapterUnavailable,
            BleState.AdapterDisabled,
            BleState.PermissionsRequired,
            BleState.Ready
        )
        assertEquals(4, states.size)
    }

    @Test
    fun `BleState data objects have stable identity`() {
        val a = BleState.AdapterUnavailable
        val b = BleState.AdapterUnavailable
        assertEquals(a, b)

        val c = BleState.Ready
        val d = BleState.Ready
        assertEquals(c, d)
    }

    @Test
    fun `PSM_FIPS constant has expected value`() {
        assertEquals(0x0085, PSM_FIPS)
    }
}
