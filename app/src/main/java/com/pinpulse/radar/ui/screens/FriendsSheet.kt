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
    var addFriendInput by remember { mutableStateOf("") }

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

        // Add Friend Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addFriendInput,
                onValueChange = { addFriendInput = it },
                placeholder = { Text("Enter Friend Code or Email") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (addFriendInput.isNotBlank()) {
                        viewModel.sendFriendRequest(addFriendInput)
                        Toast.makeText(context, "Friend request sent!", Toast.LENGTH_SHORT).show()
                        addFriendInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add", tint = Color.Black)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(req.fromUserName, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(req.fromUserEmail, fontSize = 12.sp, color = TextMuted)
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
                                if (!friend.avatarBase64.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = friend.avatarBase64,
                                        contentDescription = friend.displayName,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        friend.displayName.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
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
