package com.pinpulse.radar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.viewmodel.KickConfirmationState
import com.pinpulse.radar.ui.viewmodel.MainViewModel

@Composable
fun KickConfirmationDialog(
    state: KickConfirmationState,
    viewModel: MainViewModel
) {
    Dialog(onDismissRequest = { viewModel.cancelKickFlow() }) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = "Warning",
                    tint = DangerRed,
                    modifier = Modifier.height(48.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (state.step == 1) {
                    // --- STEP 1 PROMPT ---
                    Text(
                        text = "Kick ${state.member.displayName}?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Are you sure you want to remove this member from the group '${state.group.name}'?",
                        fontSize = 14.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.cancelKickFlow() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel", color = TextPrimary)
                        }

                        Button(
                            onClick = { viewModel.proceedToStep2Countdown() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Continue", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // --- STEP 2 FINAL COUNTDOWN CONFIRMATION ---
                    Text(
                        text = "Final Confirmation",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DangerRed
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "False-removal protection active. Confirming removal of ${state.member.displayName}.",
                        fontSize = 14.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (state.countdownRemaining > 0) {
                        Text(
                            text = "Please wait ${state.countdownRemaining}s before confirming...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF59E0B)
                        )
                    } else {
                        Text(
                            text = "Protection delay complete. You may now confirm.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DangerRed
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.cancelKickFlow() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel", color = TextPrimary)
                        }

                        Button(
                            onClick = { viewModel.confirmKickMember() },
                            enabled = state.countdownRemaining == 0,
                            modifier = Modifier.weight(1.3f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed,
                                disabledContainerColor = Color(0xFF451A1A)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (state.countdownRemaining > 0) {
                                    "Wait (${state.countdownRemaining}s)"
                                } else {
                                    "Yes, Kick"
                                },
                                color = if (state.countdownRemaining == 0) Color.White else Color(0xFF999999),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
