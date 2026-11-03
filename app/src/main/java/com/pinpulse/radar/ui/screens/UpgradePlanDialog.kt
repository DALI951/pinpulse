package com.pinpulse.radar.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pinpulse.radar.model.User
import com.pinpulse.radar.ui.theme.*

@Composable
fun UpgradePlanDialog(
    currentUser: User?,
    onDismiss: () -> Unit,
    onPlanActivated: (String, Int, Int) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var selectedPlan by remember { mutableStateOf("FAMILY") }
    var selectedPaymentMethod by remember { mutableStateOf("CARD") } // "CARD", "GPAY", "PAYPAL", "CODE"

    var cardNumber by remember { mutableStateOf("•••• •••• •••• 4242") }
    var cardExpiry by remember { mutableStateOf("12/28") }
    var cardCvv by remember { mutableStateOf("888") }
    var cardName by remember { mutableStateOf(currentUser?.displayName ?: "Cardholder") }
    var promoCode by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val planPrice = when (selectedPlan) {
        "FAMILY" -> "$9.99 / mo"
        "BUSINESS" -> "$29.99 / mo"
        "CUSTOM" -> "$79.99 / mo"
        "LIFETIME" -> "$99.00 One-Time"
        else -> "$0.00"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030712).copy(alpha = 0.95f))
                .padding(horizontal = 14.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
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
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(PulseGreen.copy(alpha = 0.2f))
                                    .border(1.dp, PulseGreen, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = PulseGreen, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Upgrade PinPulse Plan",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Select pack & preview payment gateway",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Scrollable content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "CHOOSE YOUR PLAN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Plan Option 1: Family
                        PlanSelectionCard(
                            planKey = "FAMILY",
                            title = "Family Pack",
                            price = "$9.99/mo",
                            badge = "POPULAR",
                            features = listOf("10 Mutual Friends", "2 Family Circles", "2s High-Speed Rate Limit", "Emergency Siren & SOS Alerts"),
                            isSelected = selectedPlan == "FAMILY",
                            onSelect = { selectedPlan = "FAMILY" }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Plan Option 2: Business
                        PlanSelectionCard(
                            planKey = "BUSINESS",
                            title = "Business Fleet Pack",
                            price = "$29.99/mo",
                            badge = "FLEET",
                            features = listOf("50 Active Members", "10 Team Circles", "1s Ultra-Fast Street Tracking", "Command Web Console Link"),
                            isSelected = selectedPlan == "BUSINESS",
                            onSelect = { selectedPlan = "BUSINESS" }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Plan Option 3: Lifetime
                        PlanSelectionCard(
                            planKey = "LIFETIME",
                            title = "♾️ Lifetime Pass",
                            price = "$99.00 Once",
                            badge = "NEVER EXPIRES",
                            features = listOf("All Business & Family Quotas", "Never Expires (Forever Access)", "Zero Recurring Invoices", "VIP Direct Server Priority"),
                            isSelected = selectedPlan == "LIFETIME",
                            onSelect = { selectedPlan = "LIFETIME" }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Plan Option 4: Custom Enterprise
                        PlanSelectionCard(
                            planKey = "CUSTOM",
                            title = "Custom Enterprise",
                            price = "$79.99/mo",
                            badge = "100+ USERS",
                            features = listOf("100+ Custom Quota", "25+ Circles", "Dedicated MQTT Broker Channel", "Custom Invoicing & SLA"),
                            isSelected = selectedPlan == "CUSTOM",
                            onSelect = { selectedPlan = "CUSTOM" }
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Payment Methods
                        Text(
                            text = "PAYMENT METHOD (SANDBOX PREVIEW)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PaymentMethodChip(
                                label = "Card",
                                icon = Icons.Default.CreditCard,
                                isSelected = selectedPaymentMethod == "CARD",
                                modifier = Modifier.weight(1f),
                                onClick = { selectedPaymentMethod = "CARD" }
                            )
                            PaymentMethodChip(
                                label = "Google Pay",
                                icon = Icons.Default.Payment,
                                isSelected = selectedPaymentMethod == "GPAY",
                                modifier = Modifier.weight(1f),
                                onClick = { selectedPaymentMethod = "GPAY" }
                            )
                            PaymentMethodChip(
                                label = "PayPal",
                                icon = Icons.Default.AccountBalanceWallet,
                                isSelected = selectedPaymentMethod == "PAYPAL",
                                modifier = Modifier.weight(1f),
                                onClick = { selectedPaymentMethod = "PAYPAL" }
                            )
                            PaymentMethodChip(
                                label = "Code",
                                icon = Icons.Default.VpnKey,
                                isSelected = selectedPaymentMethod == "CODE",
                                modifier = Modifier.weight(1f),
                                onClick = { selectedPaymentMethod = "CODE" }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Mock Form based on selected method
                        if (selectedPaymentMethod == "CODE") {
                            OutlinedTextField(
                                value = promoCode,
                                onValueChange = { promoCode = it.uppercase() },
                                label = { Text("Enter Promo or Admin Activation Code", fontSize = 12.sp) },
                                placeholder = { Text("e.g. PINPULSE-VIP-2026", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PulseGreen,
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        } else {
                            // Credit Card / Gateway Fields
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF131D2E))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (selectedPaymentMethod == "GPAY") "Google Pay Express (Sandbox)" else (if (selectedPaymentMethod == "PAYPAL") "PayPal Checkout (Sandbox)" else "Card Details"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE2E8F0)
                                    )
                                    Text("TEST SANDBOX", fontSize = 10.sp, color = PulseGreen, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = cardNumber,
                                    onValueChange = { cardNumber = it },
                                    label = { Text("Card Number", fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = RadarBlue,
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = cardExpiry,
                                        onValueChange = { cardExpiry = it },
                                        label = { Text("Expires", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = RadarBlue,
                                            unfocusedBorderColor = Color(0xFF334155),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        )
                                    )
                                    OutlinedTextField(
                                        value = cardCvv,
                                        onValueChange = { cardCvv = it },
                                        label = { Text("CVV", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = RadarBlue,
                                            unfocusedBorderColor = Color(0xFF334155),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Sandbox note
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "💡 Payment integration is configured in preview sandbox mode. You can test plan upgrades or enter your Friend Code (${currentUser?.friendCode ?: "----"}) in the Web Dashboard for instant activation.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Checkout Button
                    Button(
                        onClick = {
                            isProcessing = true
                            val friendsQuota = when (selectedPlan) {
                                "FAMILY" -> 10
                                "BUSINESS" -> 50
                                "CUSTOM" -> 100
                                "LIFETIME" -> 50
                                else -> 1
                            }
                            val groupsQuota = when (selectedPlan) {
                                "FAMILY" -> 2
                                "BUSINESS" -> 10
                                "CUSTOM" -> 25
                                "LIFETIME" -> 10
                                else -> 0
                            }
                            Toast.makeText(
                                context,
                                "🎉 $selectedPlan Pack Order Simulated ($planPrice)! Connect payment gateway to enable live billing.",
                                Toast.LENGTH_LONG
                            ).show()
                            onPlanActivated(selectedPlan, friendsQuota, groupsQuota)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Confirm & Upgrade ($planPrice)",
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

@Composable
private fun PlanSelectionCard(
    planKey: String,
    title: String,
    price: String,
    badge: String,
    features: List<String>,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF132338) else Color(0xFF131D2E))
            .border(
                1.5.dp,
                if (isSelected) PulseGreen else Color(0xFF1E293B),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PulseGreen else Color.Transparent)
                            .border(1.5.dp, if (isSelected) PulseGreen else Color(0xFF64748B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Black))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) PulseGreen.copy(alpha = 0.2f) else Color(0xFF1E293B))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isSelected) PulseGreen else Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = price,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSelected) PulseGreen else Color(0xFF38BDF8),
                modifier = Modifier.padding(start = 28.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            features.forEach { feat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (isSelected) PulseGreen else Color(0xFF64748B),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = feat,
                        fontSize = 11.sp,
                        color = if (isSelected) Color(0xFFE2E8F0) else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodChip(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF131D2E))
            .border(1.dp, if (isSelected) PulseGreen else Color(0xFF334155), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) PulseGreen else Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else Color(0xFF94A3B8)
            )
        }
    }
}
