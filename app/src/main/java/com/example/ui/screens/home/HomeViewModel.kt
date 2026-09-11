package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ble.BleRepository
import com.example.ble.BleRepositoryProvider
import com.example.data.RouteRepository
import com.example.data.SampleRouteRepository
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.Route
import com.example.model.UnitSystem
import com.example.settings.InMemorySettingsRepository
import com.example.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val deviceName: String = "MotoNav-01",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedDevice: MotoNavDevice? = null,
    val activeRoute: Route,
    val unitSystem: UnitSystem = UnitSystem.KILOMETERS
)

class HomeViewModel(
    private val bleRepository: BleRepository = BleRepositoryProvider.instance,
    val activeRoute: StateFlow<Route> = SampleRouteRepository.instance.selectedRoute,
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository.instance
) : ViewModel() {

    constructor(
        bleRepository: BleRepository = BleRepositoryProvider.instance,
        routeRepository: RouteRepository,
        settingsRepository: SettingsRepository = InMemorySettingsRepository.instance
    ) : this(
        bleRepository = bleRepository,
        activeRoute = routeRepository.selectedRoute,
        settingsRepository = settingsRepository
    )

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState
    val connectedDevice: StateFlow<MotoNavDevice?> = bleRepository.connectedDevice
    val unitSystem: StateFlow<UnitSystem> = settingsRepository.preferences
        .map { it.unitSystem }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnitSystem.KILOMETERS)

    fun toggleConnection() {
        if (connectionState.value == ConnectionState.Disconnected || connectionState.value == ConnectionState.Error) {
            bleRepository.connect("MotoNav-01")
        } else {
            bleRepository.disconnect()
        }
    }

    companion object {
        fun provideFactory(
            bleRepository: BleRepository = BleRepositoryProvider.instance,
            activeRoute: StateFlow<Route>,
            settingsRepository: SettingsRepository = InMemorySettingsRepository.instance
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    bleRepository = bleRepository,
                    activeRoute = activeRoute,
                    settingsRepository = settingsRepository
                ) as T
            }
        }
    }
}
