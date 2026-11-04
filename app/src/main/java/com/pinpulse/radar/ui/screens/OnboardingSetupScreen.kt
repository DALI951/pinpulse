package com.pinpulse.radar.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCyan
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.viewmodel.MainViewModel
import com.pinpulse.radar.util.UserAvatar

@Composable
fun OnboardingSetupScreen(
    viewModel: MainViewModel,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()

    val screenHeightDp = configuration.screenHeightDp
    val isCompact = screenHeightDp < 740

    val currentUser by viewModel.currentUser.collectAsState()

    var currentStep by remember { mutableIntStateOf(1) } // 1 = PIN, 2 = Profile, 3 = Welcome Guide

    // Step 1: Security PIN
    var pinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Step 2: Profile Customization
    var displayName by remember(currentUser?.displayName) { mutableStateOf(currentUser?.displayName ?: "") }
    var statusTag by remember { mutableStateOf("🏠 Home") }
    val presetTags = listOf("🏠 Home", "💼 Working", "🚗 On the road", "🎓 School", "🏃 Gym", "☕ Coffee", "✈️ Traveling")

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updatePersonalAvatar(uri, context)
            Toast.makeText(context, "Avatar updated!", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RadarDarkBg)
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = if (isCompact) 16.dp else 24.dp, vertical = if (isCompact) 14.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Step Progress Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepIndicatorItem(stepNumber = 1, title = "Security PIN", isActive = currentStep >= 1, isCurrent = currentStep == 1)
                Box(modifier = Modifier.weight(1f).height(2.dp).background(if (currentStep >= 2) PulseGreen else Color(0xFF334155)))
                StepIndicatorItem(stepNumber = 2, title = "Profile", isActive = currentStep >= 2, isCurrent = currentStep == 2)
                Box(modifier = Modifier.weight(1f).height(2.dp).background(if (currentStep >= 3) PulseGreen else Color(0xFF334155)))
                StepIndicatorItem(stepNumber = 3, title = "App Guide", isActive = currentStep >= 3, isCurrent = currentStep == 3)
            }

            // Step Content Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(if (isCompact) 18.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (currentStep) {
                        1 -> {
                            // ==========================================
                            // STEP 1: SECURITY PIN (MANDATORY, NO SKIP)
                            // ==========================================
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0F172A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(28.dp))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Create Your Security PIN",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Choose a 4 to 6 digit PIN. This PIN protects sensitive settings, prevents unauthorized changes, and is required to disarm loud emergency sirens.",
                                fontSize = 12.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                            )

                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                                label = { Text("Security PIN (4-6 digits)") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = PulseGreen,
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = confirmPinInput,
                                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPinInput = it },
                                label = { Text("Confirm PIN") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = PulseGreen,
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )

                            if (pinError != null) {
                                Text(
                                    text = pinError ?: "",
                                    color = DangerRed,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    if (pinInput.length < 4) {
                                        pinError = "PIN must be between 4 and 6 digits."
                                    } else if (pinInput != confirmPinInput) {
                                        pinError = "PINs do not match."
                                    } else {
                                        viewModel.setSecurityPin(pinInput)
                                        pinError = null
                                        currentStep = 2
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                            ) {
                                Text("Save PIN & Continue", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        2 -> {
                            // ==========================================
                            // STEP 2: PROFILE CUSTOMIZATION (CAN SKIP)
                            // ==========================================
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .clickable { photoPicker.launch("image/*") }
                                    .border(2.dp, PulseGreen, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                UserAvatar(
                                    avatarBase64 = currentUser?.avatarBase64,
                                    displayName = displayName.ifBlank { "User" },
                                    size = 76.dp
                                )
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0x33000000)),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = "Change photo", tint = Color.White, modifier = Modifier.size(16.dp).padding(bottom = 2.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Tap photo to change avatar", fontSize = 11.sp, color = PulseGreen)

                            Spacer(modifier = Modifier.height(14.dp))

                            Text("Customize Radar Presence", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Set how friends and circles see you on the live radar map.", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center)

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { if (it.length <= 30) displayName = it },
                                label = { Text("Display Name (how you appear on map)") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = RadarBlue,
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text("Radar Status Tag", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.align(Alignment.Start))
                            Spacer(modifier = Modifier.height(6.dp))

                            // Tag Chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                presetTags.take(3).forEach { tag ->
                                    FilterChip(
                                        selected = statusTag == tag,
                                        onClick = { statusTag = tag },
                                        label = { Text(tag, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PulseGreen,
                                            selectedLabelColor = Color(0xFF0F172A)
                                        )
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                presetTags.drop(3).take(3).forEach { tag ->
                                    FilterChip(
                                        selected = statusTag == tag,
                                        onClick = { statusTag = tag },
                                        label = { Text(tag, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PulseGreen,
                                            selectedLabelColor = Color(0xFF0F172A)
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Actions: Skip vs Save & Continue
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { currentStep = 3 },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Skip", color = TextMuted)
                                }

                                Button(
                                    onClick = {
                                        if (displayName.isNotBlank()) {
                                            viewModel.updateDisplayName(displayName.trim())
                                        }
                                        viewModel.updateMyStatusMessage(statusTag)
                                        currentStep = 3
                                    },
                                    modifier = Modifier.weight(1.5f).height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                                ) {
                                    Text("Save & Continue", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        3 -> {
                            // ==========================================
                            // STEP 3: INTERACTIVE GUIDE & "GOOD USE" TIPS
                            // ==========================================
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0F172A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(30.dp))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Welcome to PinPulse!", fontSize = 21.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                            Text("Here are tips to get the most out of your live safe circle radar.", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center)

                            Spacer(modifier = Modifier.height(16.dp))

                            // Feature cards
                            GuideCard(
                                icon = Icons.Default.Radar,
                                iconTint = RadarCyan,
                                title = "Smart Hybrid Live Radar",
                                description = "Updates live (1–3s) when you or friends view the map. Sleeps silently in passive standby when idle to maximize battery."
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GuideCard(
                                icon = Icons.Default.Groups,
                                iconTint = PulseGreen,
                                title = "Friend Code & Private Circles",
                                description = "Share your unique 8-character Friend Code or create Family & Business circles to keep loved ones connected."
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GuideCard(
                                icon = Icons.Default.NotificationsActive,
                                iconTint = DangerRed,
                                title = "Emergency SOS & Siren",
                                description = "Trigger loud sirens or instant SOS coordinates in emergencies. Your newly created PIN safely disarms alerts."
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GuideCard(
                                icon = Icons.Default.BatteryChargingFull,
                                iconTint = RadarBlue,
                                title = "24/7 Safety Permissions",
                                description = "Ensure location permissions are set to 'Allow all the time' so your circle can protect you even with the screen off."
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    viewModel.completeOnboardingSetup()
                                    onFinish()
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                            ) {
                                Text("Start Using PinPulse 🚀", color = Color(0xFF0F172A), fontWeight = FontWeight.Black, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicatorItem(
    stepNumber: Int,
    title: String,
    isActive: Boolean,
    isCurrent: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isActive) PulseGreen else Color(0xFF334155)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF0F172A) else Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) Color.White else TextMuted
        )
    }
}

@Composable
private fun GuideCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(description, fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp)
        }
    }
}
