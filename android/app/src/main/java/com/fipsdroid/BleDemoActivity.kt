package com.fipsdroid

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fipsdroid.ble.BleConnectionManager
import com.fipsdroid.ble.PSM_FIPS
import com.fipsdroid.ui.theme.FipsDroidTheme
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

private const val TAG = "BleDemo"
private val SERVICE_UUID = UUID.fromString("9C90B790-2CC5-42C0-9F87-C9CC40648F4C")
private const val INTENT_EXTRA_PSM_OVERRIDE = "psm_override"
private const val PSM_DYNAMIC_OBSERVED = 194

class BleDemoActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var fallbackScanJob: Job? = null
    private var activeScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanAttempt: Int = 0
    private var usingServiceFilter: Boolean = true
    private var scanResultCount: Int = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var bleConnectionManager: BleConnectionManager

    private val uiStatus = mutableStateOf("Ready to scan")
    private val uiIsScanning = mutableStateOf(false)
    private val uiLogs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleConnectionManager = BleConnectionManager(this)

        setContent {
            FipsDroidTheme {
                BleDemoScreen(
                    onStartScan = { startBleScan() },
                    onStopScan = { stopBleScan("User pressed Stop") },
                    status = uiStatus.value,
                    isScanning = uiIsScanning.value,
                    logLines = uiLogs,
                    buildInfo = appInfoString(),
                    psmInfo = resolveCandidatePsms().joinToString(", ") { "0x%04X".format(it) }
                )
            }
        }

        logInfo("BleDemoActivity created | ${appInfoString()}")
        logInfo("Bluetooth adapter present=${bluetoothAdapter != null} enabled=${bluetoothAdapter?.isEnabled == true}")
        logInfo("Candidate PSM list on launch: ${resolveCandidatePsms()}")

        val autoScan = intent?.getBooleanExtra("auto_scan", true) ?: true
        if (autoScan) {
            logInfo("Auto-scan is enabled; starting scan immediately")
            startBleScan()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        val denied = permissions.filterValues { !it }.keys
        logInfo("Permission callback received: $permissions")
        if (allGranted) {
            logInfo("All permissions granted from launcher callback")
            startBleScan()
        } else {
            logError("BLE permissions denied: $denied")
            uiStatus.value = "Permissions denied"
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val permissionStates = permissions.associateWith {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        logDebug("Permission snapshot before scan: $permissionStates")

        val allGranted = permissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        return if (allGranted) {
            logDebug("Permissions already granted")
            true
        } else {
            logInfo("Requesting missing BLE permissions")
            permissionLauncher.launch(permissions)
            false
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startBleScan() {
        scanAttempt += 1
        uiStatus.value = "Starting scan #$scanAttempt"

        if (!checkAndRequestPermissions()) return

        val locationMode = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF
        )
        logInfo("System location_mode=$locationMode (0=off, 3=high accuracy)")

        val adapter = bluetoothAdapter
        if (adapter == null) {
            logError("Cannot start scan: Bluetooth adapter is null")
            uiStatus.value = "Bluetooth adapter unavailable"
            return
        }

        if (!adapter.isEnabled) {
            logError("Cannot start scan: Bluetooth adapter is disabled")
            uiStatus.value = "Bluetooth disabled"
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            logError("Cannot start scan: bluetoothLeScanner is null")
            uiStatus.value = "BLE scanner unavailable"
            return
        }

        val attemptId = scanAttempt
        scanResultCount = 0
        usingServiceFilter = true
        logInfo("Starting BLE scan #$attemptId for service UUID: $SERVICE_UUID")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        logDebug("Scan #$attemptId filter=[serviceUuid=$SERVICE_UUID] settings=[mode=LOW_LATENCY]")

        scanJob = scope.launch {
            try {
                stopBleScan("Pre-clean before starting scan #$attemptId")
                activeScanner = scanner
                scanner.startScan(listOf(filter), settings, scanCallback)
                uiIsScanning.value = true
                uiStatus.value = "Scanning (attempt #$attemptId)"
                logInfo("BLE scan #$attemptId started successfully")

                fallbackScanJob?.cancel()
                fallbackScanJob = scope.launch {
                    delay(12_000L)
                    if (uiIsScanning.value && usingServiceFilter && activeScanner != null) {
                        logInfo("No match after 12s with service filter; switching to broad discovery scan")
                        restartScanWithoutFilter(scanner, attemptId)
                    }
                }
            } catch (e: SecurityException) {
                logError("Security exception during scan start: ${e.message}")
                Log.e(TAG, "Security exception during scan start", e)
                uiStatus.value = "Scan start failed (permission)"
            }
        }
    }

    private fun restartScanWithoutFilter(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        attemptId: Int
    ) {
        val broadSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.stopScan(scanCallback)
            scanner.startScan(emptyList(), broadSettings, scanCallback)
            usingServiceFilter = false
            uiStatus.value = "Scanning broad mode (attempt #$attemptId)"
            logInfo("Broad scan started for discovery diagnostics (no service filter)")
        } catch (e: SecurityException) {
            logError("Failed to start broad scan: ${e.message}")
            Log.e(TAG, "Failed to start broad scan", e)
        }
    }

    private fun stopBleScan(reason: String) {
        try {
            activeScanner?.stopScan(scanCallback)
            activeScanner = null
            logInfo("BLE scan stopped | reason=$reason")
        } catch (e: SecurityException) {
            logError("Security exception during scan stop: ${e.message}")
            Log.e(TAG, "Security exception during scan stop", e)
        }
        scanJob?.cancel()
        fallbackScanJob?.cancel()
        usingServiceFilter = true
        uiIsScanning.value = false
        if (!uiStatus.value.startsWith("Connected") && !uiStatus.value.startsWith("Connecting")) {
            uiStatus.value = "Stopped"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = try {
                device.name ?: "<no-name>"
            } catch (e: SecurityException) {
                "<name-permission-denied>"
            }
            val address = try {
                device.address
            } catch (e: SecurityException) {
                "unknown"
            }

            val recordUuids = result.scanRecord?.serviceUuids
                ?.joinToString(",") { it.uuid.toString() }
                ?: "<none>"
            val hasTargetService = result.scanRecord?.serviceUuids
                ?.any { it.uuid == SERVICE_UUID } == true
            val matchesByName = deviceName.contains("FIPS", ignoreCase = true)

            scanResultCount += 1

            logInfo(
                "Scan result #$scanResultCount | callbackType=$callbackType mode=${if (usingServiceFilter) "filtered" else "broad"} " +
                    "address=$address name=$deviceName rssi=${result.rssi} connectable=${result.isConnectable} uuids=$recordUuids"
            )

            if (!hasTargetService && !matchesByName) {
                logDebug("Ignoring device $address because it matched neither target UUID nor expected name pattern")
                return
            }

            uiStatus.value = "Found $address (RSSI ${result.rssi})"
            stopBleScan("Found matching service on $address")
            connectToDevice(address)
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "internal error"
                else -> "unknown"
            }
            logError("Scan failed with errorCode=$errorCode ($reason)")
            uiStatus.value = "Scan failed: $reason"
        }
    }

    private fun connectToDevice(address: String) {
        val psmCandidates = resolveCandidatePsms()
        logInfo("Connecting to $address via candidate PSMs: $psmCandidates")
        uiStatus.value = "Connecting to $address"

        connectionJob = scope.launch {
            var lastErrorMessage = "unknown"
            for ((index, psm) in psmCandidates.withIndex()) {
                logInfo("Connection attempt ${index + 1}/${psmCandidates.size} using PSM=$psm")
                val result = bleConnectionManager.connect(address, psm)

                var shouldStopTrying = false
                result.fold(
                    onSuccess = { connection ->
                        shouldStopTrying = true
                        uiStatus.value = "Connected to $address on PSM $psm"
                        logInfo("L2CAP connected to $address on PSM $psm")
                        performPingPong(connection, psm)
                    },
                    onFailure = { error ->
                        lastErrorMessage = error.message ?: error::class.java.simpleName
                        logError("Connection failed on PSM $psm: $lastErrorMessage")
                    }
                )

                if (shouldStopTrying) {
                    return@launch
                }
            }

            uiStatus.value = "Connection failed"
            logError("All connection attempts failed for $address. Last error: $lastErrorMessage")
        }
    }

    private suspend fun performPingPong(
        connection: com.fipsdroid.ble.L2capConnection,
        psm: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val pingData = "PING\n".toByteArray()
            connection.outputStream.write(pingData)
            connection.outputStream.flush()
            logInfo("TX bytes(${pingData.size}) on PSM=$psm: ${pingData.toHexString(pingData.size)}")
            logInfo("TX text: ${String(pingData).trim()}")

            val buffer = ByteArray(1024)
            val bytesRead = withTimeoutOrNull(7_000L) {
                connection.inputStream.read(buffer)
            } ?: Int.MIN_VALUE

            when {
                bytesRead > 0 -> {
                    val response = String(buffer, 0, bytesRead).trim()
                    logInfo("RX bytes($bytesRead): ${buffer.toHexString(bytesRead)}")
                    logInfo("RX text: $response")
                    uiStatus.value = "PING/PONG success"
                }

                bytesRead == -1 -> {
                    logError("RX stream closed by peer (bytesRead=-1)")
                    uiStatus.value = "Connection closed by peer"
                }

                bytesRead == Int.MIN_VALUE -> {
                    logError("RX timeout waiting for response after 7000ms")
                    uiStatus.value = "No response (timeout)"
                }

                else -> {
                    logError("No data received (bytesRead=$bytesRead)")
                    uiStatus.value = "No response"
                }
            }
        } catch (e: Exception) {
            logError("PING/PONG failed: ${e::class.java.simpleName}: ${e.message}")
            Log.e(TAG, "PING/PONG failed", e)
            uiStatus.value = "PING/PONG failed"
        } finally {
            try {
                connection.close()
                logDebug("Connection closed after ping/pong flow")
            } catch (e: Exception) {
                logError("Error while closing connection: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan("Activity destroyed")
        connectionJob?.cancel()
        scope.cancel()
        logInfo("BleDemoActivity destroyed")
    }

    private fun resolveCandidatePsms(): List<Int> {
        val psmFromIntent = intent?.getIntExtra(INTENT_EXTRA_PSM_OVERRIDE, -1) ?: -1
        val candidates = mutableListOf<Int>()

        if (psmFromIntent > 0) {
            candidates += psmFromIntent
        }
        candidates += PSM_FIPS
        if (!candidates.contains(PSM_DYNAMIC_OBSERVED)) {
            candidates += PSM_DYNAMIC_OBSERVED
        }

        return candidates.distinct()
    }

    private fun appInfoString(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = packageInfo.longVersionCode
            val updated = Instant.ofEpochMilli(packageInfo.lastUpdateTime)
            "v$versionName ($versionCode) apkUpdated=$updated"
        } catch (e: Exception) {
            "version=unknown (failed to read package info: ${e.message})"
        }
    }

    private fun logInfo(message: String) {
        Log.i(TAG, message)
        appendUiLog("I", message)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
        appendUiLog("D", message)
    }

    private fun logError(message: String) {
        Log.e(TAG, message)
        appendUiLog("E", message)
    }

    private fun appendUiLog(level: String, message: String) {
        val line = "[${Instant.now()}][$level] $message"
        runOnUiThread {
            uiLogs.add(line)
            while (uiLogs.size > 250) {
                uiLogs.removeAt(0)
            }
        }
    }

    private fun ByteArray.toHexString(length: Int): String {
        return take(length).joinToString(" ") { "%02X".format(it) }
    }
}

@Composable
fun BleDemoScreen(
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    status: String,
    isScanning: Boolean,
    logLines: List<String>,
    buildInfo: String,
    psmInfo: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "BLE L2CAP Demo",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Build: $buildInfo",
            style = MaterialTheme.typography.bodySmall
        )
        
        Text(
            text = "Service UUID: 9C90B790-2CC5-42C0-9F87-C9CC40648F4C",
            style = MaterialTheme.typography.bodySmall
        )
        
        Text(
            text = "Candidate PSMs: $psmInfo",
            style = MaterialTheme.typography.bodySmall
        )
        
        Divider()
        
        Text(
            text = "Status: $status",
            style = MaterialTheme.typography.bodyLarge
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onStartScan()
                },
                enabled = !isScanning
            ) {
                Text("Start Scan")
            }
            
            Button(
                onClick = {
                    onStopScan()
                },
                enabled = isScanning
            ) {
                Text("Stop Scan")
            }
        }
        
        Divider()
        
        Text(
            text = "Log:",
            style = MaterialTheme.typography.titleMedium
        )
        
        logLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
