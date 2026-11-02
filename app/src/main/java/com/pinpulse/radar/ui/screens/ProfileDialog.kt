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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
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

@Composable
fun ProfileDialog(
    user: User,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Personal Avatar with Change Photo overlay
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF252836))
                        .border(3.dp, if (user.isSharingLocation) PulseGreen else DangerRed, CircleShape)
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (!user.avatarBase64.isNullOrEmpty()) {
                        AsyncImage(
                            model = user.avatarBase64,
                            contentDescription = user.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            user.displayName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

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
                                .size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Tap avatar to change photo from phone", fontSize = 11.sp, color = TextMuted)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = user.displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Text(
                    text = user.email,
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

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

                Spacer(modifier = Modifier.height(16.dp))

                // Location Sharing Toggle Button
                Button(
                    onClick = { viewModel.toggleLocationSharing() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (user.isSharingLocation) DangerRed else PulseGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (user.isSharingLocation) Icons.Default.LocationOff else Icons.Default.LocationOn,
                        contentDescription = "Toggle Sharing",
                        tint = if (user.isSharingLocation) Color.White else Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (user.isSharingLocation) "Turn Off Location Sharing" else "Turn On Location Sharing",
                        color = if (user.isSharingLocation) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold
                    )
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
}
