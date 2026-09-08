package com.example.route

import com.example.network.ValhallaManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaManeuverConverterTest {

    @Test
    fun testLeftTurnFamilyMapsToMotoNavLeft() {
        val leftTypes = listOf(14, 15, 16, 17, 19, 20)
        for (t in leftTypes) {
            val vm = ValhallaManeuver(type = t, instruction = "Turn")
            assertEquals("Valhalla type $t should map to LEFT (1)", 1.toByte(), ValhallaManeuverConverter.resolveMotoNavType(vm))
        }
    }

    @Test
    fun testRightTurnFamilyMapsToMotoNavRight() {
        val rightTypes = listOf(9, 10, 11, 12, 13, 18)
        for (t in rightTypes) {
            val vm = ValhallaManeuver(type = t, instruction = "Turn")
            assertEquals("Valhalla type $t should map to RIGHT (2)", 2.toByte(), ValhallaManeuverConverter.resolveMotoNavType(vm))
        }
    }

    @Test
    fun testDestinationMapsToMotoNavDestination() {
        val vm = ValhallaManeuver(type = 4, instruction = "Arrive")
        assertEquals(5.toByte(), ValhallaManeuverConverter.resolveMotoNavType(vm))
    }

    @Test
    fun testRoundaboutDirectionFromBearings() {
        // Turning right: bearing 0 -> 90 (+90 deg delta)
        val vmRight = ValhallaManeuver(
            type = 26,
            bearingBefore = 0,
            bearingAfter = 90,
            instruction = "Enter roundabout"
        )
        assertEquals(2.toByte(), ValhallaManeuverConverter.resolveMotoNavType(vmRight))

        // Turning left: bearing 259 -> 238 (-21 deg / 339 deg delta)
        val vmLeft = ValhallaManeuver(
            type = 26,
            bearingBefore = 259,
            bearingAfter = 238,
            instruction = "Enter roundabout"
        )
        assertEquals(1.toByte(), ValhallaManeuverConverter.resolveMotoNavType(vmLeft))
    }

    @Test
    fun testUnsupportedManeuversAreSkippedSafely() {
        val unsupportedTypes = listOf(1, 2, 3, 6, 7, 8, 22)
        for (t in unsupportedTypes) {
            val vm = ValhallaManeuver(type = t, instruction = "Drive")
            assertNull("Valhalla type $t should return null and not produce an invalid type", ValhallaManeuverConverter.resolveMotoNavType(vm))
        }
    }

    @Test
    fun testConvertManeuversRemapsIndicesAndConvertsDistance() {
        val maneuvers = listOf(
            ValhallaManeuver(type = 3, beginShapeIndex = 0, length = 0.2), // Depart (unsupported)
            ValhallaManeuver(type = 10, beginShapeIndex = 9, length = 0.384, instruction = "Turn right"), // Right
            ValhallaManeuver(type = 15, beginShapeIndex = 30, length = 0.166, instruction = "Turn left"), // Left
            ValhallaManeuver(type = 4, beginShapeIndex = 403, length = 0.0, instruction = "Destination") // Arrive
        )

        val indexMap = mapOf(
            0 to 0,
            9 to 5,
            30 to 12,
            403 to 100
        )

        val converted = ValhallaManeuverConverter.convertManeuvers(maneuvers, indexMap)

        // Type 3 was skipped, 3 remain
        assertEquals(3, converted.size)

        // Maneuver 0: Right, pointIndex = 5, dist = 384m
        assertEquals(5, converted[0].pointIndex)
        assertEquals(2.toByte(), converted[0].motoNavType)
        assertEquals(384, converted[0].distanceMeters)

        // Maneuver 1: Left, pointIndex = 12, dist = 166m
        assertEquals(12, converted[1].pointIndex)
        assertEquals(1.toByte(), converted[1].motoNavType)
        assertEquals(166, converted[1].distanceMeters)

        // Maneuver 2: Destination, pointIndex = 100, dist = 0m
        assertEquals(100, converted[2].pointIndex)
        assertEquals(5.toByte(), converted[2].motoNavType)
        assertEquals(0, converted[2].distanceMeters)
    }
}
