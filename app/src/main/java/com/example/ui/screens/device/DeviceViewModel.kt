package com.example.ui.screens.device

import androidx.lifecycle.ViewModel
import com.example.ble.BleRepository
import com.example.ble.BleRepositoryProvider
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.RouteTransferProgress
import kotlinx.coroutines.flow.StateFlow

class DeviceViewModel(
    private val bleRepository: BleRepository = BleRepositoryProvider.instance
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState
    val connectedDevice: StateFlow<MotoNavDevice?> = bleRepository.connectedDevice
    val transferProgress: StateFlow<RouteTransferProgress> = bleRepository.transferProgress
    val lastError: StateFlow<String?> = bleRepository.lastError
    val diagnostics: StateFlow<BleDiagnostics> = bleRepository.diagnostics

    fun connect() {
        bleRepository.connect("MotoNav-01")
    }

    fun disconnect() {
        bleRepository.disconnect()
    }

    fun toggleConnect() {
        if (connectionState.value == ConnectionState.Disconnected || connectionState.value == ConnectionState.Error) {
            connect()
        } else {
            disconnect()
        }
    }

    fun readStatus() {
        bleRepository.readStatus()
    }

    fun sendTestRoute() {
        bleRepository.sendTestRoute()
    }

    fun cancelTransfer() {
        bleRepository.cancelTransfer()
    }

    fun simulateError() {
        bleRepository.simulateError()
    }

    fun resetError() {
        bleRepository.resetError()
    }
}
