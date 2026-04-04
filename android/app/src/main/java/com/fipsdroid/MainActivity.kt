package com.fipsdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.fipsdroid.ble.BlePermissionHandler
import com.fipsdroid.ui.ConnectionState
import com.fipsdroid.ui.DebugScreen
import com.fipsdroid.ui.HeartbeatStatus
import com.fipsdroid.ui.theme.FipsDroidTheme

private const val PEER_ADDRESS = "00:00:00:00:00:00"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val peerAddress = intent.getStringExtra("peer_address") ?: PEER_ADDRESS
        
        setContent {
            FipsDroidTheme {
                var permissionsGranted by remember { mutableStateOf(false) }
                var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
                var heartbeatStatus by remember { mutableStateOf(HeartbeatStatus()) }
                var logLines by remember { mutableStateOf(listOf<String>()) }
                
                BlePermissionHandler(
                    onGranted = { permissionsGranted = true },
                    onDenied = { }
                )
                
                if (permissionsGranted) {
                    DebugScreen(
                        peerAddress = peerAddress,
                        connectionState = connectionState,
                        heartbeatStatus = heartbeatStatus,
                        logLines = logLines,
                        onConnect = { 
                            // TODO: Wire to Rust bridge via UniFFI
                            connectionState = ConnectionState.Connecting
                            logLines = logLines + "[INFO] Connect button pressed"
                        },
                        onDisconnect = {
                            // TODO: Wire to Rust bridge via UniFFI
                            connectionState = ConnectionState.Disconnected
                            logLines = logLines + "[INFO] Disconnect button pressed"
                        }
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
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    FipsDroidTheme {
        Greeting("Android")
    }
}

@Preview(showBackground = true)
@Composable
fun DebugScreenPreview() {
    FipsDroidTheme {
        DebugScreen(
            peerAddress = "AA:BB:CC:DD:EE:FF",
            connectionState = ConnectionState.Disconnected,
            heartbeatStatus = HeartbeatStatus(
                sentCount = 5u,
                receivedCount = 3u,
                lastReceived = null
            ),
            logLines = listOf(
                "[INFO] App started",
                "[DEBUG] BLE adapter ready",
                "[INFO] Waiting for connection"
            ),
            onConnect = {},
            onDisconnect = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DebugScreenConnectedPreview() {
    FipsDroidTheme {
        DebugScreen(
            peerAddress = "AA:BB:CC:DD:EE:FF",
            connectionState = ConnectionState.Established,
            heartbeatStatus = HeartbeatStatus(
                sentCount = 100u,
                receivedCount = 98u,
                lastReceived = (System.currentTimeMillis() / 1000).toULong() - 5u
            ),
            logLines = listOf(
                "[INFO] Connected to peer",
                "[DEBUG] Handshake complete",
                "[INFO] Heartbeat #100 sent"
            ),
            onConnect = {},
            onDisconnect = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DebugScreenErrorPreview() {
    FipsDroidTheme {
        DebugScreen(
            peerAddress = "AA:BB:CC:DD:EE:FF",
            connectionState = ConnectionState.Error("Connection timeout after 30s"),
            heartbeatStatus = HeartbeatStatus(),
            logLines = listOf(
                "[ERROR] Connection failed",
                "[ERROR] Timeout waiting for handshake"
            ),
            onConnect = {},
            onDisconnect = {}
        )
    }
}
