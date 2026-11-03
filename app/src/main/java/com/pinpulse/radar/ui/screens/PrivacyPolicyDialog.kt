package com.pinpulse.radar.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pinpulse.radar.ui.theme.*

@Composable
fun PrivacyPolicyDialog(
    isMandatoryAcceptance: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = {
            if (!isMandatoryAcceptance) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isMandatoryAcceptance,
            dismissOnClickOutside = !isMandatoryAcceptance,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030712).copy(alpha = 0.95f))
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(PulseGreen.copy(alpha = 0.15f))
                                    .border(1.dp, PulseGreen.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = PulseGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Privacy & Location Consent",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isMandatoryAcceptance) "Action Required: Review & Accept to Enter" else "Legal Disclosure & Privacy Terms",
                                    fontSize = 11.sp,
                                    color = if (isMandatoryAcceptance) Color(0xFFF59E0B) else TextMuted,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (!isMandatoryAcceptance) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Notice Badge
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "🛡️ PinPulse operates as a high-precision live location radar and safety network. Before using this service, you must explicitly consent to real-time location sharing and administrator fleet monitoring.",
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 17.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Terms Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        PrivacySectionItem(
                            icon = Icons.Default.GpsFixed,
                            iconColor = PulseGreen,
                            title = "1. Real-Time GPS & Background Location Tracking",
                            description = "PinPulse collects, processes, and transmits your exact geographic coordinates (latitude, longitude, speed, altitude, accuracy, and timestamp) in real time. This occurs continuously both while the application is active on screen and while running silently in the background via our persistent foreground location service."
                        )

                        PrivacySectionItem(
                            icon = Icons.Default.AdminPanelSettings,
                            iconColor = Color(0xFFF59E0B),
                            title = "2. Administrative Oversight & Fleet Dashboard Access",
                            description = "By using PinPulse, you expressly acknowledge and agree that authorized network administrators and fleet supervisors access the central PinPulse Command Console to view node positions, connection state, rate limits, subscription validity, device battery status, and operating telemetry for safety coordination, network governance, and emergency response."
                        )

                        PrivacySectionItem(
                            icon = Icons.Default.ShareLocation,
                            iconColor = RadarBlue,
                            title = "3. Circle & Friend Visibility",
                            description = "Your live location is shared exclusively with approved mutual friends and member circles you accept. Friends can view your movement live on their radar canvas and receive emergency SOS notifications with direct Google Maps navigation links."
                        )

                        PrivacySectionItem(
                            icon = Icons.Default.Lock,
                            iconColor = Color(0xFFA855F7),
                            title = "4. Data Collected & Telemetry Diagnostics",
                            description = "We collect only data necessary to operate the live radar:\n• Exact GPS & network location fixes\n• Battery level and power charging status\n• Account details: Display Name, Email, Friend Code, and Profile Avatar\n• Emergency siren signals and SOS broadcast events\nWe never sell your personal data or track browsing history outside PinPulse."
                        )

                        PrivacySectionItem(
                            icon = Icons.Default.CheckCircle,
                            iconColor = PulseGreen,
                            title = "5. Your Rights & Account Revocation",
                            description = "You retain complete control: you can toggle location sharing off, quarantine your node, or permanently delete your account directly inside the app or by request. Deleting your account immediately wipes all retained broker packets and clears your identity from the network."
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isMandatoryAcceptance) {
                            OutlinedButton(
                                onClick = {
                                    (context as? Activity)?.finishAffinity()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f))
                            ) {
                                Text("Decline & Exit", color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onAccept,
                            modifier = Modifier
                                .weight(if (isMandatoryAcceptance) 1.5f else 1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMandatoryAcceptance) "I Agree & Continue" else "Acknowledge & Close",
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySectionItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 26.dp)
        )
    }
}
