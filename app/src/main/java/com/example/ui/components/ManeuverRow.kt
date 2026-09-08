package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Maneuver
import com.example.model.ManeuverType
import com.example.model.UnitSystem
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

@Composable
fun ManeuverItemRow(
    maneuver: Maneuver,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = getManeuverIconAndTint(maneuver.type)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MotoSurfaceVariant)
            .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("maneuver_item_${maneuver.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = maneuver.instruction,
                tint = tint,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = maneuver.instruction,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MotoTextPrimary,
                    fontSize = 15.sp
                )
            )
            Text(
                text = maneuver.roadName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MotoTextSecondary,
                    fontSize = 13.sp
                )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (maneuver.distanceMeters > 0) {
            val distText = if (unitSystem == UnitSystem.KILOMETERS) {
                if (maneuver.distanceMeters >= 1000) {
                    String.format("%.1f km", maneuver.distanceMeters / 1000.0)
                } else {
                    "${maneuver.distanceMeters} m"
                }
            } else {
                val feet = (maneuver.distanceMeters * 3.28084).toInt()
                if (feet >= 1000) {
                    String.format("%.1f mi", maneuver.distanceMeters * 0.000621371)
                } else {
                    "$feet ft"
                }
            }

            Text(
                text = distText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MotoAmberPrimary,
                    fontSize = 14.sp
                )
            )
        }
    }
}

private fun getManeuverIconAndTint(type: ManeuverType): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (type) {
        ManeuverType.DEPART -> Pair(Icons.Default.Navigation, MotoCyanSecondary)
        ManeuverType.STRAIGHT -> Pair(Icons.Default.Straight, MotoTextPrimary)
        ManeuverType.TURN_SLIGHT_LEFT -> Pair(Icons.Default.TurnSlightLeft, MotoAmberPrimary)
        ManeuverType.TURN_LEFT -> Pair(Icons.Default.TurnLeft, MotoAmberPrimary)
        ManeuverType.TURN_SHARP_LEFT -> Pair(Icons.Default.TurnSharpLeft, MotoAmberPrimary)
        ManeuverType.TURN_SLIGHT_RIGHT -> Pair(Icons.Default.TurnSlightRight, MotoAmberPrimary)
        ManeuverType.TURN_RIGHT -> Pair(Icons.Default.TurnRight, MotoAmberPrimary)
        ManeuverType.TURN_SHARP_RIGHT -> Pair(Icons.Default.TurnSharpRight, MotoAmberPrimary)
        ManeuverType.U_TURN -> Pair(Icons.AutoMirrored.Filled.RotateLeft, MotoAmberPrimary)
        ManeuverType.ROUNDABOUT -> Pair(Icons.Default.Loop, MotoCyanSecondary)
        ManeuverType.FORK_LEFT -> Pair(Icons.Default.TurnSlightLeft, MotoAmberPrimary)
        ManeuverType.FORK_RIGHT -> Pair(Icons.Default.TurnSlightRight, MotoAmberPrimary)
        ManeuverType.MERGE -> Pair(Icons.AutoMirrored.Filled.ArrowForward, MotoCyanSecondary)
        ManeuverType.ARRIVE -> Pair(Icons.Default.Flag, MotoLimeReady)
    }
}
