package com.fipsdroid.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private const val TAG = "BlePermissions"

// API 31+ requires BLUETOOTH_CONNECT and BLUETOOTH_SCAN at runtime;
// API 29-30 only needs ACCESS_FINE_LOCATION.
private val BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
} else {
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}

fun areBlePermissionsGranted(context: Context): Boolean {
    return BLE_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

fun isBleAvailable(context: Context): Boolean {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bluetoothManager?.adapter ?: return false
    return adapter.isEnabled
}

sealed class BleState {
    data object AdapterUnavailable : BleState()
    data object AdapterDisabled : BleState()
    data object PermissionsRequired : BleState()
    data object Ready : BleState()
}

fun getBleState(context: Context): BleState {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bluetoothManager?.adapter

    return when {
        adapter == null -> BleState.AdapterUnavailable
        !adapter.isEnabled -> BleState.AdapterDisabled
        !areBlePermissionsGranted(context) -> BleState.PermissionsRequired
        else -> BleState.Ready
    }
}

@Composable
fun BlePermissionHandler(
    onGranted: () -> Unit,
    onDenied: () -> Unit
) {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "All BLE permissions granted")
            onGranted()
        } else {
            Log.w(TAG, "BLE permissions denied: ${permissions.filter { !it.value }.keys}")
            showRationale = true
        }
    }

    LaunchedEffect(Unit) {
        when (getBleState(context)) {
            is BleState.AdapterUnavailable -> {
                Log.e(TAG, "BLE adapter not available on this device")
                onDenied()
            }
            is BleState.AdapterDisabled -> {
                Log.w(TAG, "BLE adapter is disabled")
                onDenied()
            }
            is BleState.Ready -> {
                Log.i(TAG, "BLE already ready")
                onGranted()
            }
            is BleState.PermissionsRequired -> {
                if (!permissionRequested) {
                    permissionRequested = true
                    permissionLauncher.launch(BLE_PERMISSIONS)
                }
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                onDenied()
            },
            title = { Text("Bluetooth Permissions Required") },
            text = {
                Text(
                    "FipsDroid needs Bluetooth permissions to connect to FIPS devices " +
                    "over BLE L2CAP. Without these permissions, the app cannot function."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        permissionRequested = true
                        permissionLauncher.launch(BLE_PERMISSIONS)
                    }
                ) {
                    Text("Grant Permissions")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        onDenied()
                    }
                ) {
                    Text("Deny")
                }
            }
        )
    }
}
