package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.Route
import com.example.model.RoutePoint
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoCardBorder
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Reusable MapLibre interactive map component for MotoNav.
 * Displays a real geographic vector map centered at Hubballi, Karnataka by default.
 * Renders the real Valhalla route geometry using a GeoJSON LineString and LineLayer.
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

    val lineString = remember(waypoints) {
        RouteMapAdapter.toLineString(waypoints)
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
            cameraState = cameraState
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
