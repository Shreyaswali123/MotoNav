package com.example

import com.example.ble.AndroidBleRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleMtuTest {

    @Test
    fun defaultAttMtu23RejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(23))
    }

    @Test
    fun attMtu32RejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(32))
    }

    @Test
    fun attMtu33AcceptsCurrentRouteDataValue() {
        assertTrue(AndroidBleRepository.isRouteDataMtuSufficient(33))
    }

    @Test
    fun largerAttMtuAcceptsCurrentRouteDataValue() {
        assertTrue(AndroidBleRepository.isRouteDataMtuSufficient(247))
    }

    @Test
    fun routeTransferRequestsMinimumRequiredAttMtu() {
        assertTrue(AndroidBleRepository.REQUESTED_ROUTE_ATT_MTU >= 33)
        assertTrue(AndroidBleRepository.ROUTE_DATA_VALUE_SIZE == 30)
    }

    @Test
    fun missingNegotiatedMtuRejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(null))
    }
}