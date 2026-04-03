# Android L2CAP BLE API Research

## Overview

L2CAP (Logical Link Control and Adaptation Protocol) Connection-Oriented Channels (CoC) provide a connection-oriented, streaming transport over Bluetooth Low Energy with credit-based flow control. Support was added in Android Q (API 29).

## Android API

### Client-Side API (Android Q+)

```kotlin
// Create an L2CAP channel (secure, requires authentication/bonding)
val socket = device.createL2capChannel(PSM_FIPS) // throws IOException

// Create an insecure L2CAP channel (no authentication)
val socket = device.createInsecureL2capChannel(PSM_FIPS) // throws IOException

// Create a listening server socket
val serverSocket = adapter.listenUsingL2capChannel(PSM_FIPS)

// Get the assigned PSM for server socket
val psm = serverSocket.psm
```

**Key Methods:**
- `BluetoothDevice.createL2capChannel(int psm)`: Create secure L2CAP channel
- `BluetoothDevice.createInsecureL2capChannel(int psm)`: Create insecure L2CAP channel
- `BluetoothAdapter.listenUsingL2capChannel(int psm)`: Create listening server socket
- `BluetoothAdapter.listenUsingInsecureL2capChannel(int psm)`: Create insecure listening server socket
- `BluetoothServerSocket.psm`: Get the assigned PSM value

### PSM Parameter

- **PSM (Protocol/Service Multiplexer)**: 16-bit value identifying the L2CAP service
- **PSM 0x0085**: Commonly used for FIPS device authentication
- **Dynamic PSM**: For server sockets, Android assigns a dynamic PSM
- **Static PSM**: User-defined static PSM values (0x0001-0x00FF)

## Threading Requirements

**CRITICAL: L2CAP operations must run on a background thread.**

```kotlin
// WRONG: Calling on main thread
device.createL2capChannel(0x0085) // Will likely crash or throw

// CORRECT: Running on background thread
Thread {
    try {
        val socket = device.createL2capChannel(0x0085)
        // Use socket...
    } catch (e: IOException) {
        // Handle error
    }
}.start()
```

## Threading Requirements

**CRITICAL: L2CAP operations must run on a background thread.**

Calling L2CAP methods on the main thread causes:
- `java.lang.IllegalStateException`: "Calling this from a background thread requires ALLOW_BG_ACCESS permission"
- Crashes on many devices

**Required Permission (Android 12+):**
```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

## Socket Usage Pattern

```kotlin
val socket = device.createL2capChannel(0x0085)

// Get input and output streams
val inputStream = socket.inputStream
val outputStream = socket.outputStream

// Read bytes (blocks until data is available)
val buffer = ByteArray(1024)
val bytesRead = inputStream.read(buffer)

// Write bytes
val data = byteArrayOf(0x01, 0x02, 0x03)
outputStream.write(data)

// Close connection
socket.close()
```

## Error Handling

### Common IOException Types

```kotlin
try {
    val socket = device.createL2capChannel(0x0085)
} catch (e: IOException) {
    when (e.message) {
        "Invalid argument" -> {
            // PSM invalid or device doesn't support L2CAP
            // Common on Samsung devices
        }
        "Timeout" -> {
            // Device busy or GATT operation in progress
        }
        "Device not connected" -> {
            // Must be paired and connected first
        }
    }
} catch (e: SecurityException) {
    // Missing BLUETOOTH_CONNECT permission
}
```

## Device Fragmentation Issues

### Known Fragmentation Problems

#### Samsung Devices (Android 10-13)

**Issue:** `java.io.IOException: Invalid argument`

- **Trigger:** Calling `createL2capChannel()` or `createInsecureL2capChannel()`
- **Root Cause:** Samsung's Bluetooth stack implementation has issues with L2CAP on certain device models
- **Workaround:** Some users report success on Samsung S21/S22/S23, but not all
- **Status:** Open issue - no official Samsung response

**Symptoms:**
- Connection attempt fails immediately with "Invalid argument"
- No connection established
- Connection established but read/write fails

#### Device-Specific Limitations

**Android 8.1 (API 27):**
- L2CAP supported but single credit at a time
- Maximum 1 packet per connection event
- Slow transfer rates
- Bug: `read(byte[])` reports -1 for empty packets instead of 0

**Android 9 (API 28):**
- Multiple credits supported
- Better performance than Oreo
- Partial L2CAP support

**Android 10+ (API 29+):**
- Full L2CAP CoC support
- Better fragmentation handling
- Generally reliable on Pixel devices

### Reliable Devices

**Pixel (3/4/5/6/7/8/9):**
- ✓ Works reliably
- ✓ No fragmentation issues
- ✓ Consistent performance

**Samsung (Recent Models):**
- ? Mixed results (S21/S22/S23 show some success)
- ? Not fully reliable
- ? Requires testing on each device

**Other Manufacturers:**
- ? Unknown reliability
- ? Limited community testing
- ? Recommendation: Test thoroughly

### Nordic Semiconductor Devices

**Confirmed Working:**
- ✓ nRF52840 / nRF52833 (Adafruit, SparkFun)
- ✓ Working with L2CAP CoC
- ✓ No fragmentation issues reported

## PSM Selection

### Static vs Dynamic PSM

**Static PSM (User-Defined):**
```kotlin
// Define in UDL or configuration
const val PSM_FIPS = 0x0085
const val PSM_HID = 0x0040
const val PSM_SDP = 0x0001

val socket = device.createL2capChannel(PSM_FIPS)
```

**Dynamic PSM (Server-Side):**
```kotlin
// Server side - get assigned PSM
val serverSocket = adapter.listenUsingL2capChannel(0xFFFF) // Request any free PSM
val psm = serverSocket.psm // Get assigned PSM
// Tell client to use this PSM
```

### PSM Range Guidelines

- **0x0001-0x00FF**: Static, user-defined
- **0x0100-0xFFFF**: Dynamic (used by Android internally)

## Code Example: Complete L2CAP Client

```kotlin
class L2CapClient(private val device: BluetoothDevice) {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    suspend fun connect(psm: Int = 0x0085): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Create L2CAP socket on background thread
            socket = device.createL2capChannel(psm)
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            Log.d(TAG, "L2CAP connected, PSM: $psm")

            // Wait for data
            startReading()

            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect L2CAP", e)
            cleanup()
            Result.failure(e)
        }
    }

    suspend fun write(data: ByteArray): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val bytesWritten = outputStream?.write(data) ?: return@withContext Result.failure(
                IOException("Not connected")
            )
            outputStream?.flush()
            Result.success(bytesWritten)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write L2CAP", e)
            Result.failure(e)
        }
    }

    private fun startReading() {
        val reader = thread {
            try {
                val buffer = ByteArray(1024)
                while (true) {
                    val bytesRead = inputStream?.read(buffer) ?: break
                    if (bytesRead > 0) {
                        val data = buffer.copyOfRange(0, bytesRead)
                        Log.d(TAG, "Received ${data.size} bytes")
                        // Process data...
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "L2CAP read error", e)
            }
        }
        reader.start()
    }

    fun close() {
        cleanup()
    }

    private fun cleanup() {
        inputStream?.close()
        outputStream?.close()
        socket?.close()
        socket = null
    }

    companion object {
        private const val TAG = "L2CapClient"
    }
}
```

## Best Practices

1. **Always run L2CAP on background thread** - Crashes on main thread
2. **Check device compatibility** - Test on target devices
3. **Handle IOException gracefully** - Many devices have partial L2CAP support
4. **Use suspend functions with Dispatchers.IO** - Modern Kotlin coroutine approach
5. **Close socket properly** - Release Bluetooth resources
6. **Implement timeout** - Don't block indefinitely on connection
7. **Use proper error handling** - Log all L2CAP errors for debugging

## Android Version Support

| API Level | L2CAP Support | Notes |
|-----------|---------------|-------|
| 27 (Oreo) | Partial | Single credit, slow transfer |
| 28 (Pie) | Partial | Multiple credits, better performance |
| 29 (Q) | Full | Stable L2CAP CoC support |
| 30 (R) | Full | Fully stable |
| 31 (S) | Full | Full L2CAP support |
| 32 (S++) | Full | Improved Bluetooth stack |
| 33+ | Full | Current stable API |

## References

- [Android BLE L2CAP Documentation](https://developer.android.com/guide/topics/connectivity/bluetooth-le#l2cap)
- [Stack Overflow: L2CAP Socket in Android](https://stackoverflow.com/questions/51614736/how-can-i-instantiate-a-l2cap-socket-in-android)
- [Nordic Android BLE Library Issues](https://github.com/nordicsemi/Android-BLE-Library/issues/463)
