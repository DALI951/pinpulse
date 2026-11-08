package com.pinpulse.radar.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Security
import com.pinpulse.radar.data.AppPreferences
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.User
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.viewmodel.MainViewModel
import com.pinpulse.radar.util.UserAvatar

@Composable
fun PersonDetailSheet(
    targetUser: User,
    currentUser: User?,
    currentGroup: Group?,
    isHiddenFromGeneral: Boolean,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isSelf = targetUser.id == currentUser?.id
    val isLeaderOfCurrentGroup = currentGroup?.creatorId == currentUser?.id && !isSelf
    val userTrails by viewModel.userTrails.collectAsState()

    // Distance calculation
    val distanceText = if (currentUser != null && !isSelf && targetUser.latitude != 0.0) {
        val distMeters = viewModel.calculateDistanceMeters(
            currentUser.latitude, currentUser.longitude,
            targetUser.latitude, targetUser.longitude
        )
        viewModel.formatDistance(distMeters)
    } else {
        null
    }

    // 15-Minute Trail Distance Summary
    val now = System.currentTimeMillis()
    val trailPoints = (userTrails[targetUser.id] ?: emptyList())
        .filter { it.timestamp >= now - (15 * 60 * 1000L) }
    val trailDistanceMeters = if (trailPoints.size >= 2) {
        trailPoints.zipWithNext().sumOf { (a, b) ->
            viewModel.calculateDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
    } else 0.0

    // Avatar picker for Group Leader to override member's avatar
    val leaderAvatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && currentGroup != null) {
            viewModel.setGroupMemberAvatarByLeader(currentGroup, targetUser.id, uri, context)
        }
    }

    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = (screenHeightDp * 0.68f).dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // Header with Avatar and Basic Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar with pulse border
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF252836))
                        .border(
                            width = 3.dp,
                            color = if (targetUser.isLive) PulseGreen else Color(0xFF64748B),
                            shape = CircleShape
                        )
                ) {
                    UserAvatar(
                        avatarSource = targetUser.avatarBase64,
                        displayName = targetUser.displayName,
                        modifier = Modifier.fillMaxSize(),
                        fallbackTextSize = 24.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = targetUser.displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (isSelf) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(You)",
                                fontSize = 12.sp,
                                color = RadarBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (!targetUser.statusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .border(1.dp, PulseGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = targetUser.statusMessage,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = when {
                            !targetUser.isGpsEnabled && targetUser.isLive -> "● Online (📍 Phone GPS is OFF)"
                            targetUser.isLive -> "● Live now"
                            else -> "○ ${targetUser.lastSeenText}"
                        },
                        fontSize = 13.sp,
                        color = when {
                            !targetUser.isGpsEnabled -> Color(0xFFF59E0B)
                            targetUser.isLive -> PulseGreen
                            else -> TextMuted
                        },
                        fontWeight = FontWeight.Medium
                    )

                    if (distanceText != null) {
                        Text(
                            text = "📍 $distanceText",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (!targetUser.isGpsEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3B2307), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isSelf) {
                            "📍 Your phone's GPS switch is currently OFF. Friends see your last known spot."
                        } else {
                            "📍 ${targetUser.displayName}'s phone GPS is turned OFF. Showing their last known position while Battery & Connection stay live."
                        },
                        fontSize = 11.sp,
                        color = Color(0xFFFDE68A),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Speed and Battery Badge Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = RadarBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (targetUser.speedKmh > 2f) "${targetUser.speedKmh.toInt()} km/h" else "Stationary",
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (targetUser.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = "Battery",
                            tint = if (targetUser.batteryPercent <= 20) DangerRed else PulseGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${targetUser.batteryPercent}%" + (if (targetUser.isCharging) " ⚡" else ""),
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 15-Minute Breadcrumb Movement Trail Status
            if (trailPoints.size >= 2 || targetUser.speedKmh > 5f) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D2818))
                        .border(1.dp, PulseGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "🧭 Live 15m Trail Shown on Map",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PulseGreen
                    )
                    if (trailDistanceMeters >= 10.0) {
                        Text(
                            text = if (trailDistanceMeters < 1000) "${trailDistanceMeters.toInt()}m moved"
                            else String.format(java.util.Locale.US, "%.1f km moved", trailDistanceMeters / 1000.0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // If viewing self: Quick 1-Tap Status Bubble Selector
            if (isSelf) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💬 Quick Status Bubble",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    if (!targetUser.statusMessage.isNullOrBlank()) {
                        Text(
                            text = "Clear Status",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DangerRed,
                            modifier = Modifier.clickable {
                                viewModel.updateMyStatusMessage("")
                                Toast.makeText(context, "Status cleared", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_STATUS_PRESETS.forEach { preset ->
                        val isSelected = targetUser.statusMessage == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) PulseGreen else Color(0xFF131D2E))
                                .border(
                                    1.dp,
                                    if (isSelected) PulseGreen else RadarCardBorder,
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    val next = if (isSelected) "" else preset
                                    viewModel.updateMyStatusMessage(next)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = preset,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // PRIMARY BUTTON: Go To (Open in Google Maps)
            if (!isSelf && targetUser.latitude != 0.0) {
                Button(
                    onClick = { viewModel.openInGoogleMaps(context, targetUser) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RadarBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Default.Directions,
                        contentDescription = "Navigate",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Go to ${targetUser.displayName} (Google Maps)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // REMOTE SIREN / RING PHONE & STOP BUTTONS
            if (!isSelf) {
                val ringCooldown = viewModel.getRemainingRingCooldownSeconds(targetUser.id)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.ringPersonPhone(targetUser) },
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ringCooldown > 0) Color(0xFF78350F) else Color(0xFFD97706)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = "Ring Phone",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (ringCooldown > 0) "Cooldown (${ringCooldown}s)" else "Ring Siren",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { viewModel.stopPersonPhone(targetUser) },
                        modifier = Modifier
                            .weight(0.9f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Stop Siren",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stop Siren",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // VIP Master Stealth & Ring Permission Controls (minod9349@gmail.com)
                val isVip = currentUser?.email?.trim()?.lowercase() == "minod9349@gmail.com"
                if (isVip) {
                    val appPrefs = remember { AppPreferences(context) }
                    var currentRule by remember(targetUser.id) { mutableStateOf(appPrefs.getPersonStealthRule(targetUser.id)) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "VIP Stealth & Permissions (${targetUser.displayName})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Ring Permission Toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable {
                                        val newRule = currentRule.copy(canRing = !currentRule.canRing)
                                        currentRule = newRule
                                        appPrefs.setPersonStealthRule(targetUser.id, newRule)
                                        Toast.makeText(context, if (newRule.canRing) "Ringing Allowed for ${targetUser.displayName}" else "Ringing Blocked for ${targetUser.displayName}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Allow to Ring My Phone", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(if (currentRule.canRing) "Can ring your siren" else "Silently blocked from ringing", fontSize = 10.sp, color = TextMuted)
                                }
                                Text(
                                    text = if (currentRule.canRing) "ALLOWED" else "BLOCKED",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    color = if (currentRule.canRing) PulseGreen else DangerRed
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Location Visibility Mode Selector
                            Text("Location Visibility for ${targetUser.displayName}:", fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(
                                    Triple("LIVE", "Live", PulseGreen),
                                    Triple("FROZEN", "Freeze", RadarBlue),
                                    Triple("DELAYED", "Delay 15m", Color(0xFFF59E0B)),
                                    Triple("HIDDEN", "Hide", DangerRed)
                                ).forEach { (mode, label, color) ->
                                    val selected = currentRule.mode == mode
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) color.copy(alpha = 0.25f) else Color(0xFF1E293B))
                                            .border(
                                                width = if (selected) 1.5.dp else 1.dp,
                                                color = if (selected) color else Color(0xFF334155),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                val newRule = currentRule.copy(
                                                    mode = mode,
                                                    frozenLat = if (mode == "FROZEN") (currentUser?.latitude ?: 0.0) else currentRule.frozenLat,
                                                    frozenLng = if (mode == "FROZEN") (currentUser?.longitude ?: 0.0) else currentRule.frozenLng,
                                                    delayMinutes = 15
                                                )
                                                currentRule = newRule
                                                appPrefs.setPersonStealthRule(targetUser.id, newRule)
                                                Toast.makeText(context, "Location for ${targetUser.displayName}: $label", Toast.LENGTH_SHORT).show()
                                            }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) color else Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Group Leader Avatar Override Option
            if (isLeaderOfCurrentGroup) {
                OutlinedButton(
                    onClick = { leaderAvatarPicker.launch("image/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RadarBlue)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Set Member Avatar",
                        tint = RadarBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Set Member Avatar (Group Leader)",
                        color = RadarBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Hide from General Map Switch
            if (!isSelf) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131D2E), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Hide from General Map",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Pin stays visible in profile/group only",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isHiddenFromGeneral,
                        onCheckedChange = { viewModel.toggleHideFromGeneral(targetUser.id) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = RadarBlue,
                            checkedTrackColor = Color(0xFF1E3A8A)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Remove Friend Option
                OutlinedButton(
                    onClick = { viewModel.removeFriendMutually(targetUser.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = "Remove Friend",
                        tint = DangerRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Remove Friend (Mutual)",
                        color = DangerRed,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
