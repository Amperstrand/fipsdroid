package com.fipsdroid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "BleConnectionManager"
const val PSM_FIPS: Int = 0x0085
private const val CONNECTION_TIMEOUT_MS = 10_000L

class L2capConnection(
    private val socket: BluetoothSocket,
    val inputStream: InputStream,
    val outputStream: OutputStream
) : Closeable {

    val isConnected: Boolean
        get() = socket.isConnected

    val remoteAddress: String
        get() = socket.remoteDevice?.address ?: "unknown"

    override fun close() {
        try { inputStream.close() } catch (e: IOException) { Log.w(TAG, "Error closing input stream", e) }
        try { outputStream.close() } catch (e: IOException) { Log.w(TAG, "Error closing output stream", e) }
        try { socket.close() } catch (e: IOException) { Log.w(TAG, "Error closing socket", e) }
        Log.d(TAG, "L2CAP connection closed")
    }
}

sealed class BleError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AdapterUnavailable : BleError("Bluetooth adapter not available")
    class AdapterDisabled : BleError("Bluetooth adapter is disabled")
    class PermissionsNotGranted : BleError("BLE permissions not granted")
    class ConnectionTimeout(address: String) :
        BleError("Connection to $address timed out after ${CONNECTION_TIMEOUT_MS}ms")
    class ConnectionFailed(address: String, cause: Throwable) :
        BleError("L2CAP connection to $address failed: ${cause.message}", cause)
    class InvalidAddress(address: String) :
        BleError("Invalid Bluetooth address: $address")
}

class BleConnectionManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    private var currentConnection: L2capConnection? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, psm: Int = PSM_FIPS): Result<L2capConnection> =
        withContext(Dispatchers.IO) {
            try {
                val adapter = bluetoothAdapter
                    ?: return@withContext Result.failure(BleError.AdapterUnavailable())

                if (!adapter.isEnabled) {
                    return@withContext Result.failure(BleError.AdapterDisabled())
                }

                if (!areBlePermissionsGranted(context)) {
                    return@withContext Result.failure(BleError.PermissionsNotGranted())
                }

                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    return@withContext Result.failure(BleError.InvalidAddress(address))
                }

                disconnect()

                val device: BluetoothDevice = adapter.getRemoteDevice(address)
                Log.i(TAG, "Connecting to $address on PSM $psm")

                val socket = withTimeout(CONNECTION_TIMEOUT_MS) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    device.createInsecureL2capChannel(psm).also { socket ->
                        socket.connect()
                    }
                }

                val connection = L2capConnection(
                    socket = socket,
                    inputStream = socket.inputStream,
                    outputStream = socket.outputStream
                )

                currentConnection = connection
                Log.i(TAG, "L2CAP connected to $address on PSM $psm")
                Result.success(connection)

            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied during BLE connection", e)
                Result.failure(BleError.PermissionsNotGranted())
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Connection timeout to $address", e)
                Result.failure(BleError.ConnectionTimeout(address))
            } catch (e: IOException) {
                Log.e(TAG, "L2CAP connection failed to $address", e)
                Result.failure(BleError.ConnectionFailed(address, e))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid address: $address", e)
                Result.failure(BleError.InvalidAddress(address))
            }
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        currentConnection?.let { connection ->
            Log.i(TAG, "Disconnecting from ${connection.remoteAddress}")
            connection.close()
            currentConnection = null
        }
    }

    fun getCurrentConnection(): L2capConnection? = currentConnection

    fun isConnected(): Boolean = currentConnection?.isConnected == true
}
