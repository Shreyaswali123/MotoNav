package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.UnitSystem
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBackground
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCardGradientDiagonal
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val aboutInfo = viewModel.aboutInfo

    Scaffold(
        modifier = modifier.testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SETTINGS",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                color = MotoTextPrimary
                            )
                        )
                        Text(
                            text = "Preferences & System Info",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MotoTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MotoBackground,
                    titleContentColor = MotoTextPrimary
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MotoBackground)
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
        ) {
            // Unit of Measurement Section
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("settings_units_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Straighten,
                                contentDescription = null,
                                tint = MotoAmberPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "DISTANCE UNITS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MotoAmberPrimary,
                                    letterSpacing = 1.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Segmented Toggle for km / miles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MotoSurfaceVariant)
                                .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            UnitOptionButton(
                                label = "Kilometers (km)",
                                isSelected = preferences.unitSystem == UnitSystem.KILOMETERS,
                                onClick = { viewModel.setUnitSystem(UnitSystem.KILOMETERS) },
                                modifier = Modifier.weight(1f).testTag("unit_km_button")
                            )

                            UnitOptionButton(
                                label = "Miles (mi)",
                                isSelected = preferences.unitSystem == UnitSystem.MILES,
                                onClick = { viewModel.setUnitSystem(UnitSystem.MILES) },
                                modifier = Modifier.weight(1f).testTag("unit_miles_button")
                            )
                        }
                    }
                }
            }

            // Navigation Preferences Section
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("settings_preferences_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MotoAmberPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "NAVIGATION PREFERENCES",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MotoAmberPrimary,
                                    letterSpacing = 1.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PreferenceToggleRow(
                            title = "Prefer Twisty & Scenic Routes",
                            subtitle = "Prioritize curves and backroads over straight expressways",
                            checked = preferences.preferTwistyRoutes,
                            onCheckedChange = { viewModel.togglePreferTwistyRoutes(it) }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MotoCardBorder))

                        PreferenceToggleRow(
                            title = "Avoid Highways",
                            subtitle = "Steer route away from tollways and multilane freeways",
                            checked = preferences.avoidHighways,
                            onCheckedChange = { viewModel.toggleAvoidHighways(it) }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MotoCardBorder))

                        PreferenceToggleRow(
                            title = "Automatic Rerouting",
                            subtitle = "Recalculate route immediately when missing a maneuver",
                            checked = preferences.autoReroute,
                            onCheckedChange = { viewModel.toggleAutoReroute(it) }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MotoCardBorder))

                        PreferenceToggleRow(
                            title = "Keep Phone Screen Awake",
                            subtitle = "Prevent display sleep while planning or mounted on bike",
                            checked = preferences.keepScreenOn,
                            onCheckedChange = { viewModel.toggleKeepScreenOn(it) }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MotoCardBorder))

                        PreferenceToggleRow(
                            title = "High-Contrast Cockpit Mode",
                            subtitle = "OLED pure black theme for bright direct sunlight visibility",
                            checked = preferences.highContrastCockpitMode,
                            onCheckedChange = { viewModel.toggleCockpitMode(it) }
                        )
                    }
                }
            }

            // About MotoNav Section
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("settings_about_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MotoCyanSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "ABOUT MOTONAV",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MotoCyanSecondary,
                                    letterSpacing = 1.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = aboutInfo.description,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MotoTextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // App Version & Firmware Version Info
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp)),
                            color = MotoSurfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                AboutInfoField(
                                    label = "App Version",
                                    value = aboutInfo.appVersion
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                AboutInfoField(
                                    label = "Firmware Version Placeholder",
                                    value = aboutInfo.firmwareVersionPlaceholder,
                                    highlight = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                AboutInfoField(
                                    label = "Hardware Target",
                                    value = aboutInfo.hardwareTarget
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitOptionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) MotoAmberPrimary else Color.Transparent
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                    color = if (isSelected) Color(0xFF0F1113) else MotoTextSecondary,
                    fontSize = 13.sp
                )
            )
        }
    }
}

@Composable
private fun PreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MotoTextPrimary,
                    fontSize = 14.sp
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MotoTextSecondary,
                    fontSize = 12.sp
                )
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0F1113),
                checkedTrackColor = MotoAmberPrimary,
                uncheckedThumbColor = MotoTextSecondary,
                uncheckedTrackColor = MotoSurfaceVariant
            )
        )
    }
}

@Composable
private fun AboutInfoField(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MotoTextMuted,
                fontSize = 12.sp
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (highlight) MotoAmberPrimary else MotoTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        )
    }
}
