package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Route
import com.example.model.RoutePoint
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoTextPrimary
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Reusable MapLibre interactive map component for MotoNav.
 * Displays a real geographic vector map centered at Hubballi, Karnataka by default.
 * Renders the real Valhalla route geometry using a GeoJSON LineString and LineLayer.
 * Supports automatic camera fitting guarded by route identity, origin marker,
 * destination marker, and maneuver markers.
 */
@Composable
fun MotoNavMap(
    modifier: Modifier = Modifier,
    route: Route? = null,
    waypoints: List<RoutePoint> = route?.waypoints ?: emptyList(),
    styleUri: String = MapConfig.DEFAULT_STYLE_URI,
    initialLatitude: Double = MapConfig.DEFAULT_LATITUDE,
    initialLongitude: Double = MapConfig.DEFAULT_LONGITUDE,
    initialZoom: Double = MapConfig.DEFAULT_ZOOM
) {
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = initialLongitude, latitude = initialLatitude),
            zoom = initialZoom
        )
    )

    val effectiveStart = route?.startLocation ?: waypoints.firstOrNull()
    val effectiveDest = route?.destination ?: if (waypoints.size > 1) waypoints.lastOrNull() else null

    val lineString = remember(waypoints) {
        RouteMapAdapter.toLineString(waypoints)
    }

    val routeSignature = remember(route, waypoints) {
        RouteMapAdapter.computeRouteSignature(
            routeId = route?.id,
            startLocation = effectiveStart,
            destination = effectiveDest,
            waypoints = waypoints
        )
    }

    // Fit camera once per new route geometry; guarded against continuous recomposition and manual gestures
    LaunchedEffect(routeSignature) {
        if (routeSignature != null) {
            val bounds = RouteMapAdapter.computeRouteBoundingBox(
                startLocation = effectiveStart,
                destination = effectiveDest,
                waypoints = waypoints
            )
            if (bounds != null) {
                try {
                    cameraState.animateTo(
                        boundingBox = bounds,
                        padding = PaddingValues(48.dp)
                    )
                } catch (_: Throwable) {
                    // Suppress gesture or replacement cancellation exceptions
                }
            }
        }
    }

    // Origin marker position
    val originPosition = remember(effectiveStart) {
        if (RouteMapAdapter.isValid(effectiveStart)) {
            Position(longitude = effectiveStart!!.longitude, latitude = effectiveStart.latitude)
        } else null
    }

    // Destination marker position
    val destPosition = remember(effectiveDest) {
        if (RouteMapAdapter.isValid(effectiveDest)) {
            Position(longitude = effectiveDest!!.longitude, latitude = effectiveDest.latitude)
        } else null
    }

    // Maneuver markers with clutter avoidance near origin/destination
    val maneuverMarkers = remember(route?.maneuvers, effectiveStart, effectiveDest) {
        RouteMapAdapter.extractManeuverMarkers(
            maneuvers = route?.maneuvers,
            origin = effectiveStart,
            destination = effectiveDest
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F1113))
            .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
            .testTag("motonav_map_container")
    ) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(styleUri),
            cameraState = cameraState,
            overlay = MapOverlay {
                include(MapOverlay.Default)

                // Origin Marker
                if (originPosition != null) {
                    OriginMarker(
                        modifier = Modifier.placedAt(originPosition, Alignment.Center)
                    )
                }

                // Destination Marker
                if (destPosition != null) {
                    DestinationMarker(
                        modifier = Modifier.placedAt(destPosition, Alignment.Center)
                    )
                }

                // Maneuver Markers
                maneuverMarkers.forEach { marker ->
                    ManeuverMarker(
                        marker = marker,
                        modifier = Modifier.placedAt(marker.position, Alignment.Center)
                    )
                }
            }
        ) {
            if (lineString != null) {
                val routeSource = rememberGeoJsonSource(
                    data = GeoJsonData.Features(lineString)
                )
                LineLayer(
                    id = "motonav_route_line",
                    source = routeSource,
                    color = const(MotoAmberPrimary),
                    width = const(6.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round)
                )
            }
        }
    }
}

@Composable
private fun OriginMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("origin_marker"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MotoLimeReady.copy(alpha = 0.25f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MotoLimeReady, CircleShape)
                .border(2.dp, Color(0xFF0F1113), CircleShape)
        )
    }
}

@Composable
private fun DestinationMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.testTag("destination_marker"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MotoAmberPrimary.copy(alpha = 0.25f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MotoAmberPrimary, CircleShape)
                .border(2.dp, Color(0xFF0F1113), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun ManeuverMarker(
    marker: MapMarker,
    modifier: Modifier = Modifier
) {
    val (symbolText, color) = when (marker.type) {
        MapMarkerType.MANEUVER_LEFT -> "←" to MotoCyanSecondary
        MapMarkerType.MANEUVER_RIGHT -> "→" to MotoCyanSecondary
        MapMarkerType.MANEUVER_ARRIVE -> "★" to MotoAmberPrimary
        else -> "•" to MotoTextPrimary
    }

    Box(
        modifier = modifier
            .testTag("maneuver_marker_${marker.id}")
            .size(22.dp)
            .background(Color(0xFF16191C), CircleShape)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbolText,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 12.sp
        )
    }
}

