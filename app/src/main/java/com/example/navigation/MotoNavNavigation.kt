package com.example.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.device.DeviceScreen
import com.example.ui.screens.device.DeviceViewModel
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.route.RoutePlanningScreen
import com.example.ui.screens.route.RouteViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBackground
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

enum class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("home", "Cockpit", Icons.Filled.Home, Icons.Outlined.Home),
    ROUTE("route", "Plan Ride", Icons.Filled.Navigation, Icons.Outlined.Navigation),
    DEVICE("device", "Device HUD", Icons.Filled.Bluetooth, Icons.Outlined.Bluetooth),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun MotoNavApp(
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.HOME.route

    val routeViewModel: RouteViewModel = viewModel()
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(
            activeRoute = routeViewModel.selectedRoute
        )
    )
    val deviceViewModel: DeviceViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MotoBackground)
            .testTag("motonav_root_scaffold"),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = MotoCardBorder)
                    .navigationBarsPadding()
                    .testTag("motonav_bottom_nav"),
                containerColor = MotoSurface,
                tonalElevation = 8.dp
            ) {
                Screen.entries.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0F1113),
                            selectedTextColor = MotoAmberPrimary,
                            indicatorColor = MotoAmberPrimary,
                            unselectedIconColor = MotoTextSecondary,
                            unselectedTextColor = MotoTextMuted
                        ),
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.HOME.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    routeViewModel = routeViewModel,
                    onNavigateToRoutePlanning = { navController.navigate(Screen.ROUTE.route) },
                    onNavigateToDevice = { navController.navigate(Screen.DEVICE.route) },
                    onNavigateToSettings = { navController.navigate(Screen.SETTINGS.route) }
                )
            }

            composable(Screen.ROUTE.route) {
                RoutePlanningScreen(
                    viewModel = routeViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDevice = { navController.navigate(Screen.DEVICE.route) }
                )
            }

            composable(Screen.DEVICE.route) {
                DeviceScreen(
                    viewModel = deviceViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.SETTINGS.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
