package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Route
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

@Composable
fun MapCanvasView(
    route: Route,
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F1113))
            .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
            .testTag("map_canvas_container")
    ) {
        // High contrast vector motorcycle map
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Background topographic / street grid lines
            val gridSpacing = 40.dp.toPx()
            var x = 0f
            while (x < width) {
                drawLine(
                    color = Color(0xFF1A1D20),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1f
                )
                x += gridSpacing
            }
            var y = 0f
            while (y < height) {
                drawLine(
                    color = Color(0xFF1A1D20),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
                y += gridSpacing
            }

            // Secondary network roads (subtle dark gray strokes)
            drawLine(
                color = Color(0xFF222B38),
                start = Offset(0f, height * 0.45f),
                end = Offset(width, height * 0.48f),
                strokeWidth = 5.dp.toPx()
            )
            drawLine(
                color = Color(0xFF222B38),
                start = Offset(width * 0.35f, 0f),
                end = Offset(width * 0.28f, height),
                strokeWidth = 4.dp.toPx()
            )
            drawLine(
                color = Color(0xFF1D2633),
                start = Offset(width * 0.7f, 0f),
                end = Offset(width * 0.82f, height),
                strokeWidth = 3.dp.toPx()
            )

            // Primary Navigation Route Path (Dynamic based on route waypoints)
            val routePath = Path()
            val startX = width * 0.18f
            val startY = height * 0.82f
            val endX = width * 0.82f
            val endY = height * 0.22f

            routePath.moveTo(startX, startY)
            // Curving twisty motorcycle route segments
            val c1X = width * 0.25f
            val c1Y = height * 0.55f
            val p1X = width * 0.42f
            val p1Y = height * 0.62f

            val c2X = width * 0.58f
            val c2Y = height * 0.70f
            val p2X = width * 0.60f
            val p2Y = height * 0.42f

            val c3X = width * 0.62f
            val c3Y = height * 0.25f

            routePath.cubicTo(c1X, c1Y, p1X, p1Y, p1X, p1Y)
            routePath.cubicTo(c2X, c2Y, p2X, p2Y, p2X, p2Y)
            routePath.cubicTo(c3X, c3Y, width * 0.72f, height * 0.28f, endX, endY)

            // Route Glow underlayer
            drawPath(
                path = routePath,
                color = MotoAmberPrimary.copy(alpha = 0.25f),
                style = Stroke(
                    width = 14.dp.toPx() * zoomLevel,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Primary Route Solid High-Vis Stroke
            drawPath(
                path = routePath,
                color = MotoAmberPrimary,
                style = Stroke(
                    width = 6.dp.toPx() * zoomLevel,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Maneuver Waypoint nodes along the path
            val waypointPositions = listOf(
                Offset(p1X, p1Y),
                Offset(p2X, p2Y),
                Offset(width * 0.72f, height * 0.28f)
            )
            waypointPositions.forEach { wp ->
                drawCircle(
                    color = Color.Black,
                    radius = 5.dp.toPx(),
                    center = wp
                )
                drawCircle(
                    color = MotoCyanSecondary,
                    radius = 3.5.dp.toPx(),
                    center = wp
                )
            }

            // Start Location Indicator (Current Location: Rider base)
            drawCircle(
                color = MotoCyanSecondary.copy(alpha = 0.25f),
                radius = 16.dp.toPx(),
                center = Offset(startX, startY)
            )
            drawCircle(
                color = MotoCyanSecondary,
                radius = 8.dp.toPx(),
                center = Offset(startX, startY)
            )
            drawCircle(
                color = Color.White,
                radius = 3.5.dp.toPx(),
                center = Offset(startX, startY)
            )

            // Destination Marker (Summit / End point)
            drawCircle(
                color = Color.Black,
                radius = 12.dp.toPx(),
                center = Offset(endX, endY)
            )
            drawCircle(
                color = MotoAmberPrimary,
                radius = 9.dp.toPx(),
                center = Offset(endX, endY)
            )
            drawCircle(
                color = Color(0xFF1A1100),
                radius = 4.dp.toPx(),
                center = Offset(endX, endY)
            )
        }

        // Overlay: Start & Destination Badges on Map
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Surface(
                color = MotoSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Start point",
                        tint = MotoCyanSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "START: ${route.startLocation.name ?: "Current Location"}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MotoTextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = MotoSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Destination point",
                        tint = MotoAmberPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "DEST: ${route.destination.name ?: "Destination"}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MotoTextPrimary
                        )
                    )
                }
            }
        }

        // Overlay: Map Controls (Compass & Zoom)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MotoSurface.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "North Compass",
                        tint = MotoCyanSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MotoSurface.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
            ) {
                Column {
                    IconButton(
                        onClick = { zoomLevel = (zoomLevel + 0.15f).coerceAtMost(1.5f) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Zoom In",
                            tint = MotoTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(1.dp)
                            .background(MotoCardBorder)
                            .align(Alignment.CenterHorizontally)
                    )
                    IconButton(
                        onClick = { zoomLevel = (zoomLevel - 0.15f).coerceAtLeast(0.85f) },
                        modifier = Modifier.size(38.dp)
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

        // Bottom HUD Badge: Satellite & Route Mode Indicator
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xCC0F1113),
            border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MotoCyanSecondary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "GPS 3D FIX • COCKPIT VIEW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MotoTextSecondary,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }
    }
}
