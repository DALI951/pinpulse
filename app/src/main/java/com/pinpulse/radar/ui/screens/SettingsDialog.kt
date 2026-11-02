package com.pinpulse.radar.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsState()
    val mapStyle by viewModel.mapStyle.collectAsState()
    val selfMarkerMode by viewModel.selfMarkerMode.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    var isEditingPin by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var pinErrorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
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
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RadarBlue.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = RadarBlue, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 1: IN-APP UPDATER
            Card(
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (updateState.updateAvailable) PulseGreen else RadarCardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = if (updateState.updateAvailable) PulseGreen else RadarBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("App Updates", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }

                        Text("v${updateState.currentVersion}", fontSize = 12.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (updateState.isDownloading) {
                        Text(
                            text = "Downloading update: ${(updateState.downloadProgress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = PulseGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { updateState.downloadProgress },
                            color = PulseGreen,
                            trackColor = Color(0xFF1E293B),
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    } else if (updateState.updateAvailable || updateState.isDownloaded) {
                        val isReady = updateState.isDownloaded
                        Text(
                            text = if (isReady) "🚀 Update ready to install: v${updateState.latestVersion}" else "🚀 New version available: v${updateState.latestVersion}!",
                            fontSize = 13.sp,
                            color = PulseGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isReady) "APK is already downloaded! Tap below to install directly without downloading again." else "Install in 1-tap directly without browser downloads.",
                            fontSize = 12.sp,
                            color = if (isReady) Color.White else TextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.downloadAndInstallUpdate(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(if (isReady) Icons.Default.CheckCircle else Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isReady) "Install Now (Already Downloaded)" else "Download & Install Update", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            text = updateState.statusMessage ?: "Check for the latest PinPulse releases directly from GitHub.",
                            fontSize = 12.sp,
                            color = if (updateState.statusMessage?.contains("latest") == true) PulseGreen else TextMuted
                        )
                        if (updateState.errorMessage != null) {
                            Text(updateState.errorMessage ?: "", fontSize = 11.sp, color = DangerRed)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.checkForUpdates() },
                            enabled = !updateState.isChecking,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (updateState.isChecking) "Checking GitHub..." else "Check for Updates",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // APK Storage Auto-Cleanup Status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF131D2E))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Installer APK Storage", fontSize = 11.sp, color = TextMuted)
                            val apkBytes = remember(updateState) {
                                com.pinpulse.radar.util.UpdateManager.getCachedApkSizeBytes(context)
                            }
                            val apkSizeMb = apkBytes / (1024f * 1024f)
                            Text(
                                if (apkSizeMb > 0.05f) {
                                    if (updateState.isDownloaded) "${String.format("%.1f", apkSizeMb)} MB (Ready to Install)"
                                    else "${String.format("%.1f", apkSizeMb)} MB (Stored)"
                                } else "0.0 MB (Auto-Cleaned)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (updateState.isDownloaded) PulseGreen else (if (apkSizeMb > 0.05f) Color(0xFFF59E0B) else PulseGreen)
                            )
                        }

                        Button(
                            onClick = {
                                val freed = com.pinpulse.radar.util.UpdateManager.cleanupDownloadedApks(context)
                                val freedMb = freed / (1024f * 1024f)
                                Toast.makeText(context, "Storage verified clean! ${String.format("%.1f", freedMb)} MB freed.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Purge Cache", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 2: SIREN SECURITY PIN
            Card(
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Siren Security PIN", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }

                        Button(
                            onClick = {
                                isEditingPin = !isEditingPin
                                newPinInput = ""
                                confirmPinInput = ""
                                pinErrorMessage = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isEditingPin) "Cancel" else "Change PIN", fontSize = 11.sp, color = PulseGreen, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Protects your phone. When someone rings your phone via siren, this PIN is required to silence the alarm.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    if (!isEditingPin) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Current PIN: ", fontSize = 12.sp, color = TextMuted)
                            Text("••••", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseGreen)
                        }
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 6) newPinInput = it },
                            label = { Text("New 4-6 digit PIN") },
                            placeholder = { Text("e.g. 1234") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = confirmPinInput,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 6) confirmPinInput = it },
                            label = { Text("Confirm New PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (pinErrorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(pinErrorMessage ?: "", fontSize = 12.sp, color = DangerRed)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (newPinInput.length < 4) {
                                    pinErrorMessage = "PIN must be at least 4 digits"
                                } else if (newPinInput != confirmPinInput) {
                                    pinErrorMessage = "PINs do not match"
                                } else {
                                    viewModel.setSecurityPin(newPinInput)
                                    isEditingPin = false
                                    pinErrorMessage = null
                                    Toast.makeText(context, "Security PIN updated successfully!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("Save Security PIN", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 3: ACCOUNT & CLOUD BACKUP
            Card(
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .border(2.dp, PulseGreen, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!currentUser?.avatarBase64.isNullOrEmpty()) {
                                AsyncImage(
                                    model = currentUser?.avatarBase64,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    currentUser?.displayName?.take(1)?.uppercase() ?: "P",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentUser?.displayName ?: "User", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                            Text(currentUser?.email ?: "", fontSize = 12.sp, color = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF131D2E))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Friend Code", fontSize = 11.sp, color = TextMuted)
                            Text(currentUser?.friendCode ?: "---", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseGreen)
                        }

                        IconButton(
                            onClick = {
                                val code = currentUser?.friendCode ?: return@IconButton
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Friend Code", code))
                                Toast.makeText(context, "Friend code copied!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = RadarBlue, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.syncCloudBackup()
                                Toast.makeText(context, "Account synced to cloud!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Cloud", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                viewModel.signOut()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D1515)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign Out", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 4: MAP & TRACKING PREFERENCES
            Card(
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Map & Tracking", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Map Style Switcher Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("streets" to "Streets", "satellite" to "Satellite", "hybrid" to "Hybrid").forEach { (styleKey, styleName) ->
                            val isSelected = mapStyle.equals(styleKey, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) RadarBlue else Color(0xFF1E293B))
                                    .clickable { viewModel.setMapStyle(styleKey) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = styleName,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.White else TextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // My Marker on Radar
                    Text("My Marker on Radar", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Prevent obscuring friends when they are standing close to you:",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "FULL" to "Full Profile",
                            "DOT" to "Discreet Dot",
                            "HIDDEN" to "Hidden"
                        ).forEach { (modeKey, modeName) ->
                            val isSelected = selfMarkerMode.equals(modeKey, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) RadarBlue else Color(0xFF1E293B))
                                    .clickable { viewModel.setSelfMarkerMode(modeKey) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = modeName,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.White else TextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 24/7 Background Exemption
                    Button(
                        onClick = {
                            (context as? com.pinpulse.radar.MainActivity)?.requestBatteryOptimizationExemption(force = true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure 24/7 Battery Whitelist", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
