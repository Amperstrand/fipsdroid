package com.fipsdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.Duration

/**
 * Formats the last received heartbeat timestamp.
 * @param lastReceived Epoch seconds, or null if never received
 * @return Formatted string like "5s ago" or "Never"
 */
fun formatLastReceived(lastReceived: ULong?): String {
    if (lastReceived == null) {
        return "Never"
    }
    val instant = Instant.ofEpochSecond(lastReceived.toLong())
    val now = Instant.now()
    val duration = Duration.between(instant, now)
    val seconds = duration.seconds
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

fun formatHeartbeatStatus(status: HeartbeatStatus): String {
    val lastReceivedStr = formatLastReceived(status.lastReceived)
    return "Sent: ${status.sentCount} / Received: ${status.receivedCount} / Last: $lastReceivedStr"
}

fun shouldShowConnect(state: ConnectionState): Boolean {
    return state is ConnectionState.Disconnected || state is ConnectionState.Error
}

fun shouldShowDisconnect(state: ConnectionState): Boolean {
    return state is ConnectionState.Connected || 
           state is ConnectionState.Established ||
           state is ConnectionState.Connecting ||
           state is ConnectionState.Handshaking
}

/**
 * The main debug screen composable for FipsDroid.
 * Displays connection state, heartbeat counter, and control buttons.
 *
 * @param peerAddress The BLE peer address (hardcoded for now)
 * @param connectionState Current connection state
 * @param heartbeatStatus Current heartbeat status
 * @param logLines List of log lines to display (optional)
 * @param onConnect Callback when Connect button is clicked
 * @param onDisconnect Callback when Disconnect button is clicked
 * @param modifier Optional modifier
 */
@Composable
fun DebugScreen(
    peerAddress: String,
    connectionState: ConnectionState,
    heartbeatStatus: HeartbeatStatus,
    logLines: List<String> = emptyList(),
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Peer Address",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = peerAddress,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Connection State",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ConnectionStateIndicator(state = connectionState)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Heartbeat Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatHeartbeatStatus(heartbeatStatus),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (connectionState is ConnectionState.Error) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = connectionState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (shouldShowConnect(connectionState)) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Connect")
                    }
                }

                if (shouldShowDisconnect(connectionState)) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }
            }

            if (logLines.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Log (last ${logLines.size} lines)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            state = rememberLazyListState()
                        ) {
                            items(logLines) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * State holder for the debug screen.
 * Can be replaced by ViewModel from Task 11 when ready.
 */
class DebugScreenState {
    private val _connectionState = mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: State<ConnectionState> = _connectionState

    private val _heartbeatStatus = mutableStateOf(HeartbeatStatus())
    val heartbeatStatus: State<HeartbeatStatus> = _heartbeatStatus

    private val _logLines = mutableStateListOf<String>()
    val logLines: List<String> = _logLines

    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updateHeartbeatStatus(status: HeartbeatStatus) {
        _heartbeatStatus.value = status
    }

    fun addLogLine(line: String) {
        _logLines.add(line)
        // Keep only last 50 lines
        while (_logLines.size > 50) {
            _logLines.removeAt(0)
        }
    }

    fun connect() {
        // Placeholder - will be wired to ViewModel in Task 11
        _connectionState.value = ConnectionState.Connecting
        addLogLine("Connect requested")
    }

    fun disconnect() {
        // Placeholder - will be wired to ViewModel in Task 11
        _connectionState.value = ConnectionState.Disconnecting
        addLogLine("Disconnect requested")
    }
}

@Composable
fun rememberDebugScreenState(): DebugScreenState {
    return remember { DebugScreenState() }
}
