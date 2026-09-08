package com.example.settings

import com.example.model.AboutMotoNavInfo
import com.example.model.NavigationPreferences
import com.example.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface SettingsRepository {
    val preferences: StateFlow<NavigationPreferences>
    val aboutInfo: AboutMotoNavInfo
    fun setUnitSystem(unitSystem: UnitSystem)
    fun toggleAvoidHighways(enabled: Boolean)
    fun togglePreferTwistyRoutes(enabled: Boolean)
    fun toggleAutoReroute(enabled: Boolean)
    fun toggleKeepScreenOn(enabled: Boolean)
    fun toggleCockpitMode(enabled: Boolean)
}

class InMemorySettingsRepository : SettingsRepository {

    private val _preferences = MutableStateFlow(NavigationPreferences())
    override val preferences: StateFlow<NavigationPreferences> = _preferences.asStateFlow()

    override val aboutInfo = AboutMotoNavInfo()

    override fun setUnitSystem(unitSystem: UnitSystem) {
        _preferences.update { it.copy(unitSystem = unitSystem) }
    }

    override fun toggleAvoidHighways(enabled: Boolean) {
        _preferences.update { it.copy(avoidHighways = enabled) }
    }

    override fun togglePreferTwistyRoutes(enabled: Boolean) {
        _preferences.update { it.copy(preferTwistyRoutes = enabled) }
    }

    override fun toggleAutoReroute(enabled: Boolean) {
        _preferences.update { it.copy(autoReroute = enabled) }
    }

    override fun toggleKeepScreenOn(enabled: Boolean) {
        _preferences.update { it.copy(keepScreenOn = enabled) }
    }

    override fun toggleCockpitMode(enabled: Boolean) {
        _preferences.update { it.copy(highContrastCockpitMode = enabled) }
    }

    companion object {
        val instance: InMemorySettingsRepository by lazy { InMemorySettingsRepository() }
    }
}
