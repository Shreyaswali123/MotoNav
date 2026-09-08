package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ble.BlePermissions
import com.example.model.ConnectionState
import com.example.model.UnitSystem
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.theme.MotoAccentGradient
import com.example.ui.theme.MotoAccentGlow
import com.example.ui.theme.MotoAmberLight
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBackground
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCardGradientDiagonal
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToRoutePlanning: () -> Unit,
    onNavigateToDevice: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val activeRoute by viewModel.activeRoute.collectAsStateWithLifecycle()
    val unitSystem by viewModel.unitSystem.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.toggleConnection()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MotoBackground)
            .padding(horizontal = 20.dp)
            .testTag("home_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header: MotoNav Branding & Quick Settings Access
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(MotoAmberPrimary, Color(0xFFFF6F00))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TwoWheeler,
                            contentDescription = "MotoNav Logo",
                            tint = Color(0xFF1A1100),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "MOTONAV",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = MotoTextPrimary
                            )
                        )
                        Text(
                            text = "ESP32 COCKPIT COMPANION",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MotoAmberPrimary,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }

                // Quick Settings Access
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MotoSurfaceVariant)
                        .border(1.dp, MotoCardBorder, CircleShape)
                        .testTag("home_quick_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MotoTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Connection Status Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MotoCardGradientDiagonal)
                    .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                    .clickable { onNavigateToDevice() }
                    .testTag("home_device_status_card")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.RouteReady) {
                                            MotoLimeReady.copy(alpha = 0.18f)
                                        } else {
                                            MotoSurfaceVariant
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.RouteReady) {
                                            MotoLimeReady.copy(alpha = 0.4f)
                                        } else {
                                            MotoCardBorder
                                        },
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.RouteReady) {
                                        Icons.Default.BluetoothConnected
                                    } else {
                                        Icons.Default.Bluetooth
                                    },
                                    contentDescription = "Bluetooth Status",
                                    tint = if (connectionState == ConnectionState.Connected || connectionState == ConnectionState.RouteReady) {
                                        MotoLimeReady
                                    } else {
                                        MotoTextSecondary
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = connectedDevice?.name ?: "MotoNav-01",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MotoTextPrimary
                                    )
                                )
                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.Connected -> {
                                            val parts = mutableListOf("ESP32-S3 Cockpit HUD")
                                            connectedDevice?.batteryPercentage?.let { parts.add("Battery $it%") }
                                            connectedDevice?.rssiDbm?.let { parts.add("$it dBm") }
                                            parts.joinToString(" • ")
                                        }
                                        ConnectionState.RouteReady -> "ESP32-S3 Cockpit HUD • Route Synced"
                                        ConnectionState.Connecting -> "Scanning for MotoNav-01..."
                                        ConnectionState.Transferring -> "Receiving Route Data..."
                                        ConnectionState.Error -> "BLE Connection Error"
                                        ConnectionState.Disconnected -> "BLE Cockpit Display"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MotoTextSecondary,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }

                        ConnectionStatusBadge(state = connectionState)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Connect / Disconnect button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error) {
                            Button(
                                onClick = {
                                    if (BlePermissions.hasPermissions(context)) {
                                        viewModel.toggleConnection()
                                    } else {
                                        permissionLauncher.launch(BlePermissions.getRequiredPermissions())
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .border(1.dp, MotoAmberLight.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .testTag("home_connect_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MotoAmberPrimary,
                                    contentColor = Color(0xFF0F1113)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connect MotoNav-01",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.toggleConnection() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_disconnect_button"),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
                            ) {
                                Text(
                                    text = "Disconnect",
                                    color = MotoTextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = onNavigateToDevice,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                                    .testTag("home_view_device_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MotoSurfaceVariant,
                                    contentColor = MotoCyanSecondary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Device HUD",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Large "Plan Ride" Primary Action (Motorcycle glove friendly with rounded-[32px] & glow)
        item {
            Button(
                onClick = onNavigateToRoutePlanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .border(1.5.dp, MotoAmberLight.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
                    .testTag("home_plan_ride_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MotoAmberPrimary,
                    contentColor = Color(0xFF0F1113)
                ),
                shape = RoundedCornerShape(32.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F1113)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = "Navigation",
                                tint = MotoAmberPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "PLAN RIDE",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 19.sp,
                                    letterSpacing = 0.5.sp,
                                    color = Color(0xFF0F1113)
                                )
                            )
                            Text(
                                text = "Search routes & sync to cockpit",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF351700)
                                )
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go",
                        tint = Color(0xFF0F1113),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Active / Selected Route Preview Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MotoCardGradientDiagonal)
                    .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                    .clickable { onNavigateToRoutePlanning() }
                    .testTag("home_active_route_card")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STAGED ROUTE",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MotoAmberPrimary,
                                letterSpacing = 1.sp
                            )
                        )

                        if (activeRoute.hasTwistySegments) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MotoCyanSecondary.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCyanSecondary.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "TWISTY PASS",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MotoCyanSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = activeRoute.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MotoTextPrimary,
                            fontSize = 18.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = activeRoute.summary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MotoTextSecondary,
                            fontSize = 13.sp
                        ),
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Stats Row: Distance & Estimated Time
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MotoSurfaceVariant)
                            .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val distanceText = if (unitSystem == UnitSystem.KILOMETERS) {
                            String.format("%.1f km", activeRoute.totalDistanceKm)
                        } else {
                            String.format("%.1f mi", activeRoute.totalDistanceMiles)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "DISTANCE",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MotoTextMuted
                                )
                            )
                            Text(
                                text = distanceText,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MotoAmberPrimary,
                                    fontSize = 18.sp
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(MotoCardBorder)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "EST. RIDE TIME",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MotoTextMuted
                                )
                            )
                            Text(
                                text = "${activeRoute.estimatedDurationMinutes} min",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MotoCyanSecondary,
                                    fontSize = 18.sp
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(MotoCardBorder)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "MANEUVERS",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MotoTextMuted
                                )
                            )
                            Text(
                                text = "${activeRoute.maneuvers.size} turns",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MotoTextPrimary,
                                    fontSize = 18.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // Quick Cockpit Tips / Safety Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MotoCardGradientDiagonal)
                    .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MotoAmberPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Sync routes while stationary before riding. ESP32 HUD provides distraction-free arrow guidance.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = MotoTextSecondary
                    )
                )
            }
        }
    }
}
