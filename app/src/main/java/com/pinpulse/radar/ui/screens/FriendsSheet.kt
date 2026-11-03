package com.pinpulse.radar.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pinpulse.radar.util.UserAvatar
import com.pinpulse.radar.model.FriendRequest
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

enum class AddFriendMode {
    CODE,
    EMAIL
}

@Composable
fun FriendsSheet(
    friends: List<User>,
    pendingRequests: List<FriendRequest>,
    currentUser: User?,
    viewModel: MainViewModel,
    onSelectFriend: (User) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var addMode by remember { mutableStateOf(AddFriendMode.CODE) }
    var addFriendInput by remember { mutableStateOf("") }
    val registeredUsers by viewModel.registeredUsers.collectAsState()

    val isCodeMode = addMode == AddFriendMode.CODE
    val cleanCode = addFriendInput.replace("-", "").trim().uppercase()
    val isCompleteCode = isCodeMode && cleanCode.length == 8 && cleanCode.all { it.isLetterOrDigit() }
    val isCompleteEmail = !isCodeMode && addFriendInput.contains("@") && addFriendInput.substringAfter("@").contains(".") && android.util.Patterns.EMAIL_ADDRESS.matcher(addFriendInput.trim()).matches()
    val isComplete = if (isCodeMode) isCompleteCode else isCompleteEmail

    LaunchedEffect(addFriendInput, addMode) {
        if (isCodeMode && isCompleteCode) {
            viewModel.queryUserByCode(addFriendInput)
        } else if (!isCodeMode && isCompleteEmail) {
            viewModel.queryUserByEmail(addFriendInput)
        }
    }

    val foundUser = remember(addFriendInput, addMode, registeredUsers) {
        if (isCodeMode) {
            if (isCompleteCode) viewModel.lookupUserByCode(addFriendInput) else null
        } else {
            if (isCompleteEmail) viewModel.lookupUserByEmail(addFriendInput) else null
        }
    }

    val isSelf = isComplete && (
        (isCodeMode && cleanCode.equals(currentUser?.friendCode?.replace("-", "")?.trim(), ignoreCase = true)) ||
        (!isCodeMode && addFriendInput.trim().equals(currentUser?.email?.trim(), ignoreCase = true)) ||
        (foundUser != null && foundUser.id == currentUser?.id)
    )
    val isAlreadyFriend = isComplete && foundUser != null && friends.any {
        it.id == foundUser.id ||
        (isCodeMode && it.friendCode.replace("-", "").equals(cleanCode, ignoreCase = true)) ||
        (!isCodeMode && it.email.isNotBlank() && it.email.equals(addFriendInput.trim(), ignoreCase = true))
    }
    val canSend = isComplete && foundUser != null && !isSelf && !isAlreadyFriend

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // My Friend Code Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Your Friend Code", fontSize = 12.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        currentUser?.friendCode ?: "---",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PulseGreen
                    )
                }

                Button(
                    onClick = {
                        val code = currentUser?.friendCode ?: return@Button
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Friend Code", code))
                        Toast.makeText(context, "Friend code copied!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RadarBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Mode Selector: Friend Code vs Email
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF131D2E))
                .border(1.dp, RadarCardBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCodeMode) RadarBlue else Color.Transparent)
                    .clickable {
                        if (addMode != AddFriendMode.CODE) {
                            addMode = AddFriendMode.CODE
                            addFriendInput = ""
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Friend Code",
                    fontSize = 13.sp,
                    fontWeight = if (isCodeMode) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCodeMode) Color.White else TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!isCodeMode) RadarBlue else Color.Transparent)
                    .clickable {
                        if (addMode != AddFriendMode.EMAIL) {
                            addMode = AddFriendMode.EMAIL
                            addFriendInput = ""
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Email Address",
                    fontSize = 13.sp,
                    fontWeight = if (!isCodeMode) FontWeight.Bold else FontWeight.Medium,
                    color = if (!isCodeMode) Color.White else TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Add Friend Input
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = addFriendInput,
                    onValueChange = { newValue ->
                        addFriendInput = if (isCodeMode) {
                            formatFriendCodeInput(newValue, addFriendInput)
                        } else {
                            newValue.trim().lowercase()
                        }
                    },
                    placeholder = {
                        Text(
                            if (isCodeMode) "Enter 8-digit Code (e.g. ABCD-1234)" else "Enter Email (e.g. friend@email.com)",
                            color = TextMuted
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (foundUser != null) PulseGreen else RadarBlue,
                        unfocusedBorderColor = if (foundUser != null) PulseGreen.copy(alpha = 0.6f) else RadarCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (canSend) {
                            val target = addFriendInput.trim()
                            viewModel.sendFriendRequest(target, isEmail = !isCodeMode)
                            val targetName = foundUser?.displayName ?: target
                            Toast.makeText(context, "Friend request sent to $targetName!", Toast.LENGTH_SHORT).show()
                            addFriendInput = ""
                        }
                    },
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PulseGreen,
                        disabledContainerColor = Color(0xFF1E293B)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Add",
                        tint = if (canSend) Color.Black else TextMuted
                    )
                }
            }

            // Realtime Feedback: ONLY shown when code or email is complete!
            if (isComplete) {
                Spacer(modifier = Modifier.height(6.dp))
                when {
                    isSelf -> {
                        Text(
                            text = if (isCodeMode) "⚠️ You cannot add your own Friend Code" else "⚠️ You cannot add your own email address",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF59E0B),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    isAlreadyFriend -> {
                        Text(
                            text = "ℹ️ Already friends with ${foundUser?.displayName ?: "this user"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    foundUser != null -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2818)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PulseGreen.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1B4D2E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    UserAvatar(
                                        avatarSource = foundUser.avatarBase64,
                                        displayName = foundUser.displayName,
                                        modifier = Modifier.fillMaxSize(),
                                        fallbackTextSize = 14.sp,
                                        fallbackColor = PulseGreen
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        foundUser.displayName,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        if (isCodeMode) "Friend Code: ${foundUser.friendCode.ifBlank { addFriendInput }}" else "Email: ${foundUser.email.ifBlank { addFriendInput }}",
                                        fontSize = 11.sp,
                                        color = PulseGreen
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = if (isCodeMode) "❌ Friend Code \"$addFriendInput\" not found" else "❌ Account with email \"$addFriendInput\" not found",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DangerRed,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pending Friend Requests (if any)
        if (pendingRequests.isNotEmpty()) {
            Text("Pending Requests (${pendingRequests.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFF59E0B))
            Spacer(modifier = Modifier.height(8.dp))
            pendingRequests.forEach { req ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF252836))
                                .border(1.dp, Color(0xFFF59E0B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            UserAvatar(
                                avatarSource = req.fromUserAvatar,
                                displayName = req.fromUserName,
                                modifier = Modifier.fillMaxSize(),
                                fallbackTextSize = 15.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(req.fromUserName, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(req.fromUserEmail.ifBlank { req.fromFriendCode }, fontSize = 12.sp, color = TextMuted)
                        }
                        IconButton(onClick = { viewModel.acceptFriendRequest(req) }) {
                            Icon(Icons.Default.Check, contentDescription = "Accept", tint = PulseGreen)
                        }
                        IconButton(onClick = { viewModel.declineFriendRequest(req.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Decline", tint = DangerRed)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Friends List
        Text("Friends (${friends.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        if (friends.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No friends yet. Share your friend code to connect!", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(friends) { friend ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFriend(friend) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF252836))
                                    .border(
                                        2.dp,
                                        if (friend.isLive) PulseGreen else Color(0xFF64748B),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                UserAvatar(
                                    avatarSource = friend.avatarBase64,
                                    displayName = friend.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackTextSize = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(friend.displayName, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(
                                    if (friend.isLive) "● Live now" else "○ ${friend.lastSeenText}",
                                    fontSize = 12.sp,
                                    color = if (friend.isLive) PulseGreen else TextMuted
                                )
                            }

                            IconButton(onClick = { viewModel.removeFriendMutually(friend.id) }) {
                                Icon(
                                    Icons.Default.PersonRemove,
                                    contentDescription = "Remove Friend",
                                    tint = DangerRed.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFriendCodeInput(input: String, previous: String): String {
    // Allow backspacing past the hyphen
    if (previous.endsWith("-") && input == previous.dropLast(1)) {
        return input.dropLast(1)
    }

    val clean = input.filter { it.isLetterOrDigit() }.uppercase().take(8)
    return when {
        clean.length > 4 -> "${clean.take(4)}-${clean.drop(4)}"
        clean.length == 4 -> if (previous.length == 5 && previous.endsWith("-")) clean else "$clean-"
        else -> clean
    }
}
