package com.pinpulse.radar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pinpulse.radar.model.User
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun SubscriptionStatusBanner(
    user: User,
    friendsCount: Int,
    groupsCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val planUpper = user.subscriptionPlan.uppercase()
    val badgeColor = when (planUpper) {
        "FAMILY" -> PulseGreen
        "BUSINESS" -> RadarBlue
        "CUSTOM" -> Color(0xFFA855F7)
        else -> Color(0xFFF59E0B)
    }
    val planIcon = when (planUpper) {
        "FAMILY" -> "👨‍👩‍👧‍👦"
        "BUSINESS" -> "🏢"
        "CUSTOM" -> "⚡"
        else -> "🎁"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RadarCardBg.copy(alpha = 0.94f))
            .border(1.dp, badgeColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$planIcon $planUpper",
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "⏳ ${user.remainingTimeFormatted}",
                color = if (user.remainingDaysCount <= 2) Color(0xFFFBBF24) else TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "• 👥 $friendsCount/${user.maxFriends}F • 🛡️ $groupsCount/${user.maxGroups}G",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Text(
            text = "Plan ›",
            color = badgeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SubscriptionPlanDialog(
    user: User,
    friendsCount: Int,
    groupsCount: Int,
    onDismiss: () -> Unit
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val planUpper = user.subscriptionPlan.uppercase()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeightDp * 0.86f).dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "💎 Subscription & Quotas",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Account Code: ${user.friendCode}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "✕",
                        color = TextMuted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Current Plan Summary Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131D31), RoundedCornerShape(14.dp))
                        .border(1.dp, PulseGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = user.planDisplayName,
                            color = PulseGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Box(
                            modifier = Modifier
                                .background(PulseGreen.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = user.accountStatus.uppercase(),
                                color = PulseGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⏳ Time Remaining: ${user.remainingTimeFormatted}",
                        color = Color(0xFFFDE68A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Friends Quota Bar
                    val friendProgress = if (user.maxFriends > 0) {
                        (friendsCount.toFloat() / user.maxFriends.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Friends Quota", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            "$friendsCount / ${user.maxFriends} Friends",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = friendProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (friendsCount >= user.maxFriends) DangerRed else PulseGreen,
                        trackColor = Color(0xFF1E293B)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Groups Quota Bar
                    val groupProgress = if (user.maxGroups > 0) {
                        (groupsCount.toFloat() / user.maxGroups.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Groups Quota", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            "$groupsCount / ${user.maxGroups} Groups",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = groupProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (groupsCount >= user.maxGroups) DangerRed else RadarBlue,
                        trackColor = Color(0xFF1E293B)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚡ Live Telemetry Rate: Every ${user.rateLimitSec}s",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "AVAILABLE PINPULSE PACKS",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "🎁 Pack Demo (Free Trial)",
                    price = "FREE • 7 Days Access",
                    features = "• Add up to 1 Friend\n• 0 Groups included\n• 7 Days free trial access",
                    isCurrent = planUpper == "DEMO",
                    accentColor = Color(0xFFF59E0B)
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "👨‍👩‍👧‍👦 Pack Family",
                    price = "10 Friends • 2 Groups",
                    features = "• Add up to 10 Friends\n• Create or join up to 2 Groups\n• Full 24/7 Live Radar, 15m Trails & Siren",
                    isCurrent = planUpper == "FAMILY",
                    accentColor = PulseGreen
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "🏢 Pack Business",
                    price = "50 Friends • 10 Groups",
                    features = "• Add up to 50 Friends / Team Members\n• Create or join up to 10 Groups\n• High-frequency 3s Fleet Telemetry & SOS",
                    isCurrent = planUpper == "BUSINESS",
                    accentColor = RadarBlue
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "⚡ Custom Enterprise Plan",
                    price = "100+ Users • Custom Quotas",
                    features = "• Custom Friends & Fleet Capacity (100+ users)\n• Custom Groups & Rate Limits\n• Managed directly from Fleet Command Dashboard",
                    isCurrent = planUpper == "CUSTOM",
                    accentColor = Color(0xFFA855F7)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RadarBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlanTierCard(
    title: String,
    price: String,
    features: String,
    isCurrent: Boolean,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) accentColor.copy(alpha = 0.12f) else Color(0xFF0F172A),
                RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) accentColor else RadarCardBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .background(accentColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("CURRENT", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Text(price, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(features, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
fun AccountLockScreen(
    user: User,
    onSignOut: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val statusUpper = user.accountStatus.uppercase()
    val isBanned = statusUpper == "BANNED"
    val isDeactivated = statusUpper == "DEACTIVATED"
    val isExpired = user.isSubscriptionExpired || statusUpper == "EXPIRED"

    val title = when {
        isBanned -> "🚫 Account Suspended"
        isDeactivated -> "⏸️ Account Deactivated — Payment Required"
        isExpired -> "⌛ ${user.planDisplayName} Expired"
        else -> "🔒 Account Cut Off"
    }

    val description = when {
        isBanned -> "Your PinPulse account has been suspended by the administrator. Your live radar is cut off, nobody can see your location, and all features (Map, Friends, Groups, SOS) are disabled."
        isDeactivated -> "Your account is currently deactivated pending subscription payment. Your radar is cut off and nobody can see you until your plan is activated."
        else -> "Your ${user.planDisplayName} has expired (0 days left). Your radar is currently cut off — you cannot use the map, add friends, join groups, or send SOS alerts, and nobody can see your location until you upgrade."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarDarkBg)
            .systemBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, DangerRed)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // User Identity & Copy Code Box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131D31), RoundedCornerShape(14.dp))
                        .border(1.dp, RadarCardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(user.displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text(user.email, color = TextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Friend Code: ${user.friendCode}", color = PulseGreen, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Friend Code", user.friendCode))
                            android.widget.Toast.makeText(context, "Friend Code copied!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RadarBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Copy Code", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "CHOOSE A PLAN TO UNLOCK YOUR RADAR",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(10.dp))

                PlanTierCard(
                    title = "👨‍👩‍👧‍👦 Pack Family",
                    price = "10 Friends • 2 Groups • 30 Days",
                    features = "• Add up to 10 Friends & 2 Family/Circle Groups\n• Full 24/7 Live GPS Radar & 15-Minute Movement Trails\n• Emergency SOS Siren, Vibration & Push Notifications",
                    isCurrent = false,
                    accentColor = PulseGreen
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "🏢 Pack Business",
                    price = "50 Friends • 10 Groups • 30 Days",
                    features = "• Add up to 50 Friends / Team / Fleet Units\n• Create or join up to 10 Groups\n• High-frequency 2s–3s Live Telemetry & Fleet SOS",
                    isCurrent = false,
                    accentColor = RadarBlue
                )
                Spacer(modifier = Modifier.height(8.dp))

                PlanTierCard(
                    title = "⚡ Custom Enterprise / ♾️ Lifetime",
                    price = "100+ Users • Custom Quotas or Lifetime Access",
                    features = "• Custom Friends & Fleet Capacity (100+ users)\n• Optional Permanent Lifetime Activation (Never Expires)\n• Instant remote activation via your Friend Code",
                    isCurrent = false,
                    accentColor = Color(0xFFA855F7)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Send your Friend Code (${user.friendCode}) or Email (${user.email}) to the administrator to activate your chosen plan. Your app will unlock automatically in real time!",
                    color = TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign Out / Switch Account", color = TextSecondary)
                }
            }
        }
    }
}

@Composable
fun SosBroadcastDialog(
    onConfirmSendSos: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown--
        }
        onConfirmSendSos()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F090D)),
            border = androidx.compose.foundation.BorderStroke(2.dp, DangerRed)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(DangerRed.copy(alpha = 0.2f), CircleShape)
                        .border(2.dp, DangerRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$countdown",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "🆘 SENDING EMERGENCY SOS",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Broadcasting your exact GPS coordinates, battery level, and direct Google Maps link to ALL friends and group members in $countdown seconds...",
                    color = Color(0xFFFECACA),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onConfirmSendSos,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Send SOS Now", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
