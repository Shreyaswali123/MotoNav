package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBlueConnecting
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoRedError
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary

@Composable
fun ConnectionStatusBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (state) {
        ConnectionState.Disconnected -> Pair(MotoTextMuted, "Disconnected")
        ConnectionState.Connecting -> Pair(MotoBlueConnecting, "Connecting...")
        ConnectionState.Connected -> Pair(MotoLimeReady, "Connected")
        ConnectionState.Transferring -> Pair(MotoAmberPrimary, "Transferring...")
        ConnectionState.RouteReady -> Pair(MotoLimeReady, "Route Ready")
        ConnectionState.Error -> Pair(MotoRedError, "Error")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("connection_status_badge"),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = color
                )
            )
        }
    }
}
