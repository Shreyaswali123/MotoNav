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
}

class InMemorySettingsRepository : SettingsRepository {

    private val _preferences = MutableStateFlow(NavigationPreferences())
    override val preferences: StateFlow<NavigationPreferences> = _preferences.asStateFlow()

    override val aboutInfo = AboutMotoNavInfo()

    override fun setUnitSystem(unitSystem: UnitSystem) {
        _preferences.update { it.copy(unitSystem = unitSystem) }
    }

    companion object {
        val instance: InMemorySettingsRepository by lazy { InMemorySettingsRepository() }
    }
}
