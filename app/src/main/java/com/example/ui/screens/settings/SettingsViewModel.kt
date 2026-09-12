package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.example.model.AboutMotoNavInfo
import com.example.model.NavigationPreferences
import com.example.model.UnitSystem
import com.example.settings.InMemorySettingsRepository
import com.example.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository.instance
) : ViewModel() {

    val preferences: StateFlow<NavigationPreferences> = settingsRepository.preferences
    val aboutInfo: AboutMotoNavInfo = settingsRepository.aboutInfo

    fun setUnitSystem(unitSystem: UnitSystem) {
        settingsRepository.setUnitSystem(unitSystem)
    }
}
