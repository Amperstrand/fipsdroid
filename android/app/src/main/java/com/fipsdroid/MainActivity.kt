package com.fipsdroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fipsdroid.ble.BlePermissionHandler
import com.fipsdroid.ble.getBleState
import com.fipsdroid.ble.isBleAvailable
import com.fipsdroid.ui.ConnectionState
import com.fipsdroid.ui.DebugScreen
import com.fipsdroid.ui.HeartbeatStatus
import com.fipsdroid.ui.theme.FipsDroidTheme

private const val TAG = "MainActivity"
private const val DEFAULT_PEER_ADDRESS = "00:00:00:00:00:00"

class MainActivity : ComponentActivity() {

    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Log.i(TAG, "Returned from Bluetooth enable prompt")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val peerAddress = intent.getStringExtra("peer_address") ?: DEFAULT_PEER_ADDRESS
        val autoConnect = intent.getBooleanExtra("auto_connect", false)

        setContent {
            FipsDroidTheme {
                var permissionsGranted by remember { mutableStateOf(false) }
                var showBtPrompt by remember { mutableStateOf(false) }
                val context = LocalContext.current

                BlePermissionHandler(
                    onGranted = {
                        permissionsGranted = true
                        if (!isBleAvailable(context)) {
                            showBtPrompt = true
                        }
                    },
                    onDenied = { }
                )

                if (permissionsGranted) {
                    if (showBtPrompt) {
                        BluetoothPromptDialog(
                            onEnable = {
                                showBtPrompt = false
                                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            },
                            onDismiss = {
                                showBtPrompt = false
                            }
                        )
                    }

                    val viewModel: BridgeViewModel = viewModel(
                        factory = BridgeViewModelFactory(context, peerAddress)
                    )

                    if (autoConnect) {
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(2000L)
                            viewModel.connect()
                        }
                    }

                    val connectionState by viewModel.connectionState.collectAsState()
                    val heartbeatStatus by viewModel.heartbeatStatus.collectAsState()
                    val logLines by viewModel.logLines.collectAsState()
                    val peerAddr by viewModel.peerAddress.collectAsState()

                    DebugScreen(
                        peerAddress = peerAddr,
                        connectionState = connectionState,
                        heartbeatStatus = heartbeatStatus,
                        logLines = logLines,
                        onConnect = { viewModel.connect() },
                        onDisconnect = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothPromptDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth Required") },
        text = {
            Text(
                "FipsDroid requires Bluetooth to be enabled to connect to the FIPS network " +
                "over BLE L2CAP. Please enable Bluetooth to continue."
            )
        },
        confirmButton = {
            Button(onClick = onEnable) {
                Text("Enable Bluetooth")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

class BridgeViewModelFactory(
    private val context: Context,
    private val peerAddress: String
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return BridgeViewModel(context).also { it.setPeerAddress(peerAddress) } as T
    }
}
