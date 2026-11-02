package com.pinpulse.radar.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.User
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.viewmodel.MainViewModel
import com.pinpulse.radar.ui.viewmodel.MapFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val currentUser by viewModel.currentUser.collectAsState()
    val visibleUsers by viewModel.visibleUsers.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val hiddenFromGeneral by viewModel.hiddenFromGeneral.collectAsState()
    val mapStyle by viewModel.mapStyle.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    val selectedPerson by viewModel.selectedPerson.collectAsState()
    val activeAlert by viewModel.activeAlert.collectAsState()
    val kickState by viewModel.kickState.collectAsState()

    var activeTab by remember { mutableStateOf("map") } // "map", "friends", "groups"
    var showProfileDialog by remember { mutableStateOf(false) }
    var centerTarget by remember { mutableStateOf<User?>(null) }

    // Request Location and Notification Permissions
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            (context as? com.pinpulse.radar.MainActivity)?.fetchImmediateLocation()
            (context as? com.pinpulse.radar.MainActivity)?.requestBatteryOptimizationExemption()
            com.pinpulse.radar.service.LocationTrackingService.start(context)
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        locationPermissionLauncher.launch(permissions.toTypedArray())
        (context as? com.pinpulse.radar.MainActivity)?.requestBatteryOptimizationExemption()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RadarDarkBg)
    ) {
        // 1. Google Maps Canvas View
        MapViewComponent(
            visibleUsers = visibleUsers,
            currentUserId = currentUser?.id,
            mapStyle = mapStyle,
            onAvatarClick = { userId ->
                viewModel.selectPersonForSheet(userId)
            },
            onMapClick = {
                viewModel.clearSelectedPerson()
            },
            centerTarget = centerTarget
        )

        // 2. Top Filter Bar & Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            // Live Alert Banner (when someone turns off location)
            AlertNotificationBanner(
                alert = activeAlert,
                onDismiss = { viewModel.dismissAlert() }
            )

            // Filter Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter: General (All)
                FilterChipItem(
                    label = "General (All)",
                    isSelected = currentFilter is MapFilter.General,
                    onClick = { viewModel.setFilter(MapFilter.General) }
                )

                // Filter: Friends Only
                FilterChipItem(
                    label = "Friends Only (${friends.size})",
                    isSelected = currentFilter is MapFilter.FriendsOnly,
                    onClick = { viewModel.setFilter(MapFilter.FriendsOnly) }
                )

                // Filter: Individual Groups
                groups.forEach { group ->
                    val isGroupSelected = (currentFilter as? MapFilter.GroupFilter)?.group?.id == group.id
                    FilterChipItem(
                        label = group.name,
                        isSelected = isGroupSelected,
                        onClick = { viewModel.setFilter(MapFilter.GroupFilter(group)) }
                    )
                }
            }

            var isBatteryIgnored by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                    } else true
                )
            }

            if (!isBatteryIgnored) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .background(Color(0xFF3B2508), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFFD97706), RoundedCornerShape(10.dp))
                        .clickable {
                            (context as? com.pinpulse.radar.MainActivity)?.requestBatteryOptimizationExemption()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                isBatteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "⚡ 24/7 Background Tracking: Tap to disable battery restrictions so you stay online when app is closed.",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 3. Floating Quick Buttons (Top Right & Bottom Right)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 54.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Map Style Switcher (Streets -> Satellite -> Hybrid)
            FloatingActionButton(
                onClick = {
                    val nextStyle = when (mapStyle) {
                        "streets" -> "satellite"
                        "satellite" -> "hybrid"
                        else -> "streets"
                    }
                    viewModel.setMapStyle(nextStyle)
                },
                modifier = Modifier.size(46.dp),
                containerColor = RadarCardBg,
                contentColor = RadarBlue,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Map Style", modifier = Modifier.size(22.dp))
            }

            // My Profile Dialog Button
            FloatingActionButton(
                onClick = { showProfileDialog = true },
                modifier = Modifier.size(46.dp),
                containerColor = RadarCardBg,
                contentColor = PulseGreen,
                shape = CircleShape
            ) {
                if (!currentUser?.avatarBase64.isNullOrEmpty()) {
                    AsyncImage(
                        model = currentUser?.avatarBase64,
                        contentDescription = "Profile",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.size(22.dp))
                }
            }
        }

        // Center on My Location Button
        FloatingActionButton(
            onClick = {
                // copy() with a fresh timestamp so pressing the button again always re-centers
                currentUser?.let { centerTarget = it.copy(lastUpdated = System.currentTimeMillis()) }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 90.dp, end = 16.dp)
                .size(52.dp),
            containerColor = RadarBlue,
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "My Location")
        }

        // 4. Single-Tap Person Details Bottom Sheet
        AnimatedVisibility(
            visible = selectedPerson != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 70.dp)
        ) {
            selectedPerson?.let { person ->
                val currentGroup = (currentFilter as? MapFilter.GroupFilter)?.group
                PersonDetailSheet(
                    targetUser = person,
                    currentUser = currentUser,
                    currentGroup = currentGroup,
                    isHiddenFromGeneral = hiddenFromGeneral.contains(person.id),
                    viewModel = viewModel,
                    onClose = { viewModel.clearSelectedPerson() }
                )
            }
        }

        // 5. Bottom Navigation Bar
        NavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            containerColor = RadarCardBg
        ) {
            NavigationBarItem(
                selected = activeTab == "map",
                onClick = {
                    activeTab = "map"
                    viewModel.clearSelectedPerson()
                },
                icon = { Icon(Icons.Default.LocationOn, contentDescription = "Radar") },
                label = { Text("Radar") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PulseGreen,
                    selectedTextColor = PulseGreen,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = Color(0x2210B981)
                )
            )

            NavigationBarItem(
                selected = activeTab == "friends",
                onClick = { activeTab = "friends" },
                icon = {
                    if (pendingRequests.isNotEmpty()) {
                        BadgedBox(badge = { Badge { Text("${pendingRequests.size}") } }) {
                            Icon(Icons.Default.People, contentDescription = "Friends")
                        }
                    } else {
                        Icon(Icons.Default.People, contentDescription = "Friends")
                    }
                },
                label = { Text("Friends") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RadarBlue,
                    selectedTextColor = RadarBlue,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = Color(0x223B82F6)
                )
            )

            NavigationBarItem(
                selected = activeTab == "groups",
                onClick = { activeTab = "groups" },
                icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
                label = { Text("Groups") },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PulseGreen,
                    selectedTextColor = PulseGreen,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = Color(0x2210B981)
                )
            )
        }

        // Bottom Sheets for Friends & Groups
        if (activeTab == "friends") {
            ModalBottomSheet(
                onDismissRequest = { activeTab = "map" },
                containerColor = RadarCardBg
            ) {
                FriendsSheet(
                    friends = friends,
                    pendingRequests = pendingRequests,
                    currentUser = currentUser,
                    viewModel = viewModel,
                    onSelectFriend = { friend ->
                        activeTab = "map"
                        centerTarget = friend
                        viewModel.selectPersonForSheet(friend.id)
                    }
                )
            }
        }

        if (activeTab == "groups") {
            ModalBottomSheet(
                onDismissRequest = { activeTab = "map" },
                containerColor = RadarCardBg
            ) {
                GroupsSheet(
                    groups = groups,
                    friends = friends,
                    currentUser = currentUser,
                    viewModel = viewModel,
                    onSelectGroupForFilter = { group ->
                        viewModel.setFilter(MapFilter.GroupFilter(group))
                        activeTab = "map"
                    }
                )
            }
        }

        // Profile Dialog
        if (showProfileDialog && currentUser != null) {
            ProfileDialog(
                user = currentUser!!,
                viewModel = viewModel,
                onDismiss = { showProfileDialog = false }
            )
        }

        // 2-Step Kick Confirmation Dialog with 5-Second Countdown
        kickState?.let { state ->
            KickConfirmationDialog(
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) PulseGreen else RadarCardBg)
            .border(
                width = 1.dp,
                color = if (isSelected) PulseGreen else RadarCardBorder,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.Black else TextPrimary
        )
    }
}
