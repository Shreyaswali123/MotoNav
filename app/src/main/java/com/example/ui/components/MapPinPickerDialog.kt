package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.RoutePoint
import com.example.ui.screens.route.LocationPickerTarget
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import java.util.Locale

/**
 * Interactive full-screen / modal map picker for dropping pins.
 *
 * Workflow:
 * 1. Shows interactive vector map centered at initial coordinates or default Hubballi.
 * 2. Rider taps or long-presses anywhere to drop / move a pin.
 * 3. Animated pin marker appears at the exact tapped geographic coordinate.
 * 4. Asynchronously reverse geocodes the coordinate into a clean address string with fallback.
 * 5. Rider taps "Confirm Location" to commit coordinates and display name.
 */
@Composable
fun MapPinPickerDialog(
    target: LocationPickerTarget,
    initialPoint: RoutePoint? = null,
    onReverseGeocode: suspend (Double, Double) -> String?,
    onConfirmLocation: (Double, Double, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val targetColor = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary

    val initialPosition = remember(initialPoint) {
        if (initialPoint != null && RouteMapAdapter.isValid(initialPoint)) {
            Position(longitude = initialPoint.longitude, latitude = initialPoint.latitude)
        } else null
    }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = initialPosition ?: Position(
                longitude = MapConfig.DEFAULT_LONGITUDE,
                latitude = MapConfig.DEFAULT_LATITUDE
            ),
            zoom = if (initialPosition != null) 14.0 else MapConfig.DEFAULT_ZOOM
        )
    )

    var selectedPin by remember { mutableStateOf<Position?>(initialPosition) }
    var resolvedAddress by remember { mutableStateOf<String?>(initialPoint?.name) }
    var isGeocoding by remember { mutableStateOf(false) }

    // Reverse geocode when pin moves
    LaunchedEffect(selectedPin) {
        val pin = selectedPin ?: return@LaunchedEffect
        isGeocoding = true
        try {
            val address = onReverseGeocode(pin.latitude, pin.longitude)
            resolvedAddress = address
        } catch (_: Throwable) {
            resolvedAddress = null
        } finally {
            isGeocoding = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MotoCardBorder, RoundedCornerShape(24.dp))
                .testTag("map_pin_picker_dialog"),
            color = MotoSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "DROP PIN ON MAP",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    color = MotoTextPrimary
                                ),
                                modifier = Modifier.testTag("map_pin_picker_title")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = targetColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, targetColor.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = if (target == LocationPickerTarget.START) "START LOCATION" else "DESTINATION",
                                    color = targetColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("pin_picker_target_badge")
                                )
                            }
                        }
                        Text(
                            text = "Tap or long-press anywhere on the map to drop a pin",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("close_pin_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Pin Picker",
                            tint = MotoTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Map Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F1113))
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("interactive_pin_map_container")
                ) {
                    MaplibreMap(
                        modifier = Modifier.fillMaxSize(),
                        baseStyle = BaseStyle.Uri(MapConfig.DEFAULT_STYLE_URI),
                        cameraState = cameraState,
                        onMapClick = { pos, _ ->
                            selectedPin = pos
                            ClickResult.Consume
                        },
                        onMapLongClick = { pos, _ ->
                            selectedPin = pos
                            ClickResult.Consume
                        },
                        overlay = MapOverlay {
                            include(MapOverlay.Default)

                            // Dropped Pin Marker
                            if (selectedPin != null) {
                                DroppedPinMarker(
                                    modifier = Modifier.placedAt(selectedPin!!, Alignment.BottomCenter),
                                    color = targetColor,
                                    label = if (target == LocationPickerTarget.START) "START" else "DEST"
                                )
                            }
                        }
                    )

                    // Top Instruction Pill Overlay
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .testTag("pin_picker_instruction_pill"),
                        color = Color(0xDD16191C),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MotoCardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = targetColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedPin == null) "Tap or long-press map to place pin" else "Tap anywhere to adjust pin",
                                color = MotoTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Floating Controls on the Map (Recenter & Zoom)
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Recenter button
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .testTag("pin_picker_recenter_button"),
                            color = Color(0xEE16191C),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MotoCardBorder)
                        ) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val targetPos = selectedPin ?: initialPosition ?: Position(
                                                longitude = MapConfig.DEFAULT_LONGITUDE,
                                                latitude = MapConfig.DEFAULT_LATITUDE
                                            )
                                            cameraState.animateTo(
                                                CameraPosition(
                                                    target = targetPos,
                                                    zoom = 14.0
                                                )
                                            )
                                        } catch (_: Throwable) {}
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = "Recenter Map",
                                    tint = targetColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Zoom In button
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .testTag("pin_picker_zoom_in_button"),
                            color = Color(0xEE16191C),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MotoCardBorder)
                        ) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            cameraState.animateTo(
                                                cameraState.position.copy(zoom = cameraState.position.zoom + 1.0)
                                            )
                                        } catch (_: Throwable) {}
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Zoom In",
                                    tint = MotoTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Zoom Out button
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .testTag("pin_picker_zoom_out_button"),
                            color = Color(0xEE16191C),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MotoCardBorder)
                        ) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            cameraState.animateTo(
                                                cameraState.position.copy(zoom = cameraState.position.zoom - 1.0)
                                            )
                                        } catch (_: Throwable) {}
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Zoom Out",
                                    tint = MotoTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Confirmation Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("pin_confirmation_card"),
                    color = MotoSurfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        val pin = selectedPin
                        if (pin != null) {
                            // Coordinate Readout Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SELECTED COORDINATES",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MotoTextMuted,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                                Text(
                                    text = String.format(Locale.US, "%.5f°, %.5f°", pin.latitude, pin.longitude),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = targetColor
                                    ),
                                    modifier = Modifier.testTag("pin_coordinates_text")
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Address / Name Display Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isGeocoding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .testTag("reverse_geocoding_indicator"),
                                        strokeWidth = 2.dp,
                                        color = targetColor
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Finding address & landmark...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MotoTextSecondary,
                                            fontSize = 13.sp
                                        )
                                    )
                                } else if (!resolvedAddress.isNullOrBlank()) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = targetColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = resolvedAddress!!,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MotoTextPrimary,
                                            fontSize = 13.sp
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("pin_resolved_address_text")
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MotoTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Custom Map Pin",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MotoTextSecondary,
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier.testTag("pin_resolved_address_text")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Confirm Location Button
                            Button(
                                onClick = {
                                    val finalAddress = resolvedAddress?.takeIf { it.isNotBlank() }
                                        ?: String.format(Locale.US, "Dropped Pin (%.4f, %.4f)", pin.latitude, pin.longitude)
                                    onConfirmLocation(pin.latitude, pin.longitude, finalAddress)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("confirm_location_button")
                                    .testTag("confirm_pin_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = targetColor,
                                    contentColor = Color(0xFF0F1113)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Confirm",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (target == LocationPickerTarget.START) "Confirm as Start Location" else "Confirm as Destination",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                )
                            }
                        } else {
                            // Prompt to tap map
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TouchApp,
                                    contentDescription = null,
                                    tint = MotoTextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "No location selected yet",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MotoTextPrimary,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Text(
                                        text = "Tap or long-press on the map above to drop a pin",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MotoTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("confirm_location_button"),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = MotoCardBorder,
                                    disabledContentColor = MotoTextMuted
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Tap Map to Drop Pin",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
