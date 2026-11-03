package com.pinpulse.radar.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.viewmodel.MainViewModel

@Composable
fun SirenPinDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hasPin = viewModel.hasSecurityPin()
    var pinInput by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    fun triggerErrorVibration(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(250)
            }
        } catch (e: Exception) {}
    }

    fun submitPin() {
        val success = viewModel.verifyPinAndStopSiren(context, pinInput)
        if (success) {
            Toast.makeText(context, "Siren and vibration silenced!", Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            hasError = true
            triggerErrorVibration(context)
            pinInput = ""
        }
    }

    Dialog(
        onDismissRequest = { /* Cannot dismiss while siren is actively ringing! */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1414)),
                border = androidx.compose.foundation.BorderStroke(2.dp, DangerRed),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Flashing Alarm Icon
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(DangerRed.copy(alpha = 0.2f))
                            .border(2.dp, DangerRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = "Siren Alert",
                            tint = DangerRed,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "SIREN RINGING",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = DangerRed
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (hasPin) {
                            "Phone is ringing at maximum volume.\nEnter your Security PIN to stop audio and vibration."
                        } else {
                            "Phone is ringing at maximum volume.\nNo Security PIN was configured on this device."
                        },
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    if (!hasPin) {
                        Button(
                            onClick = {
                                viewModel.stopSirenDirectly(context)
                                Toast.makeText(context, "Siren and vibration silenced!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Silence Siren Now",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 6) {
                                    pinInput = it
                                    hasError = false
                                }
                            },
                            placeholder = { Text("Enter PIN", color = TextMuted) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = if (hasError) DangerRed else PulseGreen) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            keyboardActions = KeyboardActions(onDone = { submitPin() }),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = hasError,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (hasError) DangerRed else PulseGreen,
                                unfocusedBorderColor = if (hasError) DangerRed else DangerRed.copy(alpha = 0.5f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        if (hasError) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "❌ Incorrect Security PIN! Alarm will continue ringing.",
                                color = DangerRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { submitPin() },
                            enabled = pinInput.length >= 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PulseGreen,
                                disabledContainerColor = Color(0xFF2A2020)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Unlock & Stop Siren",
                                color = if (pinInput.length >= 4) Color.Black else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
