package com.example.ui.screens.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.theme.MotoAmberLight
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBackground
import com.example.ui.theme.MotoBlueConnecting
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCardGradientDiagonal
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoRedError
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by viewModel.connectedDevice.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.toggleConnect()
        }
    }

    Scaffold(
        modifier = modifier.testTag("device_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "MOTO COCKPIT DEVICE",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                color = MotoTextPrimary
                            )
                        )
                        Text(
                            text = "Bluetooth Low Energy • ESP32-S3",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("device_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MotoTextPrimary
                        )
                    }
                },
                actions = {
                    ConnectionStatusBadge(
                        state = connectionState,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MotoBackground,
                    titleContentColor = MotoTextPrimary
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MotoBackground)
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
        ) {
            // Virtual Round HUD Visualizer for ESP32-S3 Handlebar Unit
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Bezel
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF22262B), Color(0xFF0F1113))
                                )
                            )
                            .border(3.dp, MotoCardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Inner HUD Screen
                        Box(
                            modifier = Modifier
                                .size(168.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F1113))
                                .border(
                                    width = 2.dp,
                                    color = when (connectionState) {
                                        ConnectionState.Connected -> MotoLimeReady.copy(alpha = 0.6f)
                                        ConnectionState.RouteReady -> MotoLimeReady
                                        ConnectionState.Transferring -> MotoAmberPrimary
                                        ConnectionState.Connecting -> MotoBlueConnecting
                                        ConnectionState.Error -> MotoRedError
                                        ConnectionState.Disconnected -> MotoCardBorder
                                    },
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // HUD Arrow / Status Graphic
                                Icon(
                                    imageVector = when (connectionState) {
                                        ConnectionState.RouteReady -> Icons.Default.Navigation
                                        ConnectionState.Transferring -> Icons.Default.Sync
                                        ConnectionState.Connected -> Icons.Default.BluetoothConnected
                                        ConnectionState.Connecting -> Icons.Default.CompassCalibration
                                        ConnectionState.Error -> Icons.Default.Warning
                                        ConnectionState.Disconnected -> Icons.Default.Bluetooth
                                    },
                                    contentDescription = null,
                                    tint = when (connectionState) {
                                        ConnectionState.RouteReady -> MotoLimeReady
                                        ConnectionState.Transferring -> MotoAmberPrimary
                                        ConnectionState.Connected -> MotoCyanSecondary
                                        ConnectionState.Connecting -> MotoBlueConnecting
                                        ConnectionState.Error -> MotoRedError
                                        ConnectionState.Disconnected -> MotoTextMuted
                                    },
                                    modifier = Modifier.size(36.dp)
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "MotoNav-01",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp,
                                        color = MotoTextPrimary
                                    )
                                )

                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.RouteReady -> "READY TO RIDE"
                                        ConnectionState.Transferring -> "${transferProgress.percentage}%"
                                        ConnectionState.Connected -> "STANDBY"
                                        ConnectionState.Connecting -> "PAIRING..."
                                        ConnectionState.Error -> "BLE FAULT"
                                        ConnectionState.Disconnected -> "OFFLINE"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = when (connectionState) {
                                            ConnectionState.RouteReady -> MotoLimeReady
                                            ConnectionState.Transferring -> MotoAmberPrimary
                                            ConnectionState.Connected -> MotoCyanSecondary
                                            ConnectionState.Connecting -> MotoBlueConnecting
                                            ConnectionState.Error -> MotoRedError
                                            ConnectionState.Disconnected -> MotoTextMuted
                                        },
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Error Banner (if in Error state)
            if (connectionState == ConnectionState.Error || lastError != null) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MotoRedError, RoundedCornerShape(12.dp))
                            .testTag("device_error_banner"),
                        color = MotoRedError.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error",
                                tint = MotoRedError,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bluetooth Error",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = MotoRedError,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = lastError ?: "GATT transmission failed or device disconnected.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MotoTextPrimary,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                            IconButton(onClick = { viewModel.resetError() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset Error",
                                    tint = MotoRedError
                                )
                            }
                        }
                    }
                }
            }

            // Transfer Progress Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("device_transfer_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ROUTE TRANSFER STATUS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MotoAmberPrimary,
                                    letterSpacing = 1.sp
                                )
                            )

                            Text(
                                text = "${transferProgress.percentage}%",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = if (connectionState == ConnectionState.RouteReady) MotoLimeReady else MotoAmberPrimary,
                                    fontSize = 18.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { transferProgress.percentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (connectionState == ConnectionState.RouteReady) MotoLimeReady else MotoAmberPrimary,
                            trackColor = MotoSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = transferProgress.stepDescription.ifBlank { "Ready to sync route packets" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MotoTextSecondary,
                                    fontSize = 12.sp
                                )
                            )
                            if (transferProgress.totalPackets > 0) {
                                Text(
                                    text = "Packet ${transferProgress.currentPacket}/${transferProgress.totalPackets}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MotoCyanSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Primary Connect / Disconnect Action Button
            item {
                val isConnected = connectionState == ConnectionState.Connected || connectionState == ConnectionState.RouteReady
                val isBusy = connectionState == ConnectionState.Connecting || connectionState == ConnectionState.Transferring

                Button(
                    onClick = {
                        if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Error) {
                            if (BlePermissions.hasPermissions(context)) {
                                viewModel.toggleConnect()
                            } else {
                                permissionLauncher.launch(BlePermissions.getRequiredPermissions())
                            }
                        } else {
                            viewModel.toggleConnect()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .then(
                            if (!isConnected) Modifier.border(1.5.dp, MotoAmberLight.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                            else Modifier.border(1.dp, MotoCardBorder, RoundedCornerShape(32.dp))
                        )
                        .testTag("device_primary_connect_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) MotoSurfaceVariant else MotoAmberPrimary,
                        contentColor = if (isConnected) MotoRedError else Color(0xFF0F1113)
                    ),
                    shape = RoundedCornerShape(32.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 5.dp),
                    enabled = !isBusy
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when (connectionState) {
                            ConnectionState.Connected, ConnectionState.RouteReady -> "DISCONNECT MOTONAV-01"
                            ConnectionState.Connecting -> "CONNECTING OVER BLE..."
                            ConnectionState.Transferring -> "TRANSFER IN PROGRESS..."
                            else -> "CONNECT TO MOTONAV-01"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }

            // Device Technical Specifications Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("device_specs_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "HARDWARE & TELEMETRY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MotoTextMuted,
                                letterSpacing = 1.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Battery & RSSI
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, MotoCardBorder, RoundedCornerShape(10.dp)),
                                color = MotoSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BatteryChargingFull,
                                        contentDescription = null,
                                        tint = if (connectedDevice?.batteryPercentage != null) MotoLimeReady else MotoTextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "BATTERY",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                color = MotoTextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = connectedDevice?.batteryPercentage?.let { "$it%" } ?: "--",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MotoTextPrimary
                                            )
                                        )
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, MotoCardBorder, RoundedCornerShape(10.dp)),
                                color = MotoSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SignalCellularAlt,
                                        contentDescription = null,
                                        tint = if (connectedDevice?.rssiDbm != null) MotoCyanSecondary else MotoTextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "BLE RSSI",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                color = MotoTextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = connectedDevice?.rssiDbm?.let { "$it dBm" } ?: "--",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MotoTextPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Device Details Rows
                        DetailInfoRow(
                            label = "Device ID",
                            value = connectedDevice?.let { "${it.name} (${it.address ?: "Unavailable"})" } ?: "MotoNav-01 (--)"
                        )
                        DetailInfoRow(label = "Microcontroller", value = "ESP32-S3 Dual-Core Xtensa LX7")
                        DetailInfoRow(label = "Display Screen", value = "2.1\" Round IPS 480x480 Cockpit HUD")
                        DetailInfoRow(label = "Custom Service", value = "f0debc9a-7856-3412-5678-123412345678")
                        DetailInfoRow(label = "Status Char", value = "f2debc9a-7856-3412-5678-123412345678")
                        DetailInfoRow(
                            label = "Status Reading",
                            value = connectedDevice?.rawStatus ?: if (connectionState == ConnectionState.Connected) "IDLE,0/0" else "--"
                        )
                        DetailInfoRow(
                            label = "Firmware Version",
                            value = connectedDevice?.firmwareVersion ?: "Unavailable"
                        )
                    }
                }
            }

            // BLE Connection Verification & Diagnostics (Real Hardware Telemetry)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("device_verification_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CompassCalibration,
                                    contentDescription = null,
                                    tint = MotoCyanSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "BLE CONNECTION VERIFICATION",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MotoCyanSecondary,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }

                            Text(
                                text = if (diagnostics.isConnected) "VERIFIED" else "DIAGNOSTIC",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (diagnostics.isConnected) MotoLimeReady else MotoAmberPrimary,
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Real-time GATT handshake & status verification for MotoNav-01 (ESP32-S3):",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 12.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 1. BLE scanning
                        DiagnosticItemRow(
                            label = "BLE scanning",
                            value = if (diagnostics.isScanning) "Active (Filtering for MotoNav-01)" else "Inactive",
                            isSuccess = diagnostics.isScanning || diagnostics.deviceDiscovered,
                            isPending = !diagnostics.isScanning && !diagnostics.deviceDiscovered
                        )

                        // 2. Device discovered
                        DiagnosticItemRow(
                            label = "Device discovered",
                            value = if (diagnostics.deviceDiscovered) {
                                "Discovered (${diagnostics.discoveredDeviceName ?: "MotoNav-01"})"
                            } else {
                                "Not discovered"
                            },
                            subtitle = if (diagnostics.deviceDiscovered) {
                                "MAC: ${diagnostics.discoveredDeviceAddress ?: "N/A"} • RSSI: ${diagnostics.discoveredRssi?.let { "$it dBm" } ?: "N/A"}"
                            } else null,
                            isSuccess = diagnostics.deviceDiscovered
                        )

                        // 3. Connecting
                        DiagnosticItemRow(
                            label = "Connecting",
                            value = if (diagnostics.isConnecting) "In progress" else if (diagnostics.isConnected) "Completed" else "Idle",
                            isSuccess = diagnostics.isConnected,
                            isPending = diagnostics.isConnecting
                        )

                        // 4. Connected
                        DiagnosticItemRow(
                            label = "Connected",
                            value = if (diagnostics.isConnected) "Connected (GATT)" else "Disconnected",
                            isSuccess = diagnostics.isConnected
                        )

                        // 5. GATT services discovered
                        DiagnosticItemRow(
                            label = "GATT services discovered",
                            value = if (diagnostics.servicesDiscovered && diagnostics.serviceUuidFound) {
                                "Discovered (f0debc9a found)"
                            } else if (diagnostics.servicesDiscovered) {
                                "Discovered (f0debc9a missing)"
                            } else {
                                "Not discovered"
                            },
                            isSuccess = diagnostics.servicesDiscovered && diagnostics.serviceUuidFound,
                            isWarning = diagnostics.servicesDiscovered && !diagnostics.serviceUuidFound
                        )

                        // 6. Control characteristic found
                        DiagnosticItemRow(
                            label = "Control characteristic found",
                            value = if (diagnostics.controlCharacteristicFound) "Found (f1debc9a)" else "Not found",
                            isSuccess = diagnostics.controlCharacteristicFound
                        )

                        // 7. Status characteristic found
                        DiagnosticItemRow(
                            label = "Status characteristic found",
                            value = if (diagnostics.statusCharacteristicFound) "Found (f2debc9a)" else "Not found",
                            isSuccess = diagnostics.statusCharacteristicFound
                        )

                        // 8. Route Data characteristic found
                        DiagnosticItemRow(
                            label = "Route Data characteristic found",
                            value = if (diagnostics.routeDataCharacteristicFound) "Found (f3debc9a)" else "Not found",
                            isSuccess = diagnostics.routeDataCharacteristicFound
                        )

                        // 9. Status notifications enabled
                        DiagnosticItemRow(
                            label = "Status notifications enabled",
                            value = if (diagnostics.statusNotificationsEnabled) "Enabled (CCCD written)" else "Not enabled",
                            isSuccess = diagnostics.statusNotificationsEnabled
                        )

                        // 10. Initial Status read
                        DiagnosticItemRow(
                            label = "Initial Status read",
                            value = if (diagnostics.initialStatusRead) "Completed" else "Not read",
                            isSuccess = diagnostics.initialStatusRead
                        )

                        // 11. Last Status message
                        DiagnosticItemRow(
                            label = "Last Status message",
                            value = diagnostics.lastStatusMessage?.let { "\"$it\"" } ?: "None",
                            subtitle = diagnostics.lastStatusReadTimestamp?.let {
                                "Updated: ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(it))}"
                            },
                            isSuccess = diagnostics.lastStatusMessage != null
                        )

                        // 12. Disconnect/reconnect state
                        DiagnosticItemRow(
                            label = "Disconnect/reconnect state",
                            value = diagnostics.disconnectReconnectState,
                            isSuccess = diagnostics.isConnected,
                            isWarning = diagnostics.disconnectReconnectState.contains("drop", ignoreCase = true) ||
                                       diagnostics.disconnectReconnectState.contains("fail", ignoreCase = true)
                        )

                        // 13. Last BLE/GATT error
                        DiagnosticItemRow(
                            label = "Last BLE/GATT error",
                            value = diagnostics.lastError ?: "None",
                            isSuccess = diagnostics.lastError == null,
                            isWarning = diagnostics.lastError != null
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Developer Action: Read Status Characteristic
                        Button(
                            onClick = { viewModel.readStatus() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("read_status_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MotoCyanSecondary.copy(alpha = 0.18f),
                                contentColor = MotoCyanSecondary
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MotoCyanSecondary.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Read Status",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "READ STATUS (GATT f2debc9a)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Real MotoNav Route Transfer Integration Test
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, if (connectionState == ConnectionState.RouteReady) MotoLimeReady.copy(alpha = 0.5f) else MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("route_transfer_integration_test_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = MotoLimeReady,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ROUTE TRANSFER INTEGRATION TEST",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MotoLimeReady,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }

                            Text(
                                text = when (connectionState) {
                                    ConnectionState.RouteReady -> "ROUTE READY"
                                    ConnectionState.Transferring -> "TRANSFERRING"
                                    ConnectionState.Error -> "ERROR"
                                    else -> "IDLE"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = when (connectionState) {
                                        ConnectionState.RouteReady -> MotoLimeReady
                                        ConnectionState.Transferring -> MotoAmberPrimary
                                        ConnectionState.Error -> Color(0xFFFF5252)
                                        else -> MotoCyanSecondary
                                    },
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Test transfer of exact 62-byte binary route with CRC32 (0xDC0213B4) to ESP32 GATT characteristics without Google Maps:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 12.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Test Fixture Parameter Badges
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MotoSurface.copy(alpha = 0.6f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DetailInfoRow(label = "Binary Route Size", value = "62 bytes (verified)")
                            DetailInfoRow(label = "Expected CRC32", value = "0xDC0213B4 (verified)")
                            DetailInfoRow(label = "Frame Fragmentation", value = "4 packets (20B, 20B, 20B, 2B)")
                            DetailInfoRow(label = "Handshake Protocol", value = "START(0x01) -> ROUTE_DATA(0x03) + ACK -> END(0x02)")
                            DetailInfoRow(
                                label = "Required Final Status",
                                value = "ROUTE_READY,62/62"
                            )
                        }

                        // Progress & Step Info
                        if (connectionState == ConnectionState.Transferring || transferProgress.totalPackets > 0) {
                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = transferProgress.stepDescription,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MotoTextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${transferProgress.currentPacket}/${transferProgress.totalPackets} (${transferProgress.percentage}%)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MotoLimeReady,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            LinearProgressIndicator(
                                progress = { transferProgress.percentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("route_transfer_progress_bar"),
                                color = if (connectionState == ConnectionState.Error) MotoRedError else MotoLimeReady,
                                trackColor = MotoSurface
                            )
                        }

                        // Final Result Banners
                        val errorText = lastError
                        if (connectionState == ConnectionState.RouteReady) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MotoLimeReady.copy(alpha = 0.15f))
                                    .border(1.dp, MotoLimeReady.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                                    .testTag("route_transfer_success_banner")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MotoLimeReady,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "INTEGRATION TEST PASSED",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MotoLimeReady,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Text(
                                            text = "All 4 packets acknowledged. CRC 0xDC0213B4 confirmed by MotoNav-01 (ROUTE_READY,62/62).",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MotoTextPrimary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (connectionState == ConnectionState.Error && errorText != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MotoRedError.copy(alpha = 0.15f))
                                    .border(1.dp, MotoRedError.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                                    .testTag("route_transfer_error_banner")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MotoRedError,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "TRANSFER TEST ERROR",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MotoRedError,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Text(
                                            text = errorText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MotoTextPrimary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // SEND TEST ROUTE Action Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.sendTestRoute() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("send_test_route_button"),
                                enabled = connectionState != ConnectionState.Transferring,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MotoLimeReady,
                                    contentColor = MotoBackground,
                                    disabledContainerColor = MotoLimeReady.copy(alpha = 0.3f),
                                    disabledContentColor = MotoBackground.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = "Send Test Route",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (connectionState == ConnectionState.Transferring) "TRANSFERRING..." else "SEND TEST ROUTE",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (connectionState == ConnectionState.Transferring) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelTransfer() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("cancel_transfer_button"),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MotoRedError
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MotoRedError.copy(alpha = 0.7f))
                                ) {
                                    Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Interactive BLE State Simulator (For Testing All States)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("device_state_simulation_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MotoAmberPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BLE STATE TEST BENCH",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MotoAmberPrimary,
                                    letterSpacing = 1.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Test each connection lifecycle state without physical ESP32 hardware:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 12.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { viewModel.simulateError() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("simulate_error_button"),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MotoRedError)
                        ) {
                            Text(
                                text = "Trigger Error",
                                color = MotoRedError,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MotoTextSecondary,
                fontSize = 12.sp
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MotoTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun DiagnosticItemRow(
    label: String,
    value: String,
    isSuccess: Boolean = false,
    isWarning: Boolean = false,
    isPending: Boolean = false,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MotoTextSecondary,
                    fontSize = 12.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isWarning -> MotoRedError
                                isSuccess -> MotoLimeReady
                                isPending -> MotoAmberPrimary
                                else -> MotoTextMuted.copy(alpha = 0.5f)
                            }
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = when {
                            isWarning -> MotoRedError
                            isSuccess -> MotoLimeReady
                            isPending -> MotoAmberPrimary
                            else -> MotoTextPrimary
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                )
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MotoTextMuted,
                    fontSize = 10.sp
                ),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
