package com.pinpulse.radar.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

val QUICK_STATUS_PRESETS = listOf(
    "🏠 Home",
    "🚗 Driving",
    "☕ At Café",
    "📚 Studying",
    "💼 Working",
    "🏋️ Gym",
    "🍽️ Eating",
    "🔋 Low Battery"
)

@Composable
fun ProfileDialog(
    user: User,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var statusInput by remember(user.statusMessage) { mutableStateOf(user.statusMessage.orEmpty()) }
    var isEditingName by remember { mutableStateOf(false) }
    var nameInput by remember(user.displayName) { mutableStateOf(user.displayName) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var passwordChangeError by remember { mutableStateOf<String?>(null) }
    var isSavingPassword by remember { mutableStateOf(false) }
    var isSendingResetEmail by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updatePersonalAvatar(uri, context)
            Toast.makeText(context, "Avatar updated!", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Personal Avatar with Change Photo overlay
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF252836))
                        .border(3.dp, PulseGreen, CircleShape)
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    UserAvatar(
                        avatarSource = user.avatarBase64,
                        displayName = user.displayName,
                        modifier = Modifier.fillMaxSize(),
                        fallbackTextSize = 30.sp
                    )

                    // Overlay icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x55000000)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Change photo",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Tap avatar to change photo from phone", fontSize = 11.sp, color = TextMuted)

                Spacer(modifier = Modifier.height(10.dp))

                if (!isEditingName) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = user.displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                isEditingName = true
                                nameInput = user.displayName
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Name",
                                tint = PulseGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { if (it.length <= 30) nameInput = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = PulseGreen,
                                unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                val clean = nameInput.trim()
                                if (clean.isNotBlank()) {
                                    viewModel.updateDisplayName(clean)
                                    isEditingName = false
                                    Toast.makeText(context, "Username updated to \"$clean\"!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = nameInput.trim().isNotBlank(),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = PulseGreen, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { isEditingName = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = DangerRed, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Text(
                    text = user.email,
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                if (user.email.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                newPasswordInput = ""
                                confirmPasswordInput = ""
                                passwordChangeError = null
                                showChangePasswordDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Change Account Password",
                            fontSize = 12.sp,
                            color = PulseGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Radar Status Bubble Section
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "💬 Radar Status Bubble",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (!user.statusMessage.isNullOrBlank()) {
                                Text(
                                    text = "Clear",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed,
                                    modifier = Modifier
                                        .clickable {
                                            statusInput = ""
                                            viewModel.updateMyStatusMessage("")
                                            Toast.makeText(context, "Status bubble cleared", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Shown floating above your pin on everyone's map",
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 1-Tap Preset Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QUICK_STATUS_PRESETS.forEach { preset ->
                                val isSelected = user.statusMessage == preset
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) PulseGreen else Color(0xFF1E293B))
                                        .border(
                                            1.dp,
                                            if (isSelected) PulseGreen else RadarCardBorder,
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            statusInput = preset
                                            viewModel.updateMyStatusMessage(preset)
                                            Toast.makeText(context, "Status set to $preset", Toast.LENGTH_SHORT).show()
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

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = statusInput,
                                onValueChange = { if (it.length <= 28) statusInput = it },
                                placeholder = { Text("Custom (e.g. 🛒 Shopping)", fontSize = 12.sp, color = TextMuted) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.updateMyStatusMessage(statusInput)
                                    Toast.makeText(
                                        context,
                                        if (statusInput.isBlank()) "Status cleared" else "Status updated!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Set", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Friend Code Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Your Friend Code", fontSize = 11.sp, color = TextMuted)
                            Text(user.friendCode, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseGreen)
                        }
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Friend Code", user.friendCode))
                                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RadarBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 24/7 Always-On Live Radar Status Indicator
                Card(
                    colors = CardDefaults.cardColors(containerColor = PulseGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(PulseGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Live Radar Active (24/7 Continuous)",
                            color = PulseGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close", color = TextPrimary)
                }
            }
        }
    }

    if (showChangePasswordDialog) {
        val targetEmail = user.email
        AlertDialog(
            onDismissRequest = { showChangePasswordDialog = false },
            title = { Text("🔐 Change Account Password", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    Text(
                        text = "Set a new password directly for $targetEmail (instant, no email needed), or send a password reset link to your email:",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        label = { Text("New Password (min 6 chars)") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(
                                    imageVector = if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (newPasswordVisible) "Hide password" else "Show password",
                                    tint = TextMuted
                                )
                            }
                        },
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PulseGreen,
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPasswordInput,
                        onValueChange = { confirmPasswordInput = it },
                        label = { Text("Confirm New Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                                    tint = TextMuted
                                )
                            }
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PulseGreen,
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )

                    if (passwordChangeError != null) {
                        Text(
                            text = passwordChangeError ?: "",
                            color = DangerRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Direct In-App Save Button
                    Button(
                        onClick = {
                            if (newPasswordInput.length < 6) {
                                passwordChangeError = "Password must be at least 6 characters."
                            } else if (newPasswordInput != confirmPasswordInput) {
                                passwordChangeError = "Passwords do not match."
                            } else {
                                isSavingPassword = true
                                passwordChangeError = null
                                viewModel.setPasswordForCurrentAccount(newPasswordInput) { success, err ->
                                    isSavingPassword = false
                                    if (success) {
                                        showChangePasswordDialog = false
                                        Toast.makeText(context, "Password updated successfully!", Toast.LENGTH_LONG).show()
                                    } else {
                                        passwordChangeError = err ?: "Failed to update password."
                                    }
                                }
                            }
                        },
                        enabled = !isSavingPassword && !isSendingResetEmail,
                        colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        if (isSavingPassword) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF0F172A), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Saving...", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        } else {
                            Text("Update Password (Instant)", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Or Send Reset Email Button
                    OutlinedButton(
                        onClick = {
                            isSendingResetEmail = true
                            passwordChangeError = null
                            viewModel.sendPasswordReset(targetEmail) { success, err ->
                                isSendingResetEmail = false
                                if (success) {
                                    Toast.makeText(
                                        context,
                                        "Reset link sent to $targetEmail! Check your SPAM / JUNK folder if not in inbox.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showChangePasswordDialog = false
                                } else {
                                    passwordChangeError = err ?: "Failed to send reset email. Please try again."
                                }
                            }
                        },
                        enabled = !isSavingPassword && !isSendingResetEmail,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        if (isSendingResetEmail) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sending Email...", color = Color.White, fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Email, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Password Reset Email Link", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "💡 Tip: Emails from Firebase can sometimes land in the Spam/Junk folder (sender: noreply@pinpulse-81d6a.firebaseapp.com).",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChangePasswordDialog = false }) {
                    Text("Close", color = TextMuted)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}
