package com.example.route

import android.util.Log
import com.example.network.ValhallaManeuver

/**
 * MotoNav v1 binary maneuver definition.
 *
 * @property pointIndex Index of the maneuver in the simplified route's points array.
 * @property motoNavType Binary maneuver code: 1 = LEFT, 2 = RIGHT, 5 = DESTINATION.
 * @property distanceMeters Distance in meters for this maneuver step.
 * @property valhallaType Original Valhalla maneuver type code for reference.
 * @property instruction Textual instruction from Valhalla.
 */
data class MotoNavManeuverRecord(
    val pointIndex: Int,
    val motoNavType: Byte,
    val distanceMeters: Int,
    val valhallaType: Int,
    val instruction: String? = null
)

/**
 * Converts Valhalla maneuver records to MotoNav binary maneuvers.
 *
 * Supported MotoNav binary types:
 * - 1 = LEFT
 * - 2 = RIGHT
 * - 5 = DESTINATION
 */
object ValhallaManeuverConverter {

    private const val TAG = "ValhallaManeuverConv"

    const val MOTONAV_TYPE_LEFT: Byte = 1
    const val MOTONAV_TYPE_RIGHT: Byte = 2
    const val MOTONAV_TYPE_DESTINATION: Byte = 5

    // Valhalla left-turn family: 14, 15, 16, 17, 19, 20 -> LEFT (1)
    private val VALHALLA_LEFT_TYPES = setOf(14, 15, 16, 17, 19, 20)

    // Valhalla right-turn family: 9, 10, 11, 12, 13, 18 -> RIGHT (2)
    private val VALHALLA_RIGHT_TYPES = setOf(9, 10, 11, 12, 13, 18)

    // Valhalla destination: 4 -> DESTINATION (5)
    private const val VALHALLA_DESTINATION_TYPE = 4

    // Valhalla roundabout types: 26 (Enter Roundabout), 27 (Exit Roundabout)
    private const val VALHALLA_ROUNDABOUT_ENTER = 26
    private const val VALHALLA_ROUNDABOUT_EXIT = 27

    /**
     * Resolves the MotoNav binary maneuver type (1, 2, 5) from a Valhalla maneuver.
     * Returns null if the maneuver is unsupported in MotoNav v1 binary protocol.
     */
    fun resolveMotoNavType(maneuver: ValhallaManeuver): Byte? {
        val vType = maneuver.type

        if (vType in VALHALLA_LEFT_TYPES) {
            return MOTONAV_TYPE_LEFT
        }

        if (vType in VALHALLA_RIGHT_TYPES) {
            return MOTONAV_TYPE_RIGHT
        }

        if (vType == VALHALLA_DESTINATION_TYPE) {
            return MOTONAV_TYPE_DESTINATION
        }

        if (vType == VALHALLA_ROUNDABOUT_ENTER || vType == VALHALLA_ROUNDABOUT_EXIT) {
            return resolveRoundaboutDirection(maneuver)
        }

        // Unsupported maneuver (e.g. 1 = None/Continue, 3 = Depart, 7 = Stay Straight)
        try {
            Log.d(TAG, "Valhalla maneuver type $vType ('${maneuver.instruction}') is unsupported in MotoNav v1 format and skipped.")
        } catch (_: Throwable) {
            // JVM Unit test safe
        }
        return null
    }

    /**
     * Determines left vs right for roundabout maneuvers based on bearing delta or instruction text.
     */
    private fun resolveRoundaboutDirection(maneuver: ValhallaManeuver): Byte {
        val bBefore = maneuver.bearingBefore
        val bAfter = maneuver.bearingAfter

        if (bBefore != null && bAfter != null) {
            val delta = (bAfter - bBefore + 360) % 360
            if (delta in 1..179) {
                return MOTONAV_TYPE_RIGHT
            } else if (delta in 181..359) {
                return MOTONAV_TYPE_LEFT
            }
        }

        // Fallback to instruction text if bearings are equal or missing
        val text = (maneuver.instruction ?: "").lowercase()
        return when {
            text.contains("right") -> MOTONAV_TYPE_RIGHT
            text.contains("left") -> MOTONAV_TYPE_LEFT
            else -> MOTONAV_TYPE_LEFT // Default navigation convention
        }
    }

    /**
     * Converts a list of Valhalla maneuvers to [MotoNavManeuverRecord]s, mapping
     * each maneuver's original shape index to the simplified route's point index.
     *
     * @param maneuvers List of maneuvers from Valhalla leg.
     * @param oldToNewIndexMap Map from original polyline index to simplified point index.
     */
    fun convertManeuvers(
        maneuvers: List<ValhallaManeuver>,
        oldToNewIndexMap: Map<Int, Int>
    ): List<MotoNavManeuverRecord> {
        val result = mutableListOf<MotoNavManeuverRecord>()

        for (m in maneuvers) {
            val mType = resolveMotoNavType(m) ?: continue
            val newPointIndex = oldToNewIndexMap[m.beginShapeIndex]
            if (newPointIndex == null) {
                try {
                    Log.w(TAG, "Maneuver at original shape index ${m.beginShapeIndex} not found in index map; skipped.")
                } catch (_: Throwable) {}
                continue
            }

            // Convert distance: length is in kilometers when units="kilometers"
            val distMeters = (m.length?.let { kotlin.math.round(it * 1000.0).toInt() } ?: 0)
                .coerceAtLeast(0)

            result.add(
                MotoNavManeuverRecord(
                    pointIndex = newPointIndex,
                    motoNavType = mType,
                    distanceMeters = distMeters,
                    valhallaType = m.type,
                    instruction = m.instruction
                )
            )
        }

        return result
    }
}
