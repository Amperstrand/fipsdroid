package com.fipsdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Connection state enum matching the Rust ConnectionState from fipsdroid-core.
 * This will be replaced by UniFFI-generated bindings when Task 11 completes.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data object Handshaking : ConnectionState()
    data object Established : ConnectionState()
    data object Disconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Data class matching the Rust HeartbeatStatus from fipsdroid-core.
 * @param sentCount Number of heartbeats sent
 * @param receivedCount Number of heartbeats received
 * @param lastReceived Epoch seconds of last received heartbeat, null if never received
 */
data class HeartbeatStatus(
    val sentCount: ULong = 0u,
    val receivedCount: ULong = 0u,
    val lastReceived: ULong? = null
)

/**
 * Returns the display color for a connection state.
 * - Red: Disconnected, Error
 * - Yellow: Connecting, Connected, Handshaking
 * - Orange: Disconnecting
 * - Green: Established
 */
@Composable
fun getConnectionStateColor(state: ConnectionState): Color {
    return when (state) {
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
        is ConnectionState.Error -> MaterialTheme.colorScheme.error
        is ConnectionState.Connecting -> Color(0xFFFFC107) // Amber/Yellow
        is ConnectionState.Connected -> Color(0xFFFFC107) // Amber/Yellow
        is ConnectionState.Handshaking -> Color(0xFFFFC107) // Amber/Yellow
        is ConnectionState.Disconnecting -> Color(0xFFFF9800) // Orange
        is ConnectionState.Established -> Color(0xFF4CAF50) // Green
    }
}

fun getConnectionStateLabel(state: ConnectionState): String {
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

/**
 * A composable that displays a colored circle indicator with a text label
 * representing the current connection state.
 *
 * @param state The current connection state
 * @param modifier Optional modifier
 */
@Composable
fun ConnectionStateIndicator(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val color = getConnectionStateColor(state)
    val label = getConnectionStateLabel(state)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Colored circle indicator
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2,
                center = Offset(size.width / 2, size.height / 2)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // State label
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                is ConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
