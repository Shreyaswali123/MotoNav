package com.example.ui.screens.route

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SearchLocation
import com.example.model.ConnectionState
import com.example.model.UnitSystem
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.ManeuverItemRow
import com.example.ui.components.MotoNavMap
import com.example.ui.theme.MotoAmberLight
import com.example.ui.theme.MotoAmberPrimary
import com.example.ui.theme.MotoBackground
import com.example.ui.theme.MotoCardBorder
import com.example.ui.theme.MotoCardGradientDiagonal
import com.example.ui.theme.MotoCyanSecondary
import com.example.ui.theme.MotoLimeReady
import com.example.ui.theme.MotoRedError
import com.example.ui.theme.MotoSurface
import com.example.ui.theme.MotoSurfaceVariant
import com.example.ui.theme.MotoTextMuted
import com.example.ui.theme.MotoTextPrimary
import com.example.ui.theme.MotoTextSecondary

enum class LocationPickerTarget {
    START,
    DESTINATION
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlanningScreen(
    viewModel: RouteViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedRoute by viewModel.selectedRoute.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val unitSystem by viewModel.unitSystem.collectAsStateWithLifecycle()

    val startLocation by viewModel.startLocation.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val isGenerateRouteEnabled by viewModel.isGenerateRouteEnabled.collectAsStateWithLifecycle()
    val isGeneratingRoute by viewModel.isGeneratingRoute.collectAsStateWithLifecycle()
    val generatedRoute by viewModel.generatedRoute.collectAsStateWithLifecycle()
    val routeGenerationError by viewModel.routeGenerationError.collectAsStateWithLifecycle()
    val isSendRouteEnabled by viewModel.isSendRouteEnabled.collectAsStateWithLifecycle()
    val locationValidationError by viewModel.locationValidationError.collectAsStateWithLifecycle()
    val locationSearchResults by viewModel.locationSearchResults.collectAsStateWithLifecycle()
    val isSearchingLocations by viewModel.isSearchingLocations.collectAsStateWithLifecycle()
    val popularLocations = viewModel.popularLocations

    val hasValidGeneratedRoute = generatedRoute != null &&
        generatedRoute!!.matchesEndpoints(startLocation, destination)

    var showLocationPicker by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf(LocationPickerTarget.START) }
    var pickerSearchQuery by remember { mutableStateOf("") }

    if (showLocationPicker) {
        LocationSelectionDialog(
            target = pickerTarget,
            searchQuery = pickerSearchQuery,
            isSearching = isSearchingLocations,
            searchResults = locationSearchResults,
            popularLocations = popularLocations,
            onSearchQueryChange = { query ->
                pickerSearchQuery = query
                viewModel.searchLocations(query)
            },
            onLocationSelected = { searchLocation ->
                val point = searchLocation.toRoutePoint()
                if (pickerTarget == LocationPickerTarget.START) {
                    viewModel.selectStartLocation(point)
                } else {
                    viewModel.selectDestination(point)
                }
                showLocationPicker = false
                pickerSearchQuery = ""
            },
            onDismiss = {
                showLocationPicker = false
                pickerSearchQuery = ""
            }
        )
    }

    Scaffold(
        modifier = modifier.testTag("route_planning_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PLAN RIDE",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                color = MotoTextPrimary
                            )
                        )
                        Text(
                            text = "MotoNav Turn-by-Turn Navigation",
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
                        modifier = Modifier.testTag("route_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MotoTextPrimary
                        )
                    }
                },
                actions = {
                    ConnectionStatusBadge(
                        state = connectionState,
                        modifier = Modifier
                            .clickable { onNavigateToDevice() }
                            .padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MotoBackground,
                    titleContentColor = MotoTextPrimary
                )
            )
        },
        bottomBar = {
            // Sticky Bottom Bar: Oversized Motorcycle Action Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("route_bottom_action_bar"),
                color = MotoSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MotoCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .fillMaxWidth()
                ) {
                    if (connectionState == ConnectionState.Transferring) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = transferProgress.stepDescription,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MotoAmberPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "${transferProgress.percentage}%",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MotoAmberPrimary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { transferProgress.percentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MotoAmberPrimary,
                                trackColor = MotoSurfaceVariant
                            )
                        }
                    }

                    if (routeGenerationError != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(MotoRedError.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(1.dp, MotoRedError.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MotoRedError,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = routeGenerationError ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MotoRedError,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Route Generation Button (Generate Route / Regenerate Route)
                    Button(
                        onClick = { viewModel.generateRoute() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (hasValidGeneratedRoute) 44.dp else 56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(
                                1.dp,
                                if (isGenerateRouteEnabled && !isGeneratingRoute) MotoAmberLight.copy(alpha = 0.4f) else MotoCardBorder,
                                RoundedCornerShape(28.dp)
                            )
                            .testTag("generate_route_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasValidGeneratedRoute) MotoSurfaceVariant else MotoAmberPrimary,
                            contentColor = if (hasValidGeneratedRoute) MotoAmberPrimary else Color(0xFF0F1113),
                            disabledContainerColor = MotoSurfaceVariant,
                            disabledContentColor = MotoTextMuted
                        ),
                        shape = RoundedCornerShape(28.dp),
                        enabled = isGenerateRouteEnabled && !isGeneratingRoute
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isGeneratingRoute) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF0F1113),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBike,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = when {
                                    isGeneratingRoute -> "GENERATING ROUTE..."
                                    hasValidGeneratedRoute -> "REGENERATE ROUTE"
                                    startLocation == null && destination == null -> "SELECT START & DESTINATION"
                                    startLocation == null -> "SELECT START POINT"
                                    destination == null -> "SELECT DESTINATION"
                                    locationValidationError != null -> "CHOOSE DIFFERENT DESTINATION"
                                    else -> "GENERATE ROUTE"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // BLE Send Button
                    Button(
                        onClick = { viewModel.sendRouteToMotoNav() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (hasValidGeneratedRoute) 56.dp else 44.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(
                                1.5.dp,
                                if (isSendRouteEnabled) MotoAmberLight.copy(alpha = 0.5f) else MotoCardBorder,
                                RoundedCornerShape(28.dp)
                            )
                            .testTag("send_route_to_motonav_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connectionState == ConnectionState.RouteReady) MotoLimeReady else MotoAmberPrimary,
                            contentColor = Color(0xFF0F1113),
                            disabledContainerColor = MotoSurfaceVariant,
                            disabledContentColor = MotoTextMuted
                        ),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isSendRouteEnabled) 4.dp else 0.dp),
                        enabled = isSendRouteEnabled
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (connectionState == ConnectionState.RouteReady) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    connectionState == ConnectionState.RouteReady -> "ROUTE READY ON MOTONAV-01"
                                    connectionState == ConnectionState.Transferring -> "SENDING TO MOTONAV..."
                                    !hasValidGeneratedRoute -> "GENERATE ROUTE FIRST"
                                    connectionState != ConnectionState.Connected -> "CONNECT DEVICE TO SEND"
                                    else -> "SEND ROUTE TO MOTONAV"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MotoBackground)
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // Interactive Start & Destination Route Points Selector Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(18.dp))
                        .testTag("route_points_selector_card"),
                    colors = CardDefaults.cardColors(containerColor = MotoSurface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Start Point Field
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    pickerTarget = LocationPickerTarget.START
                                    pickerSearchQuery = ""
                                    viewModel.searchLocations("")
                                    showLocationPicker = true
                                }
                                .border(
                                    width = 1.dp,
                                    color = if (startLocation != null) MotoCyanSecondary.copy(alpha = 0.7f) else MotoCardBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .testTag("start_location_field"),
                            color = MotoSurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MotoCyanSecondary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = "Start Point",
                                        tint = MotoCyanSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "START POINT (ORIGIN)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MotoCyanSecondary,
                                            fontSize = 10.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    )
                                    Text(
                                        text = startLocation?.name ?: "Tap to select start point...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (startLocation != null) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (startLocation != null) MotoTextPrimary else MotoTextMuted,
                                            fontSize = 14.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("start_location_name")
                                    )
                                    if (startLocation != null) {
                                        Text(
                                            text = "${String.format("%.4f", startLocation!!.latitude)}°, ${String.format("%.4f", startLocation!!.longitude)}°",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MotoTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                                if (startLocation != null) {
                                    IconButton(
                                        onClick = { viewModel.clearStartLocation() },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("clear_start_location_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear Start Point",
                                            tint = MotoTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Connecting Route Indicator & Swap Button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 18.dp)
                                    .width(2.dp)
                                    .height(18.dp)
                                    .background(MotoCardBorder)
                            )

                            if (startLocation != null && destination != null) {
                                IconButton(
                                    onClick = { viewModel.swapLocations() },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("swap_locations_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = "Swap Locations",
                                        tint = MotoAmberPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // End Point / Destination Field
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    pickerTarget = LocationPickerTarget.DESTINATION
                                    pickerSearchQuery = ""
                                    viewModel.searchLocations("")
                                    showLocationPicker = true
                                }
                                .border(
                                    width = 1.dp,
                                    color = if (destination != null) MotoAmberPrimary.copy(alpha = 0.7f) else MotoCardBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .testTag("destination_location_field"),
                            color = MotoSurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MotoAmberPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = "Destination Point",
                                        tint = MotoAmberPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "DESTINATION (END POINT)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MotoAmberPrimary,
                                            fontSize = 10.sp,
                                            letterSpacing = 0.5.sp
                                        )
                                    )
                                    Text(
                                        text = destination?.name ?: "Tap to select destination...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (destination != null) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (destination != null) MotoTextPrimary else MotoTextMuted,
                                            fontSize = 14.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("destination_location_name")
                                    )
                                    if (destination != null) {
                                        Text(
                                            text = "${String.format("%.4f", destination!!.latitude)}°, ${String.format("%.4f", destination!!.longitude)}°",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MotoTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                                if (destination != null) {
                                    IconButton(
                                        onClick = { viewModel.clearDestination() },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("clear_destination_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear Destination",
                                            tint = MotoTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Validation Error Warning
                        if (locationValidationError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .testTag("location_validation_error"),
                                color = Color(0xFFD32F2F).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = locationValidationError ?: "",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFFFF8A80),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Destination Search / Filter Preset Routes Field
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("destination_search_field"),
                    placeholder = {
                        Text(
                            text = "Search scenic preset routes...",
                            color = MotoTextMuted,
                            fontSize = 15.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MotoAmberPrimary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MotoTextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MotoSurface,
                        unfocusedContainerColor = MotoSurface,
                        focusedBorderColor = MotoAmberPrimary,
                        unfocusedBorderColor = MotoCardBorder,
                        focusedTextColor = MotoTextPrimary,
                        unfocusedTextColor = MotoTextPrimary
                    )
                )
            }

            // Quick Scenic Ride Selection Chips
            item {
                Text(
                    text = "PRESET MOTORCYCLE ROUTES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MotoTextMuted,
                        letterSpacing = 0.8.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(searchResults) { route ->
                        val isSelected = route.id == selectedRoute.id
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.selectRoute(route.id) }
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MotoAmberPrimary else MotoCardBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .testTag("route_chip_${route.id}"),
                            color = if (isSelected) MotoAmberPrimary.copy(alpha = 0.15f) else MotoSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBike,
                                    contentDescription = null,
                                    tint = if (isSelected) MotoAmberPrimary else MotoTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = route.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isSelected) MotoAmberPrimary else MotoTextPrimary
                                        )
                                    )
                                    val dist = if (unitSystem == UnitSystem.KILOMETERS) {
                                        "${String.format("%.1f", route.totalDistanceKm)} km"
                                    } else {
                                        "${String.format("%.1f", route.totalDistanceMiles)} mi"
                                    }
                                    Text(
                                        text = "$dist • ${route.estimatedDurationMinutes} min",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MotoTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Map Area with Start Location Indicator & Destination Marker
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MAP & ROUTE PREVIEW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MotoTextMuted,
                                letterSpacing = 0.8.sp
                            )
                        )

                        Text(
                            text = "${selectedRoute.waypoints.size} WAYPOINTS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MotoCyanSecondary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    MotoNavMap(
                        route = selectedRoute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                }
            }

            // Route Preview Metrics Area (Estimated distance & travel time)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MotoCardGradientDiagonal)
                        .border(1.dp, MotoCardBorder, RoundedCornerShape(16.dp))
                        .testTag("route_preview_metrics_card")
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedRoute.title,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp,
                                        color = MotoTextPrimary
                                    )
                                )
                                Text(
                                    text = selectedRoute.summary,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MotoTextSecondary,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Large metrics display
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MotoSurfaceVariant)
                                .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                                .padding(vertical = 14.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Estimated Distance
                            Column {
                                Text(
                                    text = "EST. DISTANCE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MotoTextMuted,
                                        fontSize = 10.sp
                                    )
                                )
                                val distanceFormatted = if (unitSystem == UnitSystem.KILOMETERS) {
                                    String.format("%.1f KM", selectedRoute.totalDistanceKm)
                                } else {
                                    String.format("%.1f MI", selectedRoute.totalDistanceMiles)
                                }
                                Text(
                                    text = distanceFormatted,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        color = MotoAmberPrimary,
                                        fontSize = 22.sp
                                    )
                                )
                            }

                            // Estimated Travel Time
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "EST. TRAVEL TIME",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MotoTextMuted,
                                        fontSize = 10.sp
                                    )
                                )
                                Text(
                                    text = "${selectedRoute.estimatedDurationMinutes} MIN",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        color = MotoCyanSecondary,
                                        fontSize = 22.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Start & Destination Indicator Badges (Clickable to open location selection)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    pickerTarget = LocationPickerTarget.START
                                    pickerSearchQuery = ""
                                    viewModel.searchLocations("")
                                    showLocationPicker = true
                                }
                                .padding(vertical = 4.dp)
                                .testTag("card_start_location"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = MotoCyanSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "From: ${startLocation?.name ?: selectedRoute.startLocation.name ?: "Select Start Point"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MotoTextSecondary,
                                    fontSize = 12.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    pickerTarget = LocationPickerTarget.DESTINATION
                                    pickerSearchQuery = ""
                                    viewModel.searchLocations("")
                                    showLocationPicker = true
                                }
                                .padding(vertical = 4.dp)
                                .testTag("card_destination_location"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MotoAmberPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "To: ${destination?.name ?: selectedRoute.destination.name ?: "Select Destination"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MotoTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Step-by-Step Maneuvers Preview
            item {
                Text(
                    text = "STEP-BY-STEP MANEUVERS (${selectedRoute.maneuvers.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MotoTextMuted,
                        letterSpacing = 0.8.sp
                    )
                )
            }

            items(selectedRoute.maneuvers) { maneuver ->
                ManeuverItemRow(
                    maneuver = maneuver,
                    unitSystem = unitSystem
                )
            }
        }
    }
}

@Composable
fun LocationSelectionDialog(
    target: LocationPickerTarget,
    searchQuery: String,
    isSearching: Boolean,
    searchResults: List<SearchLocation>,
    popularLocations: List<SearchLocation>,
    onSearchQueryChange: (String) -> Unit,
    onLocationSelected: (SearchLocation) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MotoCardBorder, RoundedCornerShape(24.dp))
                .testTag("location_picker_dialog"),
            color = MotoSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (target == LocationPickerTarget.START) "SELECT START POINT" else "SELECT DESTINATION",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                color = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary
                            )
                        )
                        Text(
                            text = "Search place name or choose suggested location",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MotoTextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("location_picker_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MotoTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Input Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("location_search_input")
                        .testTag("location_picker_search_field"),
                    placeholder = {
                        Text(
                            text = "Enter place name (e.g. KLE Tech, Tolankere)...",
                            color = MotoTextMuted,
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MotoTextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MotoSurfaceVariant,
                        unfocusedContainerColor = MotoSurfaceVariant,
                        focusedBorderColor = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary,
                        unfocusedBorderColor = MotoCardBorder,
                        focusedTextColor = MotoTextPrimary,
                        unfocusedTextColor = MotoTextPrimary
                    )
                )

                if (isSearching) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary,
                        trackColor = MotoSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Results list
                val displayList = if (searchQuery.isBlank()) popularLocations else searchResults

                Text(
                    text = if (searchQuery.isBlank()) "POPULAR & SUGGESTED LOCATIONS" else "SEARCH RESULTS (${displayList.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MotoTextMuted,
                        letterSpacing = 0.8.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (displayList.isEmpty() && !isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No locations found matching \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MotoTextMuted)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("location_search_results"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(displayList) { index, location ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onLocationSelected(location) }
                                    .border(1.dp, MotoCardBorder, RoundedCornerShape(12.dp))
                                    .testTag("location_result_${index}"),
                                color = MotoSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (target == LocationPickerTarget.START) MotoCyanSecondary.copy(alpha = 0.15f)
                                                else MotoAmberPrimary.copy(alpha = 0.15f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (target == LocationPickerTarget.START) Icons.Default.MyLocation else Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = if (target == LocationPickerTarget.START) MotoCyanSecondary else MotoAmberPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = location.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MotoTextPrimary,
                                                fontSize = 14.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (location.address.isNotBlank()) {
                                            Text(
                                                text = location.address,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = MotoTextSecondary,
                                                    fontSize = 12.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${String.format("%.4f", location.latitude)}° N, ${String.format("%.4f", location.longitude)}° E",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MotoTextMuted,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
