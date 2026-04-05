package com.fipsdroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fipsdroid.ble.BleConnectionManager
import com.fipsdroid.ble.L2capConnection
import com.fipsdroid.ble.PSM_FIPS
import com.fipsdroid.ble.StubRustBridge
import com.fipsdroid.ui.ConnectionState
import com.fipsdroid.ui.HeartbeatStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fipsdroid_core.FipsDroidBridge
import uniffi.fipsdroid_core.FipsDroidCallback
import uniffi.fipsdroid_core.FipsDroidException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BridgeViewModel"
private const val RELAY_POLL_INTERVAL_MS = 50L
private const val RECONNECT_DELAY_MS = 3000L
private val OBSERVED_PSM_FALLBACKS = listOf(0x0085, 192, 194, 195, 196, 197)

enum class BridgeMode {
    UNIFFI,
    DEMO_PING_PONG
}

class BridgeViewModel(private val context: Context) : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _heartbeatStatus = MutableStateFlow(HeartbeatStatus())
    val heartbeatStatus: StateFlow<HeartbeatStatus> = _heartbeatStatus.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _peerAddress = MutableStateFlow("00:00:00:00:00:00")
    val peerAddress: StateFlow<String> = _peerAddress.asStateFlow()

    private val bleConnectionManager = BleConnectionManager(context)
    private var currentConnection: L2capConnection? = null

    private var uniffiBridge: FipsDroidBridge? = null
    private var bridgeMode: BridgeMode? = null
    private var relayJob: Job? = null
    private var heartbeatPollJob: Job? = null
    private val isConnecting = AtomicBoolean(false)
    private val nativeLibLoaded = AtomicBoolean(false)

    private val testLocalPrivkey = ByteArray(32) { it.toByte() }
    private val testPeerPubkey = ByteArray(33) { if (it == 0) 0x02 else it.toByte() }

    init {
        detectNativeLib()
    }

    private fun detectNativeLib() {
        try {
            System.loadLibrary("fipsdroid_core")
            nativeLibLoaded.set(true)
            appendLog("I", "UniFFI native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibLoaded.set(false)
            appendLog("W", "Native library not available — running in demo mode (ping-pong only)")
        } catch (e: Exception) {
            nativeLibLoaded.set(false)
            appendLog("W", "Native library load error: ${e.message} — running in demo mode")
        }

        if (nativeLibLoaded.get()) {
            try {
                com.sun.jna.Native.load("fipsdroid_core", com.sun.jna.Library::class.java)
                appendLog("I", "JNA dispatch available — UniFFI bridge mode enabled")
            } catch (e: UnsatisfiedLinkError) {
                nativeLibLoaded.set(false)
                appendLog("W", "JNA dispatch not available — falling back to demo mode")
            } catch (e: NoClassDefFoundError) {
                nativeLibLoaded.set(false)
                appendLog("W", "JNA classes not found — falling back to demo mode")
            } catch (e: Exception) {
                nativeLibLoaded.set(false)
                appendLog("W", "JNA dispatch test failed — falling back to demo mode")
            }
        }
    }

    fun setPeerAddress(address: String) {
        _peerAddress.value = address
    }

    fun isBluetoothEnabled(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!isConnecting.compareAndSet(false, true)) {
            appendLog("W", "Connect already in progress")
            return
        }

        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled. Please enable Bluetooth and try again.")
            appendLog("E", "Bluetooth adapter is disabled — cannot connect")
            isConnecting.set(false)
            return
        }

        val address = _peerAddress.value
        if (address == "00:00:00:00:00:00") {
            appendLog("W", "Peer address is placeholder — starting BLE scan to discover Mac")
            _connectionState.value = ConnectionState.Connecting
            viewModelScope.launch {
                scanAndConnect()
            }
            return
        }

        _connectionState.value = ConnectionState.Connecting
        appendLog("I", "Connecting to peer $address via BLE L2CAP")

        if (nativeLibLoaded.get()) {
            connectWithUniFFI(address)
        } else {
            connectDemoMode(address)
        }
    }

    fun disconnect() {
        appendLog("I", "Disconnecting...")
        _connectionState.value = ConnectionState.Disconnecting

        relayJob?.cancel()
        relayJob = null
        heartbeatPollJob?.cancel()
        heartbeatPollJob = null

        try {
            uniffiBridge?.stop()
            uniffiBridge?.close()
        } catch (_: Exception) {}
        uniffiBridge = null

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                bleConnectionManager.disconnect()
                currentConnection = null
            }
            _connectionState.value = ConnectionState.Disconnected
            appendLog("I", "Disconnected")
            isConnecting.set(false)
        }
    }

    private fun connectWithUniFFI(address: String) {
        bridgeMode = BridgeMode.UNIFFI
        viewModelScope.launch {
            try {
                uniffiBridge = FipsDroidBridge(
                    peerAddress = address,
                    peerPubkey = testPeerPubkey,
                    localPrivkey = testLocalPrivkey
                )

                val callback = object : FipsDroidCallback {
                    override fun onStateChanged(state: uniffi.fipsdroid_core.ConnectionState) {
                        val uiState = mapUniFFIState(state)
                        _connectionState.value = uiState
                        appendLog("I", "State changed: ${mapStateName(uiState)}")
                    }

                    override fun onHeartbeat(status: uniffi.fipsdroid_core.HeartbeatStatus) {
                        _heartbeatStatus.value = HeartbeatStatus(
                            sentCount = status.sentCount,
                            receivedCount = status.receivedCount,
                            lastReceived = status.lastReceived
                        )
                    }

                    override fun onError(error: String) {
                        appendLog("E", "Bridge error: $error")
                        _connectionState.value = ConnectionState.Error(error)
                    }
                }

                val connection = tryL2capConnect(address)
                if (connection != null) {
                    currentConnection = connection
                    appendLog("I", "BLE L2CAP connected to $address — starting UniFFI bridge")
                    startUniFFIRelay(connection, callback)
                } else {
                    appendLog("E", "BLE connection failed on all PSM candidates")
                    _connectionState.value = ConnectionState.Error("BLE connection failed on all PSM candidates")
                    isConnecting.set(false)
                }
            } catch (e: FipsDroidException) {
                appendLog("E", "UniFFI bridge error: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Bridge error")
                isConnecting.set(false)
            } catch (e: Exception) {
                appendLog("E", "Unexpected error: ${e::class.java.simpleName}: ${e.message}")
                _connectionState.value = ConnectionState.Error("${e::class.java.simpleName}: ${e.message}")
                isConnecting.set(false)
            }
        }
    }

    private fun startUniFFIRelay(connection: L2capConnection, callback: FipsDroidCallback) {
        relayJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                uniffiBridge?.start(callback)
                appendLog("I", "UniFFI bridge started — beginning relay loop")

                while (isActive) {
                    try {
                        val buf = ByteArray(4096)
                        val bytesRead = connection.inputStream.read(buf)
                        when {
                            bytesRead > 0 -> {
                                val data = buf.copyOfRange(0, bytesRead)
                                uniffiBridge?.feedIncoming(data)
                            }
                            bytesRead == -1 -> {
                                appendLog("E", "BLE stream closed by peer")
                                break
                            }
                        }

                        val outgoing = uniffiBridge?.pollOutgoing()
                        if (outgoing != null) {
                            connection.outputStream.write(outgoing)
                            connection.outputStream.flush()
                        }

                        delay(RELAY_POLL_INTERVAL_MS)
                    } catch (e: Exception) {
                        if (isActive) {
                            appendLog("E", "Relay error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("E", "Relay setup error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.Disconnected
                    isConnecting.set(false)
                }
            }
        }

        heartbeatPollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val status = uniffiBridge?.getHeartbeatStatus()
                    if (status != null) {
                        _heartbeatStatus.value = HeartbeatStatus(
                            sentCount = status.sentCount,
                            receivedCount = status.receivedCount,
                            lastReceived = status.lastReceived
                        )
                    }
                } catch (_: Exception) {}
                delay(1000L)
            }
        }
    }

    private fun connectDemoMode(address: String) {
        bridgeMode = BridgeMode.DEMO_PING_PONG
        viewModelScope.launch {
            appendLog("I", "Demo mode: connecting via BLE L2CAP for ping-pong test")

            val connection = tryL2capConnect(address)
            if (connection != null) {
                currentConnection = connection
                _connectionState.value = ConnectionState.Connected
                appendLog("I", "BLE L2CAP connected (demo mode)")
                startDemoPingPong(connection)
            } else {
                appendLog("E", "BLE connection failed on all PSM candidates")
                _connectionState.value = ConnectionState.Error("BLE connection failed on all PSM candidates")
                isConnecting.set(false)
            }
        }
    }

    private suspend fun tryL2capConnect(address: String): L2capConnection? {
        appendLog("I", "Trying PSM candidates: ${OBSERVED_PSM_FALLBACKS.joinToString(", ") { "0x%04X".format(it) }}")
        for ((index, psm) in OBSERVED_PSM_FALLBACKS.withIndex()) {
            appendLog("I", "L2CAP attempt ${index + 1}/${OBSERVED_PSM_FALLBACKS.size} using PSM=$psm")
            val result = withContext(Dispatchers.IO) {
                bleConnectionManager.connect(address, psm)
            }
            result.fold(
                onSuccess = { conn ->
                    appendLog("I", "L2CAP connected on PSM=$psm")
                    return conn
                },
                onFailure = { error ->
                    appendLog("W", "PSM=$psm failed: ${error.message}")
                }
            )
        }
        return null
    }

    private fun startDemoPingPong(connection: L2capConnection) {
        relayJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pingData = "PING\n".toByteArray()
                var pingCount = 0uL
                var pongCount = 0uL

                while (isActive) {
                    try {
                        connection.outputStream.write(pingData)
                        connection.outputStream.flush()
                        pingCount++
                        _heartbeatStatus.value = HeartbeatStatus(
                            sentCount = pingCount,
                            receivedCount = pongCount,
                            lastReceived = _heartbeatStatus.value.lastReceived
                        )
                        appendLog("D", "TX PING #$pingCount")

                        val buffer = ByteArray(4096)
                        val bytesRead = withContext(Dispatchers.IO) {
                            connection.inputStream.read(buffer)
                        }

                        when {
                            bytesRead > 0 -> {
                                pongCount++
                                val response = String(buffer, 0, bytesRead).trim()
                                val now = Instant.now().epochSecond
                                _heartbeatStatus.value = HeartbeatStatus(
                                    sentCount = pingCount,
                                    receivedCount = pongCount,
                                    lastReceived = now.toULong()
                                )
                                _connectionState.value = ConnectionState.Established
                                appendLog("I", "RX PONG #$pongCount (${bytesRead} bytes): $response")
                            }
                            bytesRead == -1 -> {
                                appendLog("E", "Peer closed connection")
                                break
                            }
                        }

                        delay(5000L)
                    } catch (e: Exception) {
                        if (isActive) {
                            appendLog("E", "Ping-pong error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("E", "Demo relay error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.Disconnected
                    isConnecting.set(false)
                }
            }
        }
    }

    private suspend fun scanAndConnect() {
        appendLog("I", "Starting BLE scan for FIPS service")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: run {
            appendLog("E", "Bluetooth adapter unavailable")
            _connectionState.value = ConnectionState.Error("Bluetooth adapter unavailable")
            isConnecting.set(false)
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            appendLog("E", "BLE scanner unavailable")
            _connectionState.value = ConnectionState.Error("BLE scanner unavailable")
            isConnecting.set(false)
            return
        }

        val serviceUuid = java.util.UUID.fromString("9C90B790-2CC5-42C0-9F87-C9CC40648F4C")
        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(serviceUuid))
            .build()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var found = false
        var scanCallback: android.bluetooth.le.ScanCallback? = null
        scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val address = try { device.address } catch (_: SecurityException) { return }
                val name = try { device.name ?: "<unnamed>" } catch (_: SecurityException) { "<no-perm>" }
                val uuids = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == serviceUuid } == true

                if (!uuids && !name.contains("FIPS", ignoreCase = true)) return

                appendLog("I", "Found device: $address ($name) RSSI=${result.rssi}")
                found = true
                _peerAddress.value = address
                scanCallback?.let { cb ->
                    scanner.stopScan(cb)
                    viewModelScope.launch {
                        _connectionState.value = ConnectionState.Connecting
                        appendLog("I", "Connecting to discovered peer $address via BLE L2CAP")
                        if (nativeLibLoaded.get()) {
                            connectWithUniFFI(address)
                        } else {
                            connectDemoMode(address)
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                appendLog("E", "Scan failed: code=$errorCode")
                _connectionState.value = ConnectionState.Error("BLE scan failed (code $errorCode)")
                isConnecting.set(false)
            }
        }

        scanner.startScan(listOf(filter), settings, scanCallback)
        appendLog("I", "BLE scan started for service $serviceUuid")

        delay(15_000L)
        scanCallback?.let { scanner.stopScan(it) }

        if (!found) {
            appendLog("W", "No FIPS device found in 15s scan — stopping")
            _connectionState.value = ConnectionState.Error("No FIPS device found. Is the Mac relay running?")
            isConnecting.set(false)
        }
    }

    private fun mapUniFFIState(state: uniffi.fipsdroid_core.ConnectionState): ConnectionState {
        return when (state) {
            is uniffi.fipsdroid_core.ConnectionState.Disconnected -> ConnectionState.Disconnected
            is uniffi.fipsdroid_core.ConnectionState.Connecting -> ConnectionState.Connecting
            is uniffi.fipsdroid_core.ConnectionState.Connected -> ConnectionState.Connected
            is uniffi.fipsdroid_core.ConnectionState.Handshaking -> ConnectionState.Handshaking
            is uniffi.fipsdroid_core.ConnectionState.Established -> ConnectionState.Established
            is uniffi.fipsdroid_core.ConnectionState.Disconnecting -> ConnectionState.Disconnecting
            is uniffi.fipsdroid_core.ConnectionState.Error -> ConnectionState.Error(state.v1)
        }
    }

    private fun mapStateName(state: ConnectionState): String {
        return when (state) {
            is ConnectionState.Disconnected -> "Disconnected"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Handshaking -> "Handshaking"
            is ConnectionState.Established -> "Established"
            is ConnectionState.Disconnecting -> "Disconnecting"
            is ConnectionState.Error -> "Error: ${state.message}"
        }
    }

    private fun appendLog(level: String, message: String) {
        val line = "[${Instant.now()}][$level] $message"
        Log.println(
            when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                "D" -> Log.DEBUG
                else -> Log.INFO
            },
            TAG, line
        )
        val current = _logLines.value.toMutableList()
        current.add(line)
        while (current.size > 250) current.removeAt(0)
        _logLines.value = current
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
